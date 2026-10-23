package ru.petrovov.iib_camel.runtime.model;

import com.fasterxml.jackson.dataformat.xml.XmlMapper;
import com.fasterxml.jackson.dataformat.xml.annotation.JacksonXmlProperty;
import lombok.Getter;
import lombok.Setter;

/**
 * Корневая модель сообщения (ULM, FR-3.4) — целевой вид после парсинга XML-тела.
 *
 * Обычный POJO с геттерами (не record): simple-выражения вида
 * {@code ${body.order.status}} разрешаются через JavaBean-свойства.
 * Имена XML-элементов задаются явно (регистрозависимость Jackson XML).
 *
 * toString() рендерит канонический XML — он используется camel-jms при отправке
 * TextMessage (IIB-семантика: OutputRoot переносится как XML-сообщение),
 * поэтому @ToString не применяется; доступоры генерирует Lombok.
 */
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
