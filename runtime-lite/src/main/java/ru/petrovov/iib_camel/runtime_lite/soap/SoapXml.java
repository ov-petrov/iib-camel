package ru.petrovov.iib_camel.runtime_lite.soap;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.Attributes;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.XMLFilterImpl;

import javax.xml.XMLConstants;
import javax.xml.parsers.ParserConfigurationException;
import javax.xml.parsers.SAXParserFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerException;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMResult;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.sax.SAXTransformerFactory;
import javax.xml.transform.stream.StreamResult;
import java.io.IOException;
import java.io.StringReader;
import java.io.StringWriter;

final class SoapXml {

    private final int maxCharacters;
    private final int maxDepth;
    private final int maxElements;

    SoapXml(int maxCharacters, int maxDepth, int maxElements) {
        if (maxCharacters < 1 || maxDepth < 1 || maxElements < 1) {
            throw new IllegalArgumentException("SOAP limits must be positive");
        }
        this.maxCharacters = maxCharacters;
        this.maxDepth = maxDepth;
        this.maxElements = maxElements;
    }

    Document parse(String text, int enclosingElements) {
        if (text == null || text.isEmpty() || text.length() > maxCharacters) {
            throw new SoapProcessingException("Missing or oversized SOAP XML");
        }
        try {
            var factory = SAXParserFactory.newDefaultNSInstance();
            factory.setXIncludeAware(false);
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            var parser = factory.newSAXParser();
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            parser.setProperty(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
            parser.setProperty("jdk.xml.maxElementDepth", maxDepth);

            var reader = parser.getXMLReader();
            var result = new DOMResult();
            var handler = transformerFactory().newTransformerHandler();
            handler.setResult(result);
            var filter = new XMLFilterImpl(reader) {
                int elements = enclosingElements;
                int depth = enclosingElements;

                @Override
                public void startElement(String uri, String localName, String name, Attributes attributes)
                    throws SAXException {
                    if (++elements > maxElements || ++depth > maxDepth) {
                        throw new SoapProcessingException("SOAP XML exceeds the element or depth limit");
                    }
                    super.startElement(uri, localName, name, attributes);
                }

                @Override
                public void endElement(String uri, String localName, String name) throws SAXException {
                    super.endElement(uri, localName, name);
                    depth--;
                }

                @Override
                public void processingInstruction(String target, String data) {
                    throw new SoapProcessingException("Processing instructions are forbidden in SOAP XML");
                }
            };
            filter.setContentHandler(handler);
            reader.setProperty("http://xml.org/sax/properties/lexical-handler", handler);
            filter.parse(new InputSource(new StringReader(text)));
            return (Document) result.getNode();
        } catch (ParserConfigurationException | SAXException | IOException | TransformerException exception) {
            throw new SoapProcessingException("Cannot parse SOAP XML");
        }
    }

    String serialize(Node node) {
        try {
            var transformer = transformerFactory().newTransformer();
            transformer.setOutputProperty(OutputKeys.METHOD, "xml");
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            var output = new StringWriter();
            transformer.transform(new DOMSource(node), new StreamResult(output));
            if (output.getBuffer().length() > maxCharacters) {
                throw new SoapProcessingException("Serialized SOAP XML exceeds the character limit");
            }
            return output.toString();
        } catch (TransformerException exception) {
            throw new SoapProcessingException("Cannot serialize SOAP XML");
        }
    }

    static void declareInheritedNamespaces(Element element) {
        for (Node node = element.getParentNode(); node instanceof Element ancestor; node = node.getParentNode()) {
            var attributes = ancestor.getAttributes();
            for (int index = 0; index < attributes.getLength(); index++) {
                var attribute = attributes.item(index);
                if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())
                    && !element.hasAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getLocalName())) {
                    element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, attribute.getNodeName(),
                        attribute.getNodeValue());
                }
            }
        }
    }

    private static SAXTransformerFactory transformerFactory() throws TransformerException {
        var factory = (SAXTransformerFactory) TransformerFactory.newDefaultInstance();
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
        return factory;
    }
}
