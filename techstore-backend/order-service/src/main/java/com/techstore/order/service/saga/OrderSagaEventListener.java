package com.techstore.order.service.saga;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.techstore.event.constant.SagaTopics;
import com.techstore.event.dto.InventoryReservationFailedEvent;
import com.techstore.event.dto.InventoryReservedEvent;
import com.techstore.event.dto.OrderCancelledEvent;
import com.techstore.event.dto.PaymentCompletedEvent;
import com.techstore.event.dto.PaymentFailedEvent;
import com.techstore.event.dto.PostEvent;
import com.techstore.order.client.WarehouseServiceClient;
import com.techstore.order.constant.OrderSagaStatus;
import com.techstore.order.entity.Order;
import com.techstore.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Lắng nghe các event phản hồi từ warehouse-service / payment-service
 * để tiến/lùi state của Order. Đây là "phản ứng dây chuyền" đúng tinh thần choreography:
 * order-service không gọi lệnh cho ai, chỉ phản ứng lại event.
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class OrderSagaEventListener {

    private final OrderRepository orderRepository;
    private final WarehouseServiceClient warehouseClient; // tái dùng client đã có sẵn
    private final CouponCompensationService couponCompensationService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    // ---------- Happy path ----------

    @KafkaListener(topics = SagaTopics.INVENTORY_RESERVED, groupId = "order-saga")
    @Transactional
    public void onInventoryReserved(InventoryReservedEvent event) {

        Order order = orderRepository.findById(event.getOrderId()).orElse(null);
        if (order == null) return;

        // Idempotent guard: nếu order không còn ở PENDING_STOCK thì bỏ qua (event trễ/duplicate)
        if (!OrderSagaStatus.PENDING_STOCK.equals(order.getStatus())) {
            log.warn(
                    "Order {} không ở PENDING_STOCK (hiện tại: {}), bỏ qua inventory.reserved",
                    order.getId(),
                    order.getStatus());
            return;
        }

        String txIds = event.getWarehouseTransactionIds().stream()
                .map(String::valueOf)
                .reduce((a, b) -> a + "," + b)
                .orElse(null);
        order.setWarehouseTransactionId(txIds);

        if (order.getPaymentMethod() != null) {
            order.setStatus(OrderSagaStatus.PENDING_PAYMENT);
            orderRepository.save(order);
            kafkaTemplate.send(SagaTopics.PAYMENT_REQUESTED, order.getId().toString(), order.getId());
        } else {
            // COD: xác nhận luôn
            order.setStatus("CONFIRMED");
            orderRepository.save(order);
            publishNotification(order, "CONFIRMED");
            kafkaTemplate.send(SagaTopics.ORDER_CONFIRMED, order.getId().toString(), order.getId());
        }
    }

    @KafkaListener(topics = SagaTopics.PAYMENT_COMPLETED, groupId = "order-saga")
    @Transactional
    public void onPaymentCompleted(PaymentCompletedEvent event) {

        Order order = orderRepository.findById(event.getOrderId()).orElse(null);
        if (order == null || !OrderSagaStatus.PENDING_PAYMENT.equals(order.getStatus())) return;

        order.setStatus("CONFIRMED");
        orderRepository.save(order);
        publishNotification(order, "CONFIRMED");
        kafkaTemplate.send(SagaTopics.ORDER_CONFIRMED, order.getId().toString(), order.getId());
    }

    // ---------- Compensation ----------

    @KafkaListener(topics = SagaTopics.INVENTORY_RESERVATION_FAILED, groupId = "order-saga")
    @Transactional
    public void onInventoryReservationFailed(InventoryReservationFailedEvent event) {

        Order order = orderRepository.findById(event.getOrderId()).orElse(null);
        if (order == null) return;

        // Chưa trừ kho => không cần gọi cancelTransaction, chỉ cần release coupon
        couponCompensationService.releaseCouponIfAny(order);

        order.setStatus("CANCELLED");
        orderRepository.save(order);
        publishNotification(order, "CANCELLED");
        kafkaTemplate.send(
                SagaTopics.ORDER_CANCELLED,
                order.getId().toString(),
                OrderCancelledEvent.builder()
                        .orderId(order.getId())
                        .reason(event.getReason())
                        .build());
    }

    @KafkaListener(topics = SagaTopics.PAYMENT_FAILED, groupId = "order-saga")
    @Transactional
    public void onPaymentFailed(PaymentFailedEvent event) {

        Order order = orderRepository.findById(event.getOrderId()).orElse(null);
        if (order == null) return;

        compensateReservedInventory(order);
        couponCompensationService.releaseCouponIfAny(order);

        order.setStatus("CANCELLED");
        orderRepository.save(order);
        publishNotification(order, "CANCELLED");
        kafkaTemplate.send(
                SagaTopics.ORDER_CANCELLED,
                order.getId().toString(),
                OrderCancelledEvent.builder()
                        .orderId(order.getId())
                        .reason(event.getReason())
                        .build());
    }

    /** Dùng lại đúng API cancelTransaction() đã có sẵn trong WarehouseServiceClient. */
    private void compensateReservedInventory(Order order) {
        if (order.getWarehouseTransactionId() == null
                || order.getWarehouseTransactionId().isBlank()) return;

        for (String txId : order.getWarehouseTransactionId().split(",")) {
            try {
                warehouseClient.cancelTransaction(Long.valueOf(txId.trim()));
            } catch (Exception e) {
                log.error("Không thể huỷ warehouse transaction {} cho order {}", txId, order.getId(), e);
                // Không throw để không chặn compensation của các bước còn lại;
                // nên có thêm cơ chế retry/dead-letter cho production
            }
        }
    }

    private void publishNotification(Order order, String status) {
        PostEvent event = PostEvent.builder()
                .title("Cập nhật trạng thái đơn hàng")
                .content("Đơn hàng #" + order.getId() + " hiện đang: " + status)
                .userId(order.getCustomerId().toString())
                .build();
        kafkaTemplate.send("post-delivery", event);
    }
}
