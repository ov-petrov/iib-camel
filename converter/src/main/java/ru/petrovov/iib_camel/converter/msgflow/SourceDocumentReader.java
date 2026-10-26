package ru.petrovov.iib_camel.converter.msgflow;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.Locator;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;
import ru.petrovov.iib_camel.converter.analysis.model.SourceRef;

import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.parsers.SAXParserFactory;
import java.io.InputStream;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;

final class SourceDocumentReader {
    private static final String SOURCE_KEY = SourceDocumentReader.class.getName() + ".source";

    private SourceDocumentReader() {
    }

    static Document read(InputStream input, String sourceFile) throws Exception {
        DocumentBuilderFactory documentFactory = DocumentBuilderFactory.newInstance();
        documentFactory.setNamespaceAware(true);
        Document document = documentFactory.newDocumentBuilder().newDocument();

        SAXParserFactory saxFactory = SAXParserFactory.newInstance();
        saxFactory.setNamespaceAware(true);
        saxFactory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        saxFactory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        saxFactory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        saxFactory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        saxFactory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);

        var reader = saxFactory.newSAXParser().getXMLReader();
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        reader.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        reader.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("внешние XML-ресурсы запрещены");
        });
        DomHandler handler = new DomHandler(document, sourceFile);
        reader.setContentHandler(handler);
        reader.setErrorHandler(handler);
        reader.parse(new InputSource(input));
        return document;
    }

    static SourceRef source(Element element, String sourceFile) {
        Object source = element.getUserData(SOURCE_KEY);
        return source instanceof SourceRef ref ? ref : SourceRef.file(sourceFile);
    }

    private static final class DomHandler extends DefaultHandler {
        private final Document document;
        private final String sourceFile;
        private final Deque<Element> elements = new ArrayDeque<>();
        private final Map<String, String> namespaces = new LinkedHashMap<>();
        private Locator locator;

        private DomHandler(Document document, String sourceFile) {
            this.document = document;
            this.sourceFile = sourceFile;
        }

        @Override
        public void setDocumentLocator(Locator locator) {
            this.locator = locator;
        }

        @Override
        public void startPrefixMapping(String prefix, String uri) {
            namespaces.put(prefix, uri);
        }

        @Override
        public void startElement(String uri, String localName, String qName, Attributes attributes) {
            Element element = document.createElementNS(emptyToNull(uri), qName);
            namespaces.forEach((prefix, namespace) -> element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI,
                    prefix.isEmpty() ? XMLConstants.XMLNS_ATTRIBUTE : XMLConstants.XMLNS_ATTRIBUTE + ":" + prefix,
                    namespace));
            namespaces.clear();

            for (int i = 0; i < attributes.getLength(); i++) {
                element.setAttributeNS(emptyToNull(attributes.getURI(i)), attributes.getQName(i), attributes.getValue(i));
            }
            element.setUserData(SOURCE_KEY, new SourceRef(sourceFile,
                    locator == null ? -1 : locator.getLineNumber(),
                    locator == null ? -1 : locator.getColumnNumber()), null);

            if (elements.isEmpty()) {
                document.appendChild(element);
            } else {
                elements.peek().appendChild(element);
            }
            elements.push(element);
        }

        @Override
        public void endElement(String uri, String localName, String qName) {
            elements.pop();
        }

        @Override
        public void characters(char[] chars, int start, int length) {
            if (!elements.isEmpty()) {
                elements.peek().appendChild(document.createTextNode(new String(chars, start, length)));
            }
        }

        private static String emptyToNull(String value) {
            return value.isEmpty() ? null : value;
        }
    }
}
