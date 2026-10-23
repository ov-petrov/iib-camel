package ru.petrovov.iib_camel.runtime.mapping;

import java.beans.Introspector;
import java.beans.PropertyDescriptor;
import java.io.StringReader;
import java.io.StringWriter;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import javax.xml.transform.OutputKeys;
import javax.xml.transform.TransformerFactory;
import javax.xml.transform.dom.DOMSource;
import javax.xml.transform.stream.StreamResult;
import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.Node;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;
import org.xml.sax.SAXParseException;
import org.xml.sax.helpers.DefaultHandler;

public final class XmlBeanMapping {
    private XmlBeanMapping() {}

    public record Model(String xmlName, String namespace, Class<?> javaType, List<Field> fields) {
        public Model { fields = List.copyOf(fields); }
    }

    public record Field(String xmlName, String namespace, String property, Class<?> javaType,
                        boolean required, boolean nillable, Model nested) {}

    public static Object read(String xml, Model model) {
        try {
            var builder = documentFactory().newDocumentBuilder();
            builder.setErrorHandler(new DefaultHandler() {
                @Override public void error(SAXParseException ex) throws SAXException { throw ex; }
                @Override public void fatalError(SAXParseException ex) throws SAXException { throw ex; }
            });
            Element root = builder.parse(new InputSource(new StringReader(xml))).getDocumentElement();
            checkAttributes(root, false);
            return readElement(root, model);
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot read Mapping XML: " + ex.getMessage(), ex);
        }
    }

