package ru.petrovov.iib_camel.runtime_lite.xsd;

import org.w3c.dom.Element;
import org.w3c.dom.ls.DOMImplementationLS;
import org.xml.sax.EntityResolver;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.ext.EntityResolver2;
import org.xml.sax.helpers.DefaultHandler;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayDeque;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

final class SchemaInputs {

    private static final String XSD = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final Set<String> DEPENDENCIES = Set.of("include", "import", "redefine");

    private final Path entry;
    private final Map<Path, byte[]> documents;
    private final Set<QName> roots;
    private final Schema schema;

    private SchemaInputs(Path entry, Map<Path, byte[]> documents, Set<QName> roots, DOMImplementationLS dom)
        throws SAXException {
        this.entry = entry;
        this.documents = Map.copyOf(documents);
        this.roots = Collections.unmodifiableSet(new LinkedHashSet<>(roots));

        var factory = SchemaFactory.newDefaultInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(XMLConstants.USE_CATALOG, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setResourceResolver((type, namespace, publicId, systemId, baseUri) -> {
            if (systemId == null) {
                return null;
            }
            try {
                var source = resolve(publicId, baseUri, systemId);
                var input = dom.createLSInput();
                input.setPublicId(publicId);
                input.setSystemId(source.getSystemId());
                input.setByteStream(source.getByteStream());
                return input;
            } catch (SAXException failure) {
                throw new IllegalArgumentException(failure.getMessage(), failure);
            }
        });
        this.schema = factory.newSchema(new SAXSource(entrySource()));
    }

    static SchemaInputs load(Path suppliedEntry) throws IOException, SAXException, ParserConfigurationException {
        var input = scan(suppliedEntry);
        return new SchemaInputs(input.entry(), input.documents(), input.roots(), input.dom());
    }

    static List<Path> entrySchemas(Path suppliedDirectory) throws IOException, SAXException, ParserConfigurationException {
        if (!Files.isDirectory(suppliedDirectory)) {
            throw new IOException("Input directory does not exist: " + suppliedDirectory.toAbsolutePath());
        }
        Path directory = suppliedDirectory.toRealPath();
        List<Path> entries;
        try (var files = Files.list(directory)) {
            entries = files.filter(Files::isRegularFile)
                .filter(path -> path.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".xsd"))
                .sorted(Comparator.comparing(path -> path.getFileName().toString()))
                .toList();
        }
        if (entries.isEmpty()) {
            throw new IOException("No XSD files in input directory: " + directory);
        }
        var graphs = new LinkedHashMap<Path, Set<Path>>();
        for (Path entry : entries) {
            try {
                graphs.put(entry, scan(entry).documents().keySet());
            } catch (IOException | SAXException invalid) {
                throw new IOException(entry + ": " + invalid.getMessage(), invalid);
            }
        }
        return entries.stream().filter(entry -> entries.stream().noneMatch(other ->
            !entry.equals(other) && graphs.get(other).contains(entry)
                && (!graphs.get(entry).contains(other)
                    || other.getFileName().toString().compareTo(entry.getFileName().toString()) < 0)))
            .toList();
    }

