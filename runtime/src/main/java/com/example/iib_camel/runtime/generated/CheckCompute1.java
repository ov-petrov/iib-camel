package com.example.iib_camel.runtime.generated;

// Сгенерировано конвертером iib-camel (FR-3.2, ESQL → Java); оригинал:
// esql://routine/check#Check_Compute1.Main

import com.example.iib_camel.runtime.compute.AbstractComputeBean;
import lombok.extern.slf4j.Slf4j;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

/**
 * Скомпилированный аналог Compute-модуля Check_Compute1 (сгенерировано, FR-3.2).
 * Вызов из маршрута: bean:checkCompute1; поддержка операторов — protected-методы AbstractComputeBean.
 */
@Slf4j
@Component("checkCompute1")
public class CheckCompute1 extends AbstractComputeBean {

    /** Аналог Main(): перенос и правка сообщения; молчаливый возврат — успех. */
    @Override
    protected void main(Exchange exchange) {
        // SET OutputRoot = InputRoot;
        copy(exchange);
        // SET OutputRoot.MQMD.CommentField = 'CHECKED-BY-SUBFLOW';
        mqmd(exchange, "CommentField", "CHECKED-BY-SUBFLOW");
    }
}
