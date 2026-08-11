package com.techstore.order.service.payment;

import java.util.Map;
import java.util.Optional;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.techstore.event.constant.SagaTopics;
import com.techstore.event.dto.PaymentCompletedEvent;
import com.techstore.event.dto.PaymentFailedEvent;
import com.techstore.order.entity.Payment;
import com.techstore.order.repository.PaymentRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

/**
 * Bọc quanh PaymentStrategy.handleCallback() có sẵn để:
 * 1) biết kết quả thành/bại (method gốc trả void)
 * 2) publish payment.completed / payment.failed cho saga
 * 3) trả về CallbackResult để controller quyết định redirect đúng trang
 *
 * KHÔNG sửa VNPayPaymentStrategy / PaymentStrategy / PaymentStrategyFactory.
 */
@Log4j2
@Service
@RequiredArgsConstructor
public class PaymentCallbackSagaHandler {

    public enum CallbackResult {
        SUCCESS,
        FAILED,
        ERROR
    }

    private final PaymentStrategyFactory paymentFactory;
    private final PaymentRepository paymentRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public CallbackResult handle(String method, Map<String, String> params) {

        String txnRef = params.get("vnp_TxnRef");

        // Lấy orderId TRƯỚC khi gọi handleCallback, vì nếu nó throw (amount mismatch,
        // invalid signature), transaction rollback nhưng ta vẫn cần orderId để
        // publish payment.failed đúng saga.
        Optional<Payment> paymentBefore = paymentRepository.findByTransactionCode(txnRef);
        if (paymentBefore.isEmpty()) {
            log.error("IPN callback với txnRef={} không khớp Payment nào trong hệ thống", txnRef);
            return CallbackResult.ERROR;
        }
        Long orderId = paymentBefore.get().getOrder().getId();

        // Idempotent guard ở tầng saga: nếu Payment đã SUCCESS từ trước (VNPay gọi IPN lại)
        // thì không publish lại event, tránh order-service xử lý CONFIRMED 2 lần.
        if ("SUCCESS".equals(paymentBefore.get().getStatus())) {
            log.info("Payment {} đã SUCCESS từ trước, bỏ qua publish trùng", txnRef);
            return CallbackResult.SUCCESS;
        }

        try {
            PaymentStrategy strategy = paymentFactory.getStrategy(method);
            strategy.handleCallback(params); // logic gốc, không đổi

        } catch (RuntimeException e) {
            log.warn("handleCallback thất bại cho order {}: {}", orderId, e.getMessage());
            kafkaTemplate.send(
                    SagaTopics.PAYMENT_FAILED,
                    orderId.toString(),
                    PaymentFailedEvent.builder()
                            .orderId(orderId)
                            .reason(e.getMessage())
                            .build());
            return CallbackResult.ERROR;
        }

        // handleCallback chạy xong không exception -> transaction đã commit, đọc lại kết quả thật
        Payment paymentAfter = paymentRepository.findByTransactionCode(txnRef).orElse(null);
        if (paymentAfter == null) return CallbackResult.ERROR;

        if ("SUCCESS".equals(paymentAfter.getStatus())) {
            kafkaTemplate.send(
                    SagaTopics.PAYMENT_COMPLETED,
                    orderId.toString(),
                    PaymentCompletedEvent.builder()
                            .orderId(orderId)
                            .transactionRef(txnRef)
                            .build());
            return CallbackResult.SUCCESS;

        } else if ("FAILED".equals(paymentAfter.getStatus())) {
            kafkaTemplate.send(
                    SagaTopics.PAYMENT_FAILED,
                    orderId.toString(),
                    PaymentFailedEvent.builder()
                            .orderId(orderId)
                            .reason("GATEWAY_DECLINED")
                            .build());
            return CallbackResult.FAILED;
        }

        // Vẫn PENDING (không nên xảy ra sau khi handleCallback chạy xong, nhưng phòng hờ)
        return CallbackResult.ERROR;
    }
}
