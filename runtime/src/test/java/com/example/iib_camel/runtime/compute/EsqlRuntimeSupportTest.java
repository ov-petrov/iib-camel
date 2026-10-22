package com.example.iib_camel.runtime.compute;

import org.apache.camel.Exchange;
import org.apache.camel.impl.DefaultCamelContext;
import org.apache.camel.support.DefaultExchange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Юнит-тесты исполняющей поддержки сгенерированных Compute-бинов (FR-3.2):
 * pass-through copy, MQMD → JMS-свойство, правка XML-дерева, честный сбой на некорректном теле.
 */
class EsqlRuntimeSupportTest {

    private final EsqlRuntimeSupport esql = new EsqlRuntimeSupport();

    private static Exchange exchangeWith(String body) {
        DefaultCamelContext context = new DefaultCamelContext();
        Exchange exchange = new DefaultExchange(context);
        exchange.getIn().setBody(body);
        return exchange;
    }

    @Test
    void copyLeavesBodyUntouched() {
        Exchange exchange = exchangeWith("<Order><Status>NEW</Status></Order>");
        esql.copy(exchange);
        assertEquals("<Order><Status>NEW</Status></Order>", exchange.getIn().getBody(String.class),
                "pass-through: тело не меняется");
    }

    @Test
    void mqmdSetsMessageHeader() {
        Exchange exchange = exchangeWith("<Order/>");
        esql.mqmd(exchange, "CommentField", "CHECKED-BY-SUBFLOW");
        assertEquals("CHECKED-BY-SUBFLOW", exchange.getIn().getHeader("CommentField", String.class));
    }

    @Test
    void setBodyFieldPatchesXmlTree() {
        Exchange exchange = exchangeWith("<Adapter><Order><Status>NEW</Status></Order></Adapter>");
        esql.setBodyField(exchange, "Order.Status", "PAUSED");
        assertEquals("<Adapter><Order><Status>PAUSED</Status></Order></Adapter>",
                exchange.getIn().getBody(String.class),
                "SET OutputRoot.XMLNSC.Order.Status правит вложенное поле");
    }

    @Test
    void setBodyFieldCreatesMissingLevels() {
        Exchange exchange = exchangeWith("<Adapter/>");
        esql.setBodyField(exchange, "Order.Status", "ACTIVE");
        assertEquals("<Adapter><Order><Status>ACTIVE</Status></Order></Adapter>",
                exchange.getIn().getBody(String.class));
    }

    @Test
    void malformedBodyFailsLoudly() {
        Exchange exchange = exchangeWith("<Adapter><Order>broken");
        assertThrows(IllegalStateException.class,
                () -> esql.setBodyField(exchange, "Order.Status", "X"),
                "некорректный XML → исключение (маршрутный doCatch ведёт в очередь ошибок)");
    }
}
