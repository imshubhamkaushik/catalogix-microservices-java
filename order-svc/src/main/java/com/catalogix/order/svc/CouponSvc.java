package com.catalogix.order.svc;

import com.catalogix.order.dto.CouponResponse;
import com.catalogix.order.dto.CreateCouponRequest;
import com.catalogix.order.exception.CouponInvalidException;
import com.catalogix.order.model.Coupon;
import com.catalogix.order.repository.CouponRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;

@Service
public class CouponSvc {

    private final CouponRepository repo;

    public CouponSvc(CouponRepository repo) {
        this.repo = repo;
    }

    /**
     * @throws CouponInvalidException if the code doesn't exist, is inactive, expired, or exhausted.
     */
    @Transactional(readOnly = true)
    public Coupon validate(String code) {
        Coupon coupon = repo.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new CouponInvalidException("Coupon code not found: " + code));
        if (!coupon.isCurrentlyRedeemable(Instant.now())) {
            throw new CouponInvalidException("Coupon is no longer valid: " + code);
        }
        return coupon;
    }

    // Never discounts below zero (a fixed-amount coupon bigger than the order just zeroes it out).
    public BigDecimal calculateDiscount(Coupon coupon, BigDecimal subtotal) {
        BigDecimal discount = switch (coupon.getDiscountType()) {
            case PERCENTAGE -> subtotal.multiply(coupon.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            case FIXED_AMOUNT -> coupon.getDiscountValue();
        };
        return discount.min(subtotal).setScale(2, RoundingMode.HALF_UP);
    }

    @Transactional
    public void recordUsage(Coupon coupon) {
        coupon.setUsedCount(coupon.getUsedCount() + 1);
        repo.save(coupon);
    }

    // Called when an order that consumed a coupon use is cancelled or its
    // payment fails — the customer shouldn't lose a redemption for an order
    // that never actually went through.
    @Transactional
    public void releaseUsage(String code) {
        repo.findByCodeIgnoreCase(code).ifPresent(c -> {
            c.setUsedCount(Math.max(0, c.getUsedCount() - 1));
            repo.save(c);
        });
    }

    // ---- Admin management ----

    @Transactional
    public CouponResponse create(CreateCouponRequest req) {
        if (repo.findByCodeIgnoreCase(req.getCode()).isPresent()) {
            throw new IllegalArgumentException("Coupon code already exists: " + req.getCode());
        }
        Coupon coupon = new Coupon();
        coupon.setCode(req.getCode().toUpperCase());
        coupon.setDiscountType(req.getDiscountType());
        coupon.setDiscountValue(req.getDiscountValue());
        coupon.setMaxUses(req.getMaxUses());
        coupon.setExpiresAt(req.getExpiresAt());
        return toResponse(repo.save(coupon));
    }

    @Transactional(readOnly = true)
    public List<CouponResponse> listAll() {
        return repo.findAll().stream().map(this::toResponse).toList();
    }

    @Transactional
    public CouponResponse deactivate(Long id) {
        Coupon coupon = repo.findById(id)
                .orElseThrow(() -> new IllegalArgumentException("Coupon not found: " + id));
        coupon.setActive(false);
        return toResponse(repo.save(coupon));
    }

    private CouponResponse toResponse(Coupon c) {
        return new CouponResponse(c.getId(), c.getCode(), c.getDiscountType(), c.getDiscountValue(),
                c.getMaxUses(), c.getUsedCount(), c.getExpiresAt(), c.isActive(), c.getCreatedAt());
    }
}
