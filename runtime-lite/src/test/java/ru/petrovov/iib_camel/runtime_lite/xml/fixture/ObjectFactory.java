package ru.petrovov.iib_camel.runtime_lite.xml.fixture;

import jakarta.xml.bind.JAXBElement;
import jakarta.xml.bind.annotation.XmlAccessType;
import jakarta.xml.bind.annotation.XmlAccessorType;
import jakarta.xml.bind.annotation.XmlElementDecl;
import jakarta.xml.bind.annotation.XmlRegistry;
import jakarta.xml.bind.annotation.XmlType;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

import javax.xml.namespace.QName;

@XmlRegistry
public class ObjectFactory {

    private static final String NAMESPACE = "urn:example:document:v1";
    private static final QName DOCUMENT = new QName(NAMESPACE, "document");

    @XmlElementDecl(namespace = NAMESPACE, name = "document")
    public JAXBElement<NamedDocument> createDocument(NamedDocument value) {
        return new JAXBElement<>(DOCUMENT, NamedDocument.class, value);
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @XmlAccessorType(XmlAccessType.FIELD)
    @XmlType(name = "DocumentType", namespace = NAMESPACE)
    public static class NamedDocument {

        private String value;
    }
}
