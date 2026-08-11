package com.techstore.order.controller;

import java.io.IOException;
import java.util.Map;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.techstore.order.service.payment.PaymentCallbackSagaHandler;
import com.techstore.order.service.payment.PaymentCallbackSagaHandler.CallbackResult;
import com.techstore.order.service.payment.PaymentStrategy;
import com.techstore.order.service.payment.PaymentStrategyFactory;

import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/payment")
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentStrategyFactory paymentFactory;
    private final PaymentCallbackSagaHandler sagaHandler;

    @GetMapping("/vnpay/ipn")
    public void handleVNPayIPN(
            @RequestParam Map<String, String> allParams, HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        PaymentStrategy strategy = paymentFactory.getStrategy("VNPAY");

        strategy.handleCallback(allParams);

        response.sendRedirect("http://localhost:4200/order-success?txnRef=" + allParams.get("vnp_TxnRef"));
    }

    @GetMapping("/saga/vnpay/ipn")
    public void handleVNPayIPNSaga(
            @RequestParam Map<String, String> allParams, HttpServletRequest request, HttpServletResponse response)
            throws IOException {

        // Toàn bộ logic gốc (verify signature, idempotent, amount check,
        // set Payment/Order status) vẫn nằm nguyên trong strategy.handleCallback()
        // sagaHandler chỉ BỌC quanh nó để biết kết quả và publish event.
        CallbackResult result = sagaHandler.handle("VNPAY", allParams);

        String txnRef = allParams.get("vnp_TxnRef");

        switch (result) {
            case SUCCESS -> response.sendRedirect("http://localhost:4200/order-success?txnRef=" + txnRef);
            case FAILED -> response.sendRedirect("http://localhost:4200/order-failed?txnRef=" + txnRef);
            case ERROR -> response.sendRedirect(
                    "http://localhost:4200/order-failed?txnRef=" + txnRef + "&reason=error");
        }
    }
}
