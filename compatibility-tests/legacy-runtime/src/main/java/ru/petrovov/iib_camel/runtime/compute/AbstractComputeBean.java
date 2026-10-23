package ru.petrovov.iib_camel.runtime.compute;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.HashMap;
import java.util.Map;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.Transformer;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.apache.camel.Handler;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.helpers.DefaultHandler;

/** Shared operations for generated Compute beans; XML paths include the document root. */
@Slf4j
public abstract class AbstractComputeBean {

    private static final String INPUT_SNAPSHOT = AbstractComputeBean.class.getName() + ".inputSnapshot";

    @Handler
    public final void compute(Exchange exchange) {
        Object previousSnapshot = exchange.getProperty(INPUT_SNAPSHOT);
        exchange.setProperty(INPUT_SNAPSHOT, new InputSnapshot(exchange.getIn().getBody(),
                new HashMap<>(exchange.getIn().getHeaders())));
        exchange.getIn().setBody(null);
        exchange.getIn().setHeaders(new HashMap<>());
        try {
            main(exchange);
        } catch (RuntimeException | Error failure) {
            copy(exchange);
            throw failure;
        } finally {
            if (previousSnapshot == null) {
                exchange.removeProperty(INPUT_SNAPSHOT);
            } else {
                exchange.setProperty(INPUT_SNAPSHOT, previousSnapshot);
            }
        }
    }

    protected abstract void main(Exchange exchange);

    /** Restores this invocation's input; mutable payloads and header values are not deep-copied. */
    protected void copy(Exchange exchange) {
        InputSnapshot snapshot = exchange.getProperty(INPUT_SNAPSHOT, InputSnapshot.class);
        if (snapshot == null) {
            throw new IllegalStateException("copy must be called within compute");
        }
        exchange.getIn().setBody(snapshot.body());
        exchange.getIn().setHeaders(new HashMap<>(snapshot.headers()));
    }

    protected void mqmd(Exchange exchange, String field, Object value) {
        if (value == null) exchange.getIn().removeHeader(field);
        else exchange.getIn().setHeader(field, value);
        log.debug("MQMD.{} = {}", field, value);
    }

    protected void setBodyField(Exchange exchange, String path, Object value) {
        try {
            String[] segments = xmlPath(path);
            String body = exchange.getIn().getBody(String.class);
            if (body == null && exchange.getProperty(INPUT_SNAPSHOT) == null) {
                throw new IllegalStateException("тело пустое — XML-дерева нет");
            }
            if (body == null && value == null) return;
            Document document = body == null ? parseXml("<" + segments[0] + "/>") : parseXml(body);
            rejectNamespaces(document);
            Element current = document.getDocumentElement();
            if (!current.getTagName().equals(segments[0])) {
                throw new IllegalArgumentException("XML root " + current.getTagName()
                        + " does not match path root " + segments[0]);
            }
            for (int i = 1; i < segments.length; i++) {
                Element child = directChild(current, segments[i]);
                if (child == null) {
                    if (value == null) return;
                    child = document.createElement(segments[i]);
                    current.appendChild(child);
                }
                current = child;
            }
            if (value == null) {
                if (current == document.getDocumentElement()) {
                    exchange.getIn().setBody(null);
                    return;
                }
                current.getParentNode().removeChild(current);
            } else current.setTextContent(EsqlValues.text(value));
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            Transformer transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            exchange.getIn().setBody(out.toString(), String.class);
        } catch (Exception e) {
            throw new IllegalStateException("setBodyField(" + path + "): " + e.getMessage(), e);
        }
    }

    protected Object bodyField(Exchange exchange, boolean input, String path) {
        try {
            String[] segments = xmlPath(path);
            Object body = input ? snapshot(exchange).body() : exchange.getIn().getBody();
            if (body == null) return null;
            if (!(body instanceof String xml)) throw new IllegalArgumentException("XML body must be a String");
            Document document = parseXml(xml);
            rejectNamespaces(document);
            Element current = document.getDocumentElement();
            if (!current.getTagName().equals(segments[0])) return null;
            for (int i = 1; i < segments.length; i++) {
                current = directChild(current, segments[i]);
                if (current == null) return null;
            }
            for (var child = current.getFirstChild(); child != null; child = child.getNextSibling()) {
                if (child instanceof Element) throw new IllegalArgumentException("Scalar XML field required: " + path);
            }
            return current.getTextContent();
        } catch (Exception e) {
            throw new IllegalStateException("bodyField(" + path + "): " + e.getMessage(), e);
        }
    }

    protected Object mqmdField(Exchange exchange, boolean input, String field) {
        Object value = input ? snapshot(exchange).headers().get(field) : exchange.getIn().getHeader(field);
        return value == null ? null : EsqlValues.text(value);
    }

    private static InputSnapshot snapshot(Exchange exchange) {
        InputSnapshot snapshot = exchange.getProperty(INPUT_SNAPSHOT, InputSnapshot.class);
        if (snapshot == null) throw new IllegalStateException("InputRoot access must be within compute");
        return snapshot;
    }

    protected static Object esqlCast(Object value, String type) { return EsqlValues.cast(value, type); }
    protected static Boolean esqlCompare(Object left, Object right, String operator) { return EsqlValues.compare(left, right, operator); }
    protected static Boolean esqlAnd(Object left, java.util.function.Supplier<?> right) { return EsqlValues.andLazy(left, right); }
    protected static Boolean esqlOr(Object left, java.util.function.Supplier<?> right) { return EsqlValues.orLazy(left, right); }
    protected static Boolean esqlNot(Object value) { return EsqlValues.not(value); }
    protected static boolean esqlTrue(Object value) { return EsqlValues.truth(value); }

    private static String[] xmlPath(String path) {
        if (path == null || !path.matches("[A-Za-z_][A-Za-z0-9_-]*(\\.[A-Za-z_][A-Za-z0-9_-]*)*")) {
            throw new IllegalArgumentException("Unsupported XML path: " + path);
        }
        return path.split("\\.");
    }

    private static Document parseXml(String body) throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setFeature("http://apache.org/xml/features/nonvalidating/load-external-dtd", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        factory.setXIncludeAware(false);
        factory.setExpandEntityReferences(false);
        var builder = factory.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> {
            throw new SAXException("External XML resources are not supported");
        });
        builder.setErrorHandler(new DefaultHandler());
        return builder.parse(new InputSource(new StringReader(body)));
    }

    private static void rejectNamespaces(Document document) {
        var elements = document.getElementsByTagName("*");
        for (int i = 0; i < elements.getLength(); i++) {
            Element element = (Element) elements.item(i);
            if (element.getNamespaceURI() != null) {
                throw new IllegalArgumentException("XML namespaces are not supported");
            }
            var attributes = element.getAttributes();
            for (int j = 0; j < attributes.getLength(); j++) {
                if (attributes.item(j).getNamespaceURI() != null) {
                    throw new IllegalArgumentException("XML namespaces are not supported");
                }
            }
        }
    }

    private static Element directChild(Element parent, String name) {
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element element && element.getTagName().equals(name)) {
                return element;
            }
        }
        return null;
    }

    private record InputSnapshot(Object body, Map<String, Object> headers) {
    }
}
