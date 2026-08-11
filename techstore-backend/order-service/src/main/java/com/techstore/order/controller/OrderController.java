package com.techstore.order.controller;

import java.time.LocalDate;
import java.util.List;

import jakarta.servlet.http.HttpServletRequest;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.techstore.order.dto.request.OrderCreateRequest;
import com.techstore.order.dto.response.AdminOrderResponse;
import com.techstore.order.dto.response.ApiResponse;
import com.techstore.order.dto.response.CustomerOrderResponse;
import com.techstore.order.dto.response.OrderDetailResponse;
import com.techstore.order.dto.response.OrderResponse;
import com.techstore.order.dto.response.OrderSummaryResponse;
import com.techstore.order.dto.response.ProductSalesResponse;
import com.techstore.order.dto.response.RevenueStatsResponse;
import com.techstore.order.dto.response.TopLoyalCustomerResponse;
import com.techstore.order.dto.response.TopVariantResponse;
import com.techstore.order.service.OrderService;
import com.techstore.order.service.saga.OrderSagaCommandService;
import com.techstore.order.util.VNPayUtils;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/orders")
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderSagaCommandService sagaCommandService;

    @GetMapping("/customer/{customerId}")
    public ApiResponse<List<CustomerOrderResponse>> getOrdersByCustomer(
            @PathVariable Long customerId, @RequestParam(required = false) String status) {

        return ApiResponse.<List<CustomerOrderResponse>>builder()
                .result(orderService.getOrdersByCustomer(customerId, status))
                .build();
    }

    @GetMapping
    public ApiResponse<List<AdminOrderResponse>> getAllOrders(@RequestParam(required = false) String status) {

        return ApiResponse.<List<AdminOrderResponse>>builder()
                .result(orderService.getAllOrders(status))
                .build();
    }

    @PostMapping
    public ApiResponse<OrderResponse> createOrder(
            @RequestBody OrderCreateRequest request, HttpServletRequest servletRequest) {

        String ipAddress = VNPayUtils.getIpAddress(servletRequest);

        return ApiResponse.<OrderResponse>builder()
                .result(orderService.createOrder(request, ipAddress))
                .build();
    }

    @PostMapping("/saga")
    public ApiResponse<Long> createOrder(@RequestBody OrderCreateRequest request) {
        Long orderId = sagaCommandService.createOrder(request);
        return ApiResponse.<Long>builder().result(orderId).build();
    }

    @GetMapping("/order-detail/{id}")
    public ApiResponse<OrderDetailResponse> getOrderDetail(@PathVariable Long id) {
        return ApiResponse.<OrderDetailResponse>builder()
                .result(orderService.getOrderDetail(id))
                .build();
    }

    @PostMapping("/{id}/confirm")
    public ApiResponse<Void> confirmOrder(@PathVariable Long id, @RequestParam Long staffId) {

        orderService.confirmOrder(id, staffId);

        return ApiResponse.<Void>builder().build();
    }

    @PostMapping("/{id}/cancel")
    public ApiResponse<Void> cancelOrder(@PathVariable Long id, @RequestParam Long staffId) {

        orderService.cancelOrder(id, staffId);

        return ApiResponse.<Void>builder().build();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable Long id, @RequestParam String status) {

        orderService.updateStatus(id, status);

        return ApiResponse.<Void>builder().build();
    }

    @PutMapping("/order-detail/{id}/reviewed")
    public ApiResponse<Void> markReviewed(@PathVariable Long id) {

        orderService.markOrderDetailReviewed(id);

        return ApiResponse.<Void>builder().build();
    }

    @GetMapping("/stats/revenue")
    public ApiResponse<RevenueStatsResponse> getRevenueStats(
            @RequestParam(required = false, defaultValue = "MONTH") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.<RevenueStatsResponse>builder()
                .result(orderService.getRevenueStats(period, from, to))
                .build();
    }

    @GetMapping("/stats/top-variants")
    public ApiResponse<List<TopVariantResponse>> getTopVariants(
            @RequestParam(required = false, defaultValue = "10") int top,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.<List<TopVariantResponse>>builder()
                .result(orderService.getTopVariants(top, from, to))
                .build();
    }

    @GetMapping("/stats/summary")
    public ApiResponse<OrderSummaryResponse> getOrderSummary(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        return ApiResponse.<OrderSummaryResponse>builder()
                .result(orderService.getOrderSummary(status, from, to))
                .build();
    }

    @GetMapping("/stats/top-loyal-customers")
    public ApiResponse<List<TopLoyalCustomerResponse>> getTopLoyalCustomers(
            @RequestParam(required = false, defaultValue = "10") int top,
            @RequestParam(required = false, defaultValue = "MONTH") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ApiResponse.<List<TopLoyalCustomerResponse>>builder()
                .result(orderService.getTopLoyalCustomers(top, period, from, to))
                .build();
    }

    @GetMapping("/stats/product-sales/{productId}")
    public ApiResponse<ProductSalesResponse> getProductSales(
            @PathVariable Long productId,
            @RequestParam(required = false, defaultValue = "MONTH") String period,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {

        return ApiResponse.<ProductSalesResponse>builder()
                .result(orderService.getProductSales(productId, period, from, to))
                .build();
    }

    @GetMapping("/{id}/print-label")
    public ApiResponse<String> printLabel(@PathVariable Long id) {
        return ApiResponse.<String>builder().result(orderService.printLabel(id)).build();
    }
}
