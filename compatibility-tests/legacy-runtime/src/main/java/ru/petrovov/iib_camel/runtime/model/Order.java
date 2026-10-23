package ru.petrovov.iib_camel.runtime.model;

import lombok.Getter;
import lombok.Setter;

/**
 * Уровень заказа (ULM, FR-3.4); поля соответствуют XML-фрагменту
 * {@code <Adapter><Order><id/><status/><amount/>}.
 */
@Getter
@Setter
public class Order {

    private String id;
    private String status;
    private String amount;
}
