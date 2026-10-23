package ru.petrovov.iib_camel.runtime.model;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class Adapter {

    private static final XmlMapper XML = new XmlMapper();

    @JacksonXmlProperty(localName = "Order")
    private Order order;

    @Override
    public String toString() {
        try {
            return XML.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("не удалось отрендерить XML-тело Adapter", e);
        }
    }
}
