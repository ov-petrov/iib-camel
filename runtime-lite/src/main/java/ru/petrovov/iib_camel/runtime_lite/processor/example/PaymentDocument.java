package ru.petrovov.iib_camel.runtime_lite.processor.example;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import com.fasterxml.jackson.annotation.JsonRootName;
import lombok.Getter;
import lombok.Setter;

import java.math.BigDecimal;

@Getter
@Setter
@JsonPropertyOrder({"documentId", "amount", "currency", "payer", "sendNotification"})
@JsonRootName("PaymentDocument")
class PaymentDocument {

    private String documentId;

    private BigDecimal amount;

    private String currency;

    private Payer payer;

    private boolean sendNotification;
}
