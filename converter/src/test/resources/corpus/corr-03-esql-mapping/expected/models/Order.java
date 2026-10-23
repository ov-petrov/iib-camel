package ru.petrovov.iib_camel.runtime.generated.model;

// Модель из ESQL-путей; типы String (FR-3.4).
public class Order {

    // SET OutputRoot.XMLNSC.Order.Status = 'ADJUSTED';
    private String status;

    public String getStatus() {
        return status;
    }

    public void setStatus(String value) {
        this.status = value;
    }

    // SET OutputRoot.XMLNSC.Order.Rating = CAST('100' AS DECIMAL);
    private String rating;

    public String getRating() {
        return rating;
    }

    public void setRating(String value) {
        this.rating = value;
    }

}
