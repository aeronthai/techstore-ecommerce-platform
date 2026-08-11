package com.techstore.event.constant;

public final class SagaTopics {
    public static final String ORDER_CREATED = "order.created";
    public static final String INVENTORY_RESERVED = "inventory.reserved";
    public static final String INVENTORY_RESERVATION_FAILED = "inventory.reservation.failed";
    public static final String PAYMENT_REQUESTED = "payment.requested";
    public static final String PAYMENT_COMPLETED = "payment.completed";
    public static final String PAYMENT_FAILED = "payment.failed";
    public static final String ORDER_CANCELLED = "order.cancelled";
    public static final String ORDER_CONFIRMED = "order.confirmed";

    private SagaTopics() {}
}
