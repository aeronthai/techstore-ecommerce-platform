package com.techstore.order.service.saga;

import org.springframework.stereotype.Service;

import com.techstore.order.entity.Coupon;
import com.techstore.order.entity.Order;
import com.techstore.order.repository.CouponRepository;

import lombok.RequiredArgsConstructor;

@Service
@RequiredArgsConstructor
public class CouponCompensationService {

    private final CouponRepository couponRepository;

    public void releaseCouponIfAny(Order order) {
        if (order.getCoupon() == null) return;

        Coupon coupon = order.getCoupon();
        Integer newCount = Math.max(0, coupon.getUsedCount() - 1);
        coupon.setUsedCount(newCount);
        couponRepository.save(coupon);
    }
}
