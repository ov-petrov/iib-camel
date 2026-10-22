package com.example.iib_camel.runtime.generated;

// Сгенерировано конвертером iib-camel (FR-3.2, ESQL → Java); оригинал:
// esql://routine/check#Check_Compute1.Main

import com.example.iib_camel.runtime.compute.EsqlRuntimeSupport;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

/**
 * Скомпилированный аналог Compute-модуля Check_Compute1 (сгенерировано, FR-3.2).
 * Вызов из маршрута: bean:checkCompute1.
 */
@Slf4j
@Component("checkCompute1")
public class CheckCompute1 {

    private final EsqlRuntimeSupport esql = new EsqlRuntimeSupport();

    /** Аналог Main(): перенос и правка сообщения; молчаливый возврат — успех. */
    public void compute(Exchange exchange) {
        // SET OutputRoot = InputRoot;
        esql.copy(exchange);
        // SET OutputRoot.MQMD.CommentField = 'CHECKED-BY-SUBFLOW';
        esql.mqmd(exchange, "CommentField", "CHECKED-BY-SUBFLOW");
    }
}
