package com.techstore.warehouse.service.saga;

import java.util.List;
import java.util.stream.Collectors;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import com.techstore.event.constant.SagaTopics;
import com.techstore.event.dto.InventoryReservationFailedEvent;
import com.techstore.event.dto.InventoryReservedEvent;
import com.techstore.event.dto.OrderCreatedEvent;
import com.techstore.warehouse.dto.request.OrderItemRequest;
import com.techstore.warehouse.exception.AppException;
import com.techstore.warehouse.service.WarehouseTransactionService;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Phản ứng lại order.created: gọi thẳng WarehouseTransactionService.exportInventory()
 * đã có sẵn (KHÔNG sửa 1 dòng nào trong service đó), rồi phát kết quả.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderCreatedSagaListener {

    private final WarehouseTransactionService transactionService;
    private final KafkaTemplate<String, Object> kafkaTemplate;

    @KafkaListener(topics = SagaTopics.ORDER_CREATED, groupId = "warehouse-saga")
    public void onOrderCreated(OrderCreatedEvent event) {

        List<OrderItemRequest> items = event.getItems().stream()
                .map(i -> {
                    OrderItemRequest r = new OrderItemRequest();
                    r.setVariantId(i.getVariantId());
                    r.setQuantity(i.getQuantity());
                    return r;
                })
                .collect(Collectors.toList());

        try {
            List<Long> transactionIds = transactionService.exportInventory(event.getOrderId(), items);

            kafkaTemplate.send(
                    SagaTopics.INVENTORY_RESERVED,
                    event.getOrderId().toString(),
                    InventoryReservedEvent.builder()
                            .orderId(event.getOrderId())
                            .warehouseTransactionIds(transactionIds)
                            .build());

        } catch (AppException e) {
            log.warn("Reserve inventory thất bại cho order {}: {}", event.getOrderId(), e.getMessage());

            kafkaTemplate.send(
                    SagaTopics.INVENTORY_RESERVATION_FAILED,
                    event.getOrderId().toString(),
                    InventoryReservationFailedEvent.builder()
                            .orderId(event.getOrderId())
                            .reason(e.getMessage())
                            .build());
        }
    }
}
