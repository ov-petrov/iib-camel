package com.example.iib_camel.runtime.compute;

import java.io.StringReader;
import java.io.StringWriter;
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

/**
 * Базовый класс сгенерированных Compute-бинов (FR-3.2 → FR-R3.1).
 *
 * Бины, сгенерированные конвертером из ESQL
 * (package {@code com.example.iib_camel.runtime.generated}), наследуют этот класс
 * и используют защищённые методы — семантика ESQL-операторов сосредоточена
 * в одном проверяемом месте:
 *
 *   SET OutputRoot = InputRoot;                      → {@link #copy(Exchange)} —
 *                                                      тело переносится как есть
 *                                                      (QoS переносит camel-jms, preserveMessageQos);
 *   SET OutputRoot.MQMD.'<F>' = '<S>';               → {@link #mqmd(Exchange, String, String)} —
 *                                                      MQMD-поле живёт как JMS-свойство сообщения;
 *   SET OutputRoot.XMLNSC.<Path> = '<S>';            → {@link #setBodyField(Exchange, String, String)} —
 *                                                      правка XML-дерева тела (DOM: имена, порядок
 *                                                      и пробелы узлов сохраняются).
 *
 * Некорректный XML в setBodyField → исключение → маршрутный doCatch → очередь ошибок
 * (поведение как в IIB Compute: сбой преобразования не остаётся незамеченным).
 */
@Slf4j
public abstract class AbstractComputeBean {

    /**
     * Единственная публичная точка вызова маршрута (RouteGenerator ссылается bean:<id> →
     * compute(Exchange)). Финальная и помечена @Handler — Camel выбирает её однозначно,
     * без перебора protected-методов поддержки (AmbiguousMethodCallException).
     */
    @Handler
    public final void compute(Exchange exchange) {
        main(exchange);
    }

    /** Аналог ESQL Main(). Реализуется сгенерированным бином. */
    protected abstract void main(Exchange exchange);

    /** SET OutputRoot = InputRoot — тело проходит без изменений; отдельного кода не нужно. */
    protected void copy(Exchange exchange) {
        // pass-through: изменение не требуется, вызов оставлен для трассируемости сгенерированного кода
    }

    /** SET OutputRoot.MQMD.<field> = value — MQMD-поле как JMS-свойство (напр. ApplicationIdData). */
    protected void mqmd(Exchange exchange, String field, String value) {
        exchange.getIn().setHeader(field, value);
        log.debug("MQMD.{} = {}", field, value);
    }

    /**
     * SET OutputRoot.<domain>.<Path.To.Field> = value — правка XML-тела
     * (путь задаётся без корневого элемента: «Order.Status» — вложенность под корнем).
     * DOM-правка: имена и порядок узлов сохраняются, недостающие уровни создаются.
     */
    protected void setBodyField(Exchange exchange, String path, String value) {
        try {
            String body = exchange.getIn().getBody(String.class);
            if (body == null || body.isBlank()) {
                throw new IllegalStateException("тело пустое — XML-дерева нет");
            }
            DocumentBuilderFactory factory = DocumentBuilderFactory.newInstance();
            factory.setExpandEntityReferences(false);
            Document document = factory.newDocumentBuilder()
                    .parse(new org.xml.sax.InputSource(new StringReader(body)));
            Element current = document.getDocumentElement();
            String[] segments = path.split("\\.");
            for (int i = 0; i < segments.length - 1; i++) {
                Element child = directChild(current, segments[i]);
                if (child == null) {
                    child = document.createElement(segments[i]);
                    current.appendChild(child);
                }
                current = child;
            }
            String leaf = segments[segments.length - 1];
            Element field = directChild(current, leaf);
            if (field == null) {
                field = document.createElement(leaf);
                current.appendChild(field);
            }
            field.setTextContent(value);
            document.normalize();
            Transformer transformer = TransformerFactory.newInstance().newTransformer();
            transformer.setOutputProperty(OutputKeys.OMIT_XML_DECLARATION, "yes");
            StringWriter out = new StringWriter();
            transformer.transform(new DOMSource(document), new StreamResult(out));
            exchange.getIn().setBody(out.toString(), String.class);
        } catch (Exception e) {
            throw new IllegalStateException("setBodyField(" + path + "): " + e.getMessage(), e);
        }
    }

    /** Спуск по прямым детям (без поиска по поддереву) — предсказуемая правка (D-3). */
    private static Element directChild(Element parent, String name) {
        var children = parent.getChildNodes();
        for (int i = 0; i < children.getLength(); i++) {
            if (children.item(i) instanceof Element el && el.getTagName().equals(name)) {
                return el;
            }
        }
        return null;
    }
}
