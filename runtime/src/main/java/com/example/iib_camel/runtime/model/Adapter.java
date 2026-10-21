package com.example.iib_camel.runtime.model;

import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import com.fasterxml.jackson.dataformat.xml.XmlMapper;

/**
 * Корневая модель сообщения (ULM, FR-3.4) — целевой вид после парсинга XML-тела.
 *
 * Обычный POJO с геттерами (не record): simple-выражения вида
 * {@code ${body.order.status}} разрешаются через JavaBean-свойства.
 * Имена XML-элементов задаются явно (регистрозависимость Jackson XML).
 *
 * toString() рендерит канонический XML — он используется camel-jms при отправке
 * TextMessage (IIB-семантика: OutputRoot переносится как XML-сообщение).
 */
public class Adapter {

    private static final XmlMapper XML = new XmlMapper();

    @JacksonXmlProperty(localName = "Order")
    private Order order;

    public Order getOrder() {
        return order;
    }

    public void setOrder(Order order) {
        this.order = order;
    }

    @Override
    public String toString() {
        try {
            return XML.writeValueAsString(this);
        } catch (Exception e) {
            throw new IllegalStateException("не удалось отрендерить XML-тело Adapter", e);
        }
    }
}
