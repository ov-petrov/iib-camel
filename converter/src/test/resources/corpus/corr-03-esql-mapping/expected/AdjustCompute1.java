package ru.petrovov.iib_camel.runtime.generated;

import ru.petrovov.iib_camel.runtime.compute.AbstractComputeBean;
import org.apache.camel.Exchange;
import org.springframework.stereotype.Component;

@Component("adjustCompute1")
public class AdjustCompute1 extends AbstractComputeBean {

    @Override
    protected void main(Exchange exchange) {
        throw new UnsupportedOperationException("ESQL module Adjust_Compute1: translation incomplete; see migration report");
    }
}