    private static ScannedInputs scan(Path suppliedEntry) throws IOException, SAXException, ParserConfigurationException {
        Path absolute = suppliedEntry.toAbsolutePath().normalize();
        if (absolute.getParent() == null) {
            throw new IOException("Expected a schema file: " + absolute);
        }
        Path directory = absolute.getParent().toRealPath();
        Path entry = directory.resolve(absolute.getFileName());

        var factory = DocumentBuilderFactory.newDefaultNSInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature(XMLConstants.USE_CATALOG, false);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        var parser = factory.newDocumentBuilder();
        parser.setErrorHandler(new DefaultHandler() {
            @Override
            public void error(SAXParseException exception) throws SAXException {
                throw exception;
            }

            @Override
            public void fatalError(SAXParseException exception) throws SAXException {
                throw exception;
            }
        });

        var documents = new LinkedHashMap<Path, byte[]>();
        var roots = new LinkedHashSet<QName>();
        var pending = new ArrayDeque<Path>();
        pending.add(entry);
        while (!pending.isEmpty()) {
            Path file = pending.removeFirst();
            if (documents.containsKey(file)) {
                continue;
            }
            Path realFile = file.toRealPath();
            if (!realFile.startsWith(directory) || !Files.isRegularFile(realFile)) {
                throw new IOException("Schema must be a file within the entry schema directory: " + file);
            }
            byte[] content = Files.readAllBytes(realFile);
            documents.put(file, content);
            Element root = parser.parse(source(file, content)).getDocumentElement();
            if (!XSD.equals(root.getNamespaceURI()) || !"schema".equals(root.getLocalName())) {
                throw new IOException("Expected an XML Schema document: " + file);
            }
            var children = root.getChildNodes();
            for (int index = 0; index < children.getLength(); index++) {
                if (!(children.item(index) instanceof Element element) || !XSD.equals(element.getNamespaceURI())) {
                    continue;
                }
                String localName = element.getLocalName();
                if (entry.equals(file) && "element".equals(localName) && element.hasAttribute("name")) {
                    roots.add(new QName(root.getAttribute("targetNamespace"), element.getAttribute("name")));
                }
                if (DEPENDENCIES.contains(localName) && element.hasAttribute("schemaLocation")) {
                    pending.add(dependency(directory, file, element.getAttribute("schemaLocation")));
                }
            }
        }
        var dom = (DOMImplementationLS) parser.getDOMImplementation().getFeature("LS", "3.0");
        return new ScannedInputs(entry, documents, roots, dom);
    }

    private record ScannedInputs(Path entry, Map<Path, byte[]> documents, Set<QName> roots, DOMImplementationLS dom) {
    }

    private static Path dependency(Path directory, Path file, String location) throws IOException {
        try {
            URI uri = URI.create(location.strip().replace(" ", "%20"));
            if (!uri.isAbsolute() && uri.getRawAuthority() == null && uri.getRawQuery() == null
                && uri.getRawFragment() == null && uri.getPath() != null && !uri.getPath().isBlank()
                && uri.getPath().indexOf('\\') < 0) {
                Path relative = Path.of(uri.getPath());
                Path candidate = file.resolveSibling(relative).normalize();
                if (!relative.isAbsolute() && candidate.startsWith(directory)) {
                    return candidate;
                }
            }
        } catch (IllegalArgumentException invalid) {
            throw new IOException("Invalid schemaLocation: " + location, invalid);
        }
        throw new IOException("Only relative schema locations inside the entry schema directory are allowed: " + location);
    }

    Set<QName> roots() {
        return roots;
    }

    Schema schema() {
        return schema;
    }

    InputSource entrySource() {
        return source(entry, documents.get(entry));
    }

    EntityResolver entityResolver() {
        return new EntityResolver2() {
            @Override
            public InputSource getExternalSubset(String name, String baseUri) {
                return null;
            }

            @Override
            public InputSource resolveEntity(String name, String publicId, String baseUri, String systemId)
                throws SAXException {
                return resolve(publicId, baseUri, systemId);
            }

            @Override
            public InputSource resolveEntity(String publicId, String systemId) throws SAXException {
                return resolve(publicId, null, systemId);
            }
        };
    }

    private InputSource resolve(String publicId, String baseUri, String systemId) throws SAXException {
        if (systemId == null) {
            throw new SAXException("Schema resource has no identifier");
        }
        try {
            URI base = baseUri == null ? entry.toUri() : URI.create(baseUri);
            URI uri = base.resolve(systemId.replace(" ", "%20"));
            if ("file".equalsIgnoreCase(uri.getScheme())) {
                Path file = Path.of(uri).normalize();
                byte[] content = documents.get(file);
                if (content != null) {
                    var source = source(file, content);
                    source.setPublicId(publicId);
                    return source;
                }
            }
        } catch (IllegalArgumentException invalid) {
            throw new SAXException("Invalid schema resource identifier: " + systemId, invalid);
        }
        throw new SAXException("Schema resource is outside the verified local graph: " + systemId);
    }

    private static InputSource source(Path file, byte[] content) {
        var source = new InputSource(new ByteArrayInputStream(content));
        source.setSystemId(file.toUri().toASCIIString());
        return source;
    }
}
