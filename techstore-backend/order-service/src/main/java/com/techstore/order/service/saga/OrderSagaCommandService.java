package com.techstore.order.service.saga;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.techstore.event.constant.SagaTopics;
import com.techstore.event.dto.OrderCreatedEvent;
import com.techstore.order.constant.OrderSagaStatus;
import com.techstore.order.dto.request.OrderCreateRequest;
import com.techstore.order.entity.Coupon;
import com.techstore.order.entity.Order;
import com.techstore.order.exception.AppException;
import com.techstore.order.exception.ErrorCode;
import com.techstore.order.repository.CouponRepository;
import com.techstore.order.repository.OrderRepository;

import lombok.RequiredArgsConstructor;
import lombok.extern.log4j.Log4j2;

@Log4j2
@Service
@RequiredArgsConstructor
public class OrderSagaCommandService {

    private final OrderRepository orderRepository;
    private final CouponRepository couponRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    public Long createOrder(OrderCreateRequest request) {

        // ===== 1. Validate + "khóa mềm" coupon (giữ nguyên logic nghiệp vụ cũ) =====
        Coupon coupon = null;
        if (request.getCouponId() != null) {
            coupon = couponRepository
                    .findById(request.getCouponId())
                    .orElseThrow(() -> new AppException(ErrorCode.COUPON_NOT_FOUND));

            if (!"ACTIVE".equalsIgnoreCase(coupon.getStatus())) {
                throw new AppException(ErrorCode.COUPON_INVALID);
            }
            if (coupon.getUsageLimit() != null && coupon.getUsedCount() >= coupon.getUsageLimit()) {
                throw new AppException(ErrorCode.COUPON_LIMIT_REACHED);
            }

            // Tăng usedCount ngay để tránh 2 order cùng dùng hết coupon (sẽ release nếu saga fail)
            coupon.setUsedCount(coupon.getUsedCount() + 1);
            couponRepository.save(coupon);
        }

        // ===== 2. Tạo Order ở trạng thái chờ, KHÔNG gọi warehouse/product trực tiếp =====
        Order order = new Order();
        order.setCustomerId(request.getCustomerId());
        order.setStatus(OrderSagaStatus.PENDING_STOCK);
        if (coupon != null) {
            order.setCoupon(coupon);
        }
        // Lưu tạm số lượng item dạng JSON/snapshot nếu cần truy vết (tuỳ chọn, không bắt buộc)
        orderRepository.save(order);

        // ===== 3. Phát sự kiện order.created =====
        List<OrderCreatedEvent.OrderItemPayload> items = request.getItems().stream()
                .map(i -> OrderCreatedEvent.OrderItemPayload.builder()
                        .variantId(i.getVariantId())
                        .quantity(i.getQuantity())
                        .build())
                .collect(Collectors.toList());

        OrderCreatedEvent event = OrderCreatedEvent.builder()
                .orderId(order.getId())
                .customerId(order.getCustomerId())
                .couponId(request.getCouponId())
                .paymentMethod(request.getPaymentMethod())
                .items(items)
                .build();

        // key = orderId để đảm bảo thứ tự event của cùng 1 order nằm cùng partition
        kafkaTemplate.send(SagaTopics.ORDER_CREATED, order.getId().toString(), event);

        log.info("Saga: order {} created, waiting inventory reservation", order.getId());
        return order.getId();
    }
}
