package com.example.iib_camel.runtime.generated;

// Сгенерировано конвертером iib-camel (FR-3.2, ESQL → Java); оригинал:
// esql://routine/adjust#Adjust_Compute1.Main

import com.example.iib_camel.runtime.compute.EsqlRuntimeSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

/**
 * Скомпилированный аналог Compute-модуля Adjust_Compute1 (сгенерировано, FR-3.2).
 * Вызов из маршрута: bean:adjustCompute1.
 *
 * <p>ВНИМАНИЕ: операторы, помеченные TODO (FR-3.3), требуют
 * ручной доработки — статус в отчёте миграции: «частично».
 */
@Slf4j
@Component("adjustCompute1")
public class AdjustCompute1 {

    private final EsqlRuntimeSupport esql = new EsqlRuntimeSupport();

    /** Аналог Main(): перенос и правка сообщения; молчаливый возврат — успех. */
    public void compute(Exchange exchange) {
        // SET OutputRoot = InputRoot;
        esql.copy(exchange);
        // SET OutputRoot.MQMD.CommentField = 'ADJUSTED';
        esql.mqmd(exchange, "CommentField", "ADJUSTED");
        // SET OutputRoot.XMLNSC.Order.Status = 'ADJUSTED';
        esql.setBodyField(exchange, "Order.Status", "ADJUSTED");
        // SET OutputRoot.XMLNSC.Order.Rating = CAST('100' AS DECIMAL);
        esql.setBodyField(exchange, "Order.Rating", "100");
        // TODO (FR-3.3): IF InputBody.Order.Status = 'HOLD' THEN SET OutputRoot.XMLNSC.Order.Status = 'PAUSED';
    }
}
