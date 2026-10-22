package ru.petrovov.iib_camel.runtime.generated;

// Источник: esql://routine/check#Check_Compute1.Main

import ru.petrovov.iib_camel.runtime.compute.AbstractComputeBean;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

@Component("checkCompute1")
public class CheckCompute1 extends AbstractComputeBean {

    @Override
    protected void main(Exchange exchange) {
        // SET OutputRoot = InputRoot;
        copy(exchange);
        // SET OutputRoot.MQMD.CommentField = 'CHECKED-BY-SUBFLOW';
        mqmd(exchange, "CommentField", "CHECKED-BY-SUBFLOW");
    }
}
