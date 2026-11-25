package ru.petrovov.iib_camel.runtime_lite.xsd;

import org.apache.xmlbeans.SchemaGlobalElement;
import org.apache.xmlbeans.SchemaType;
import org.apache.xmlbeans.SchemaTypeSystem;
import org.apache.xmlbeans.XmlBeans;
import org.apache.xmlbeans.XmlCursor;
import org.apache.xmlbeans.XmlException;
import org.apache.xmlbeans.XmlObject;
import org.apache.xmlbeans.XmlOptions;
import org.apache.xmlbeans.impl.xsd2inst.SampleXmlUtil;
import org.xml.sax.InputSource;
import org.xml.sax.SAXException;

import javax.xml.XMLConstants;
import javax.xml.namespace.QName;
import java.io.IOException;
import java.io.StringReader;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

final class XmlSampleGenerator {

    private static final String XSD = XMLConstants.W3C_XML_SCHEMA_NS_URI;
    private static final QName CHOICE = new QName(XSD, "choice");
    private static final QName ANNOTATION = new QName(XSD, "annotation");

    private final SchemaTypeSystem schema;

    XmlSampleGenerator(SchemaInputs inputs) throws IOException, XmlException {
        var entry = parseForSample(inputs.entrySource());
        var resolver = inputs.entityResolver();
        var compileOptions = secureOptions().setCompileDownloadUrls(false).setEntityResolver((publicId, systemId) -> {
            var source = resolver.resolveEntity(publicId, systemId);
            if (source == null || source.getByteStream() == null) {
                throw new SAXException("Schema dependency was not prevalidated: " + systemId);
            }
            try {
                var transformed = new InputSource(new StringReader(parseForSample(source).xmlText()));
                transformed.setSystemId(source.getSystemId());
                return transformed;
            } catch (XmlException invalid) {
                throw new SAXException("Cannot prepare schema dependency for example generation: " + systemId, invalid);
            }
        });
        this.schema = XmlBeans.compileXsd(new XmlObject[]{entry}, XmlBeans.getBuiltinTypeSystem(), compileOptions);
    }

    List<QName> roots() {
        return Arrays.stream(schema.globalElements()).filter(element -> !element.isAbstract())
            .map(SchemaGlobalElement::getName)
            .sorted(Comparator.comparing(QName::getNamespaceURI).thenComparing(QName::getLocalPart)).toList();
    }

    String generate(QName root) throws XmlException {
        var element = schema.findElement(root);
        if (element == null || element.isAbstract()) {
            throw new IllegalArgumentException("No concrete global element for sample root " + root);
        }

        String candidate = SampleXmlUtil.createSampleForType(element);
        var sample = schema.parse(candidate, schema.findDocumentType(root), secureOptions().setLoadStripComments());
        improvePatternValues(sample);
        return sample.xmlText(new XmlOptions().setSavePrettyPrint().setSavePrettyPrintIndent(2)) + "\n";
    }

    private XmlObject parseForSample(InputSource source) throws IOException, XmlException {
        var options = secureOptions().setDocumentSourceName(source.getSystemId());
        try (var bytes = source.getByteStream()) {
            var schema = XmlObject.Factory.parse(bytes, options);
            selectFirstAlternatives(schema);
            return schema;
        }
    }

    private void selectFirstAlternatives(XmlObject schema) {
        try (var cursor = schema.newCursor()) {
            while (!cursor.toNextToken().isNone()) {
                if (cursor.isStart()) {
                    if (ANNOTATION.equals(cursor.getName())) {
                        cursor.toEndToken();
                    } else if (CHOICE.equals(cursor.getName())) {
                        keepFirstParticle(cursor);
                    }
                }
            }
        }
    }

    private void keepFirstParticle(XmlCursor cursor) {
        cursor.setName(new QName(XSD, "sequence", cursor.getName().getPrefix()));
        try (var child = cursor.newCursor()) {
            int retained = child.toFirstChild() && ANNOTATION.equals(child.getName()) ? 2 : 1;
            while (child.toCursor(cursor) && child.toChild(retained)) {
                child.removeXml();
            }
        }
    }

    private void improvePatternValues(XmlObject sample) {
        try (var cursor = sample.newCursor()) {
            while (!cursor.toNextToken().isNone()) {
                if (!cursor.isStart() && !cursor.isAttr()) {
                    continue;
                }
                SchemaType type = cursor.getObject().schemaType();
                if (type.isSimpleType() && type.hasPatternFacet()) {
                    String value = cursor.getTextValue();
                    String replacement = patternValue(type, value);
                    if (!value.equals(replacement)) {
                        cursor.setTextValue(replacement);
                    }
                }
            }
        }
    }

    private String patternValue(SchemaType type, String value) {
        if (type.matchPatternFacet(value)) {
            return value;
        }
        int length = value.length();
        for (String alternative : List.of(value.toUpperCase(Locale.ROOT), value.toLowerCase(Locale.ROOT),
            "A".repeat(length), "a".repeat(length), "0".repeat(length), "1".repeat(length))) {
            if (type.matchPatternFacet(alternative)) {
                return alternative;
            }
        }
        return value;
    }

    private XmlOptions secureOptions() {
        return new XmlOptions().setDisallowDocTypeDeclaration(true).setLoadExternalDTD(false);
    }
}
