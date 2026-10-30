package ru.petrovov.iib_camel.runtime_lite.processor.example;

import com.fasterxml.jackson.annotation.JsonPropertyOrder;
import lombok.Getter;
import lombok.Setter;

@Getter
@Setter
@JsonPropertyOrder({"name", "account"})
class Payer {

    private String name;

    private String account;
}
