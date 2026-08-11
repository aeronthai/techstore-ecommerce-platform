package com.techstore.event.dto;

import java.io.Serializable;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCreatedEvent implements Serializable {
    private static final long serialVersionUID = 1L;
    private Long orderId;
    private Long customerId;
    private Long couponId;
    private String paymentMethod;
    private String ipAddress;
    private List<OrderItemPayload> items;

    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    public static class OrderItemPayload implements Serializable {
        private static final long serialVersionUID = 1L;
        private Long variantId;
        private Long quantity;
    }
}
