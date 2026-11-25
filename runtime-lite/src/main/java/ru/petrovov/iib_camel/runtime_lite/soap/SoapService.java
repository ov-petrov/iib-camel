package ru.petrovov.iib_camel.runtime_lite.soap;

import jakarta.xml.soap.MessageFactory;
import jakarta.xml.soap.SOAPConstants;
import jakarta.xml.soap.SOAPException;
import jakarta.xml.soap.SOAPMessage;
import org.w3c.dom.DOMException;
import org.w3c.dom.Element;
import org.w3c.dom.Node;

import javax.xml.namespace.QName;
import javax.xml.transform.dom.DOMSource;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class SoapService {

    private static final String SOAP_11 = SOAPConstants.URI_NS_SOAP_1_1_ENVELOPE;
    private static final String SOAP_12 = SOAPConstants.URI_NS_SOAP_1_2_ENVELOPE;
    private static final String ENVELOPE = "Envelope";
    private static final String HEADER = "Header";
    private static final String BODY = "Body";
    private static final String FAULT = "Fault";
    private static final int WRAPPER_ELEMENTS = 2;

    private final SoapXml xml;
    private final MessageFactory messageFactory;

    public SoapService(int maxCharacters, int maxDepth, int maxElements) {
        xml = new SoapXml(maxCharacters, maxDepth, maxElements);
        try {
            messageFactory = MessageFactory.newInstance(SOAPConstants.SOAP_1_1_PROTOCOL);
            var envelope = messageFactory.createMessage().getSOAPPart().getEnvelope();
            if (!SOAP_11.equals(envelope.getNamespaceURI())) {
                throw new IllegalStateException("SAAJ provider did not create a SOAP 1.1 Envelope");
            }
        } catch (SOAPException exception) {
            throw new IllegalStateException("Cannot initialize the SOAP 1.1 provider", exception);
        }
    }

    public String unwrap(String envelope) {
        var document = xml.parse(envelope, 0);
        Element root = document.getDocumentElement();
        if (!named(root, SOAP_11, ENVELOPE)) {
            throw new SoapProcessingException("Expected a SOAP 1.1 Envelope");
        }
        List<Element> children = children(root);
        if (children.size() == 2 && named(children.getFirst(), SOAP_11, HEADER)) {
            checkHeader(children.removeFirst());
        }
        if (children.size() != 1 || !named(children.getFirst(), SOAP_11, BODY)) {
            throw new SoapProcessingException("Expected one SOAP Body following the optional Header");
        }
        Element body = children.getFirst();
        checkLiteralEncoding(root);
        checkLiteralEncoding(body);
        Element payload = onlyBodyElement(body);
        checkDocumentContent(payload);
        if (named(payload, SOAP_11, FAULT)) {
            throw fault(payload);
        }
        checkBusinessRoot(payload);

        try {
            var part = newMessage().getSOAPPart();
            part.setContent(new DOMSource(document));
            Element businessElement = onlyBodyElement(part.getEnvelope().getBody());
            SoapXml.declareInheritedNamespaces(businessElement);
            return xml.serialize(businessElement);
        } catch (SOAPException | DOMException exception) {
            throw new SoapProcessingException("Cannot read SOAP 1.1 Body");
        }
    }

    public String wrap(String payload) {
        var document = xml.parse(payload, WRAPPER_ELEMENTS);
        checkBusinessRoot(document.getDocumentElement());
        checkDocumentContent(document.getDocumentElement());
        try {
            var envelope = newMessage().getSOAPPart().getEnvelope();
            var header = envelope.getHeader();
            if (header != null) {
                header.detachNode();
            }
            envelope.getBody().addDocument(document);
            return xml.serialize(envelope);
        } catch (SOAPException | DOMException exception) {
            throw new SoapProcessingException("Cannot create SOAP 1.1 Envelope");
        }
    }

    private synchronized SOAPMessage newMessage() {
        try {
            return messageFactory.createMessage();
        } catch (SOAPException exception) {
            throw new IllegalStateException("SOAP 1.1 provider could not create a message", exception);
        }
    }

    private static void checkHeader(Element header) {
        for (Element entry : children(header)) {
            if (entry.getNamespaceURI() == null) {
                throw new SoapProcessingException("SOAP header entries must be namespace-qualified");
            }
            if (!entry.hasAttributeNS(SOAP_11, "mustUnderstand")) {
                continue;
            }
            String value = entry.getAttributeNS(SOAP_11, "mustUnderstand");
            if (!"0".equals(value) && !"1".equals(value)) {
                throw new SoapProcessingException("Invalid SOAP 1.1 mustUnderstand value");
            }
            String actor = entry.getAttributeNS(SOAP_11, "actor").trim();
            if ("1".equals(value) && (actor.isEmpty() || SOAPConstants.URI_SOAP_ACTOR_NEXT.equals(actor))) {
                throw new SoapProcessingException("Unsupported mandatory SOAP header");
            }
        }
    }

    private static Element onlyBodyElement(Element body) {
        List<Element> elements = children(body);
        if (elements.size() != 1) {
            throw new SoapProcessingException("SOAP Body must contain exactly one element");
        }
        return elements.getFirst();
    }

    private static void checkBusinessRoot(Element root) {
        if (soapNamespace(root)) {
            throw new SoapProcessingException("Expected a business document in the SOAP Body");
        }
    }

    private static boolean soapNamespace(Element element) {
        String namespace = element.getNamespaceURI();
        return SOAP_11.equals(namespace) || SOAP_12.equals(namespace);
    }

    private static void checkDocumentContent(Element root) {
        checkLiteralEncoding(root);
        var descendants = root.getElementsByTagName("*");
        int length = descendants.getLength();
        for (int index = 0; index < length; index++) {
            var element = (Element) descendants.item(index);
            if (ENVELOPE.equals(element.getLocalName()) && soapNamespace(element)) {
                throw new SoapProcessingException("Nested SOAP envelopes are forbidden");
            }
            checkLiteralEncoding(element);
        }
    }

    private static void checkLiteralEncoding(Element element) {
        if (!element.getAttributeNS(SOAP_11, "encodingStyle").isBlank()) {
            throw new SoapProcessingException("SOAP encoding is unsupported for document/literal payloads");
        }
    }

    private static SoapFaultException fault(Element fault) {
        List<Element> fields = children(fault);
        if (fields.size() < 2 || !named(fields.getFirst(), null, "faultcode")
            || !named(fields.get(1), null, "faultstring")) {
            throw new SoapProcessingException("Invalid SOAP 1.1 Fault");
        }
        Element code = fields.removeFirst();
        requireSimpleContent(code);
        requireSimpleContent(fields.removeFirst());
        if (!fields.isEmpty() && named(fields.getFirst(), null, "faultactor")) {
            requireSimpleContent(fields.removeFirst());
        }
        if (!fields.isEmpty() && named(fields.getFirst(), null, "detail")) {
            fields.removeFirst();
        }
        if (!fields.isEmpty()) {
            throw new SoapProcessingException("Invalid SOAP 1.1 Fault fields");
        }
        return new SoapFaultException(faultCode(code));
    }

    private static QName faultCode(Element code) {
        String value = code.getTextContent().trim();
        int colon = value.indexOf(':');
        String prefix = colon < 0 ? null : value.substring(0, colon);
        String namespace = code.lookupNamespaceURI(prefix);
        try {
            Element name = code.getOwnerDocument().createElementNS(namespace, value);
            return new QName(namespace, name.getLocalName(), prefix == null ? "" : prefix);
        } catch (DOMException exception) {
            throw new SoapProcessingException("Invalid SOAP Fault code");
        }
    }

    private static void requireSimpleContent(Element field) {
        if (field.getElementsByTagName("*").getLength() != 0) {
            throw new SoapProcessingException("Expected text in SOAP Fault field");
        }
    }

    private static List<Element> children(Element parent) {
        List<Element> elements = new ArrayList<>();
        for (Node node = parent.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (node instanceof Element element) {
                elements.add(element);
            } else if ((node.getNodeType() == Node.TEXT_NODE || node.getNodeType() == Node.CDATA_SECTION_NODE)
                && !node.getNodeValue().chars().allMatch(SoapService::xmlWhitespace)) {
                throw new SoapProcessingException("Unexpected text in SOAP structure");
            }
        }
        return elements;
    }

    private static boolean xmlWhitespace(int character) {
        return character == ' ' || character == '\t' || character == '\r' || character == '\n';
    }

    private static boolean named(Element element, String namespace, String localName) {
        return Objects.equals(namespace, element.getNamespaceURI()) && localName.equals(element.getLocalName());
    }
}