    public static String write(Object bean, Model model) {
        if (bean == null) throw new IllegalArgumentException("Mapping output root must not be null");
        try {
            Document document = documentFactory().newDocumentBuilder().newDocument();
            Element root = createElement(document, model.xmlName(), model.namespace(), "");
            document.appendChild(root);
            writeElement(bean, root, model);
            TransformerFactory factory = TransformerFactory.newInstance();
            factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
            factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_STYLESHEET, "");
            var transformer = factory.newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter output = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(output));
            return output.toString();
        } catch (IllegalArgumentException ex) {
            throw ex;
        } catch (Exception ex) {
            throw new IllegalArgumentException("Cannot write Mapping XML: " + ex.getMessage(), ex);
        }
    }

    private static Object readElement(Element element, Model model) throws Exception {
        require(matches(element, model.xmlName(), model.namespace()), "Unexpected XML element " + element.getTagName()
                + "; expected {" + model.namespace() + "}" + model.xmlName());
        Object bean = model.javaType().getConstructor().newInstance();
        Set<Field> seen = new HashSet<>();
        int previous = -1;
        for (Node node = element.getFirstChild(); node != null; node = node.getNextSibling()) {
            if (!(node instanceof Element child)) {
                require(node.getNodeType() == Node.COMMENT_NODE || node.getNodeType() == Node.PROCESSING_INSTRUCTION_NODE
                        || node.getTextContent().isBlank(), "Mixed XML content unsupported in " + model.xmlName());
                continue;
            }
            Field field = model.fields().stream().filter(f -> matches(child, f.xmlName(), f.namespace())).findFirst()
                    .orElseThrow(() -> new IllegalArgumentException("Unknown XML field {" + namespace(child) + "}" + child.getLocalName()));
            require(seen.add(field), "Repeated XML field unsupported: " + field.xmlName());
            int index = model.fields().indexOf(field);
            require(index >= previous, "XML fields violate XSD sequence order at " + field.xmlName());
            previous = index;
            boolean nil = checkAttributes(child, field.nillable());
            Object value;
            if (nil) {
                require(!child.hasChildNodes(), "xsi:nil field must be empty: " + field.xmlName());
                value = null;
            } else if (field.nested() != null) value = readElement(child, field.nested());
            else {
                for (Node nested = child.getFirstChild(); nested != null; nested = nested.getNextSibling()) {
                    require(!(nested instanceof Element), "Scalar XML field required: " + field.xmlName());
                }
                value = scalar(child.getTextContent(), field.javaType());
            }
            PropertyDescriptor property = property(model.javaType(), field.property());
            property.getWriteMethod().invoke(bean, value);
        }
        for (Field field : model.fields()) require(!field.required() || seen.contains(field), "Missing required XML field " + field.xmlName());
        return bean;
    }

    private static void writeElement(Object bean, Element element, Model model) throws Exception {
        require(model.javaType().isInstance(bean), "Unexpected Mapping Java model type");
        for (Field field : model.fields()) {
            Object value = property(model.javaType(), field.property()).getReadMethod().invoke(bean);
            if (value == null && !field.required()) continue;
            require(value != null || field.nillable(), "Mapping did not produce required XML field " + field.xmlName());
            Element child = createElement(element.getOwnerDocument(), field.xmlName(), field.namespace(), namespace(element));
            element.appendChild(child);
            if (value == null) {
                child.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns:xsi", XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI);
                child.setAttributeNS(XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI, "xsi:nil", "true");
            } else if (field.nested() != null) writeElement(value, child, field.nested());
            else {
                require(field.javaType().isInstance(value), "Unexpected Mapping scalar type for " + field.xmlName());
                child.setTextContent(text(value));
            }
        }
    }

    private static PropertyDescriptor property(Class<?> type, String name) throws Exception {
        for (PropertyDescriptor property : Introspector.getBeanInfo(type).getPropertyDescriptors()) {
            if (property.getName().equals(name) && property.getReadMethod() != null && property.getWriteMethod() != null) return property;
        }
        throw new IllegalArgumentException("Missing JavaBean property " + type.getName() + "." + name);
    }

    private static Object scalar(String text, Class<?> type) {
        if (type == String.class) return text;
        String value = text.trim();
        if (type == Boolean.class) {
            require(Set.of("true", "false", "1", "0").contains(value), "Invalid XSD boolean: " + value);
            return value.equals("true") || value.equals("1");
        }
        if (type == BigDecimal.class) {
            require(value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)"), "Invalid XSD decimal: " + value);
            return new BigDecimal(value);
        }
        if (type == Double.class || type == Float.class) {
            require(value.matches("[+-]?(?:[0-9]+(?:\\.[0-9]*)?|\\.[0-9]+)(?:[eE][+-]?[0-9]+)?")
                    || Set.of("INF", "-INF", "NaN").contains(value), "Invalid XSD floating point value: " + value);
            String javaValue = value.equals("INF") ? "Infinity" : value.equals("-INF") ? "-Infinity" : value;
            if (type == Double.class) return Double.valueOf(javaValue);
            return Float.valueOf(javaValue);
        }
        require(value.matches("[+-]?[0-9]+"), "Invalid XSD integer: " + value);
        if (type == BigInteger.class) return new BigInteger(value);
        if (type == Byte.class) return Byte.valueOf(value);
        if (type == Short.class) return Short.valueOf(value);
        if (type == Integer.class) return Integer.valueOf(value);
        if (type == Long.class) return Long.valueOf(value);
        throw new IllegalArgumentException("Unsupported XML scalar Java type " + type.getName());
    }

    private static String text(Object value) {
        if (value instanceof BigDecimal decimal) return decimal.toPlainString();
        if (value instanceof Double number && number.isInfinite()) return number > 0 ? "INF" : "-INF";
        if (value instanceof Float number && number.isInfinite()) return number > 0 ? "INF" : "-INF";
        return value.toString();
    }

    private static boolean checkAttributes(Element element, boolean nillable) {
        boolean nil = false;
        var attributes = element.getAttributes();
        for (int i = 0; i < attributes.getLength(); i++) {
            Node attribute = attributes.item(i);
            if (XMLConstants.XMLNS_ATTRIBUTE_NS_URI.equals(attribute.getNamespaceURI())) continue;
            require(nillable && XMLConstants.W3C_XML_SCHEMA_INSTANCE_NS_URI.equals(attribute.getNamespaceURI())
                    && attribute.getLocalName().equals("nil"), "Unsupported XML attribute " + attribute.getNodeName());
            require(Set.of("true", "false", "0", "1").contains(attribute.getNodeValue()), "Invalid xsi:nil");
            nil = attribute.getNodeValue().equals("true") || attribute.getNodeValue().equals("1");
        }
        return nil;
    }

    private static Element createElement(Document document, String name, String namespace, String parentNamespace) {
        Element element = document.createElementNS(namespace.isEmpty() ? null : namespace, name);
        if (!namespace.equals(parentNamespace)) element.setAttributeNS(XMLConstants.XMLNS_ATTRIBUTE_NS_URI, "xmlns", namespace);
        return element;
    }

    private static boolean matches(Element element, String name, String expectedNamespace) {
        return name.equals(element.getLocalName()) && expectedNamespace.equals(namespace(element));
    }

    private static String namespace(Element element) {
        return element.getNamespaceURI() == null ? "" : element.getNamespaceURI();
    }

    private static DocumentBuilderFactory documentFactory() throws Exception {
        DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
        factory.setNamespaceAware(true);
        factory.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
        factory.setFeature("http://xml.org/sax/features/external-general-entities", false);
        factory.setFeature("http://xml.org/sax/features/external-parameter-entities", false);
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA, "");
        return factory;
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw new IllegalArgumentException(message);
    }
}
