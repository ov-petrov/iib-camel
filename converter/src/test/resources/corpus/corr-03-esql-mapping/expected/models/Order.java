package ru.petrovov.iib_camel.runtime.generated.model;

// Модель из ESQL-путей; типы String (FR-3.4).
public class Order {

    // SET OutputRoot.XMLNSC.Order.Status = 'ADJUSTED';
    private String Status;

    public String Status() {
        return Status;
    }

    public void setStatus(String value) {
        this.Status = value;
    }

    // SET OutputRoot.XMLNSC.Order.Rating = CAST('100' AS DECIMAL);
    private String Rating;

    public String Rating() {
        return Rating;
    }

    public void setRating(String value) {
        this.Rating = value;
    }

}
