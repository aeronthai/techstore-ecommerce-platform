package com.techstore.order.service.payment.impl;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.techstore.event.constant.SagaTopics;
import com.techstore.event.dto.PaymentFailedEvent;
import com.techstore.order.entity.Order;
import com.techstore.order.repository.OrderRepository;
import com.techstore.order.service.payment.PaymentStrategy;
import com.techstore.order.service.payment.PaymentStrategyFactory;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
@RequiredArgsConstructor
public class PaymentRequestedSagaListener {

    private final OrderRepository orderRepository;
    private final PaymentStrategyFactory paymentFactory;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = SagaTopics.PAYMENT_REQUESTED, groupId = "order-saga")
    public void onPaymentRequested(Long orderId) {

        Order order = orderRepository.findById(orderId).orElse(null);
        if (order == null || order.getPaymentMethod() == null) return;

        try {
            PaymentStrategy strategy =
                    paymentFactory.getStrategy(order.getPaymentMethod().getName());

            String paymentUrl = strategy.createPaymentUrl(order, "0.0.0.0");

        } catch (RuntimeException e) {
            log.error("Không tạo được payment URL cho order {}", orderId, e);
            kafkaTemplate.send(
                    SagaTopics.PAYMENT_FAILED,
                    orderId.toString(),
                    PaymentFailedEvent.builder()
                            .orderId(orderId)
                            .reason("CANNOT_CREATE_PAYMENT_URL")
                            .build());
        }
    }
}
