package ru.petrovov.iib_camel.runtime.generated;


import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;
import ru.petrovov.iib_camel.runtime.compute.AbstractComputeBean;

@Component("adapterCompute1")
public class AdapterCompute1 extends AbstractComputeBean {

    @Override
    protected void main(Exchange exchange) {

        copy(exchange);

        mqmd(exchange, "ApplicationIdData", "IIB-CAMEL-MIG");
    }
}
