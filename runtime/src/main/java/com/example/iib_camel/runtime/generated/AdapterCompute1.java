package com.example.iib_camel.runtime.generated;

// Источник: esql://routine/adapter#Adapter_Compute1.Main

import com.example.iib_camel.runtime.compute.AbstractComputeBean;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

@Component("adapterCompute1")
public class AdapterCompute1 extends AbstractComputeBean {

    @Override
    protected void main(Exchange exchange) {
        // SET OutputRoot = InputRoot;
        copy(exchange);
        // SET OutputRoot.MQMD.ApplicationIdData = 'IIB-CAMEL-MIG';
        mqmd(exchange, "ApplicationIdData", "IIB-CAMEL-MIG");
    }
}
