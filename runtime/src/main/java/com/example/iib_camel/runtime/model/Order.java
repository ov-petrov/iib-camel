package com.example.iib_camel.runtime.model;

/**
 * Уровень заказа (ULM, FR-3.4); поля соответствуют XML-фрагменту
 * {@code <Adapter><Order><id/><status/><amount/>}.
 */
public class Order {

    private String id;
    private String status;
    private String amount;

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getAmount() {
        return amount;
    }

    public void setAmount(String amount) {
        this.amount = amount;
    }
}
