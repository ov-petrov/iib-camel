package ru.petrovov.iib_camel.runtime.model;

import lombok.Getter;
import lombok.Setter;


@Getter
@Setter
public class Order {

    private String id;
    private String status;
    private String amount;
}
