package com.example.iib_camel.runtime.compute;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Юнит-тесты базового класса сгенерированных Compute-бинов (FR-3.2):
 * pass-through copy, MQMD → JMS-свойство, правка XML-дерева, честный сбой на некорректном теле.
 * Методы защищённые (доступны наследникам-бинам), поэтому тест идёт через публиковающий наследника.
 */
class AbstractComputeBeanTest {

    /** Публикует protected-методы для теста из чужого пакета — упаковка имён сохраняет семантику. */
    private static final class TestBean extends AbstractComputeBean {
        @Override
        protected void main(Exchange exchange) {
            // только чтобы класс был конкретным
        }

        void copy_(Exchange exchange) { copy(exchange); }

        void mqmd_(Exchange exchange, String field, String value) { mqmd(exchange, field, value); }

        void setBodyField_(Exchange exchange, String path, String value) {
            setBodyField(exchange, path, value);
        }
    }

    private final TestBean esql = new TestBean();

    private static Exchange exchangeWith(String body) {
        DefaultCamelContext context = new DefaultCamelContext();
        Exchange exchange = new DefaultExchange(context);
        exchange.getIn().setBody(body);
        return exchange;
    }

    @Test
    void copyLeavesBodyUntouched() {
        Exchange exchange = exchangeWith("<Order><Status>NEW</Status></Order>");
        esql.copy_(exchange);
        assertEquals("<Order><Status>NEW</Status></Order>", exchange.getIn().getBody(String.class),
                "pass-through: тело не меняется");
    }

    @Test
    void mqmdSetsMessageHeader() {
        Exchange exchange = exchangeWith("<Order/>");
        esql.mqmd_(exchange, "CommentField", "CHECKED-BY-SUBFLOW");
        assertEquals("CHECKED-BY-SUBFLOW", exchange.getIn().getHeader("CommentField", String.class));
    }

    @Test
    void setBodyFieldPatchesXmlTree() {
        Exchange exchange = exchangeWith("<Adapter><Order><Status>NEW</Status></Order></Adapter>");
        esql.setBodyField_(exchange, "Order.Status", "PAUSED");
        assertEquals("<Adapter><Order><Status>PAUSED</Status></Order></Adapter>",
                exchange.getIn().getBody(String.class),
                "SET OutputRoot.XMLNSC.Order.Status правит вложенное поле");
    }

    @Test
    void setBodyFieldCreatesMissingLevels() {
        Exchange exchange = exchangeWith("<Adapter/>");
        esql.setBodyField_(exchange, "Order.Status", "ACTIVE");
        assertEquals("<Adapter><Order><Status>ACTIVE</Status></Order></Adapter>",
                exchange.getIn().getBody(String.class));
    }

    @Test
    void malformedBodyFailsLoudly() {
        Exchange exchange = exchangeWith("<Adapter><Order>broken");
        assertThrows(IllegalStateException.class,
                () -> esql.setBodyField_(exchange, "Order.Status", "X"),
                "некорректный XML → исключение (маршрутный doCatch ведёт в очередь ошибок)");
    }
}
