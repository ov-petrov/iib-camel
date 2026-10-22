package com.example.iib_camel.runtime.generated.model;

/**
 * Поддерево тела сообщения: Order (сгенерировано, FR-3.4).
 * Поля восстановлены из обращений к дереву ESQL; типы пока String
 * (компромисс: CAST выводится примечанием). Поведение маршрута правит
 * тело динамически (AbstractComputeBean.setBodyField); класс — основа
 * для будущих мапперов FR-4.1.
 */
public class Order {

    /** SET OutputRoot.XMLNSC.Order.Status = 'ADJUSTED'; */
    private String Status;

    public String Status() {
        return Status;
    }

    public void setStatus(String value) {
        this.Status = value;
    }

    /** SET OutputRoot.XMLNSC.Order.Rating = CAST('100' AS DECIMAL); */
    private String Rating;

    public String Rating() {
        return Rating;
    }

    public void setRating(String value) {
        this.Rating = value;
    }

}
