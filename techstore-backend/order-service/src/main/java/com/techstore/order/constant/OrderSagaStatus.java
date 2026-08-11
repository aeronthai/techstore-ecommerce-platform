package com.techstore.order.constant;

public final class OrderSagaStatus {
    public static final String PENDING_STOCK = "PENDING_STOCK";
    public static final String PENDING_PAYMENT = "PENDING_PAYMENT";
    public static final String COMPENSATING = "COMPENSATING";

    private OrderSagaStatus() {}
}
