package com.techstore.order.service.saga;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.techstore.event.constant.SagaTopics;
import com.techstore.event.dto.OrderCancelledEvent;
import com.techstore.order.constant.OrderSagaStatus;
import com.techstore.order.entity.Order;
import com.techstore.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Quét định kỳ các order bị "treo" quá lâu ở PENDING_STOCK/PENDING_PAYMENT
 * (do event bị mất, service down giữa chừng, v.v.) và tự bù trừ.
 */
@Log4j2
@Component
@RequiredArgsConstructor
public class OrderSagaTimeoutJob {

    private final OrderRepository orderRepository;
    private final CouponCompensationService couponCompensationService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    private static final int TIMEOUT_MINUTES = 15;

    @Scheduled(fixedDelay = 5 * 60 * 1000) // 5 phút / lần
    public void compensateStuckOrders() {

        LocalDateTime threshold = LocalDateTime.now().minusMinutes(TIMEOUT_MINUTES);
        List<Order> stuckOrders = orderRepository.findByStatusInAndCreatedAtBefore(
                List.of(OrderSagaStatus.PENDING_STOCK, OrderSagaStatus.PENDING_PAYMENT), threshold);

        for (Order order : stuckOrders) {
            log.warn(
                    "Order {} bị treo ở status {} quá {} phút, tự compensate",
                    order.getId(),
                    order.getStatus(),
                    TIMEOUT_MINUTES);

            couponCompensationService.releaseCouponIfAny(order);
            order.setStatus("CANCELLED");
            orderRepository.save(order);

            kafkaTemplate.send(
                    SagaTopics.ORDER_CANCELLED,
                    order.getId().toString(),
                    OrderCancelledEvent.builder()
                            .orderId(order.getId())
                            .reason("TIMEOUT")
                            .build());
        }
    }
}
