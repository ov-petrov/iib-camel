package ru.petrovov.iib_camel.runtime_lite.xml.schema;

import org.springframework.core.io.ResourceLoader;
import org.w3c.dom.ls.LSInput;

import javax.xml.XMLConstants;
import javax.xml.transform.sax.SAXSource;
import javax.xml.validation.Schema;
import javax.xml.validation.SchemaFactory;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.Reader;
import java.net.URI;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class XmlSchemaRegistry {

    private static final String CLASSPATH_PREFIX = "classpath:";

    private final ResourceLoader resourceLoader;
    private final ConcurrentMap<String, Schema> schemas = new ConcurrentHashMap<>();

    public XmlSchemaRegistry(ResourceLoader resourceLoader) {
        this.resourceLoader = Objects.requireNonNull(resourceLoader, "resourceLoader");
    }

    public Schema get(String schemaLocation) {
        String location = normalizeLocation(schemaLocation);
        return schemas.computeIfAbsent(location, this::load);
    }

    private Schema load(String location) {
        String rootDirectory = location.substring(0, location.lastIndexOf('/') + 1);
        try {
            var factory = SchemaFactory.newInstance(XMLConstants.W3C_XML_SCHEMA_NS_URI);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            factory.setResourceResolver((type, namespace, publicId, systemId, baseUri) ->
                resolve(rootDirectory, publicId, systemId, baseUri));
            byte[] schemaBytes = readResource(location);
            return factory.newSchema(new SAXSource(SecureSax.reader(), SecureSax.source(schemaBytes, location)));
        } catch (Exception e) {
            throw new XmlSchemaException("Could not load XSD schema " + location, e);
        }
    }

    private LSInput resolve(String rootDirectory, String publicId, String systemId, String baseUri) {
        if (systemId == null || baseUri == null || systemId.contains("\\") || systemId.contains("%")) {
            throw new XmlSchemaException("Invalid XSD schema dependency");
        }
        try {
            URI relative = URI.create(systemId);
            if (relative.isAbsolute() || relative.getRawAuthority() != null || relative.getRawQuery() != null
                || relative.getRawFragment() != null || relative.getPath().startsWith("/")) {
                throw new XmlSchemaException("Only relative classpath XSD dependencies are allowed");
            }
            String location = URI.create(baseUri).resolve(relative).normalize().toString();
            if (!location.startsWith(rootDirectory)) {
                throw new XmlSchemaException("XSD dependency is outside the schema directory");
            }
            byte[] bytes = readResource(location);
            SecureSax.reader().parse(SecureSax.source(bytes, location));
            return new SchemaInput(publicId, location, bytes);
        } catch (XmlSchemaException e) {
            throw e;
        } catch (Exception e) {
            throw new XmlSchemaException("Could not load XSD schema dependency", e);
        }
    }

    private byte[] readResource(String location) throws IOException {
        try (var input = resourceLoader.getResource(location).getInputStream()) {
            return input.readAllBytes();
        }
    }

    private static String normalizeLocation(String location) {
        if (location == null || !location.startsWith(CLASSPATH_PREFIX)) {
            throw new IllegalArgumentException("Schema location must start with classpath:");
        }
        String path = location.substring(CLASSPATH_PREFIX.length());
        if (path.isBlank() || path.contains("\\") || path.contains("%") || path.contains("?")
            || path.contains("#") || path.contains(":")) {
            throw new IllegalArgumentException("Invalid classpath XSD schema location");
        }
        String absolutePath = path.startsWith("/") ? path : "/" + path;
        URI normalized = URI.create("classpath:" + absolutePath).normalize();
        if (!normalized.getPath().equals(absolutePath) || absolutePath.startsWith("//")) {
            throw new IllegalArgumentException("XSD schema location must be normalized");
        }
        return normalized.toString();
    }

    private static final class SchemaInput implements LSInput {
        private final String publicId;
        private final String systemId;
        private final InputStream byteStream;

        private SchemaInput(String publicId, String systemId, byte[] bytes) {
            this.publicId = publicId;
            this.systemId = systemId;
            this.byteStream = new ByteArrayInputStream(bytes);
        }

        @Override
        public Reader getCharacterStream() {
            return null;
        }

        @Override
        public void setCharacterStream(Reader characterStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public InputStream getByteStream() {
            return byteStream;
        }

        @Override
        public void setByteStream(InputStream byteStream) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getStringData() {
            return null;
        }

        @Override
        public void setStringData(String stringData) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getSystemId() {
            return systemId;
        }

        @Override
        public void setSystemId(String systemId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getPublicId() {
            return publicId;
        }

        @Override
        public void setPublicId(String publicId) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getBaseURI() {
            return systemId;
        }

        @Override
        public void setBaseURI(String baseURI) {
            throw new UnsupportedOperationException();
        }

        @Override
        public String getEncoding() {
            return null;
        }

        @Override
        public void setEncoding(String encoding) {
            throw new UnsupportedOperationException();
        }

        @Override
        public boolean getCertifiedText() {
            return false;
        }

        @Override
        public void setCertifiedText(boolean certifiedText) {
            throw new UnsupportedOperationException();
        }
    }
}
