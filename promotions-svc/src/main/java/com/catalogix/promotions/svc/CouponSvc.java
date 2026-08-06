package com.catalogix.promotions.svc;

import com.catalogix.promotions.dto.*;
import com.catalogix.promotions.exception.CouponInvalidException;
import com.catalogix.promotions.model.Coupon;
import com.catalogix.promotions.repository.CouponRepository;

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
     * Read-only preview: "would this code currently work, and for how much?"
     * Does NOT touch usedCount, so it's safe to call repeatedly as a cart
     * changes (cart-svc calls this on every applyCoupon/quantity update).
     * The actual redemption only happens in commit(), at the moment
     * checkout-svc is finalizing an order.
     */
    @Transactional(readOnly = true)
    public DiscountResponse preview(String code, BigDecimal subtotal) {
        Coupon coupon = repo.findByCodeIgnoreCase(code)
                .orElseThrow(() -> new CouponInvalidException("Coupon code not found: " + code));
        if (!coupon.isCurrentlyRedeemable(Instant.now())) {
            throw new CouponInvalidException("Coupon is no longer valid: " + code);
        }
        return new DiscountResponse(coupon.getCode(), calculateDiscount(coupon, subtotal));
    }

    /**
     * Atomically re-validates and redeems one use of the coupon, returning
     * the discount to apply. Called by checkout-svc exactly once per order,
     * inside the window where the order is actually being committed.
     * Pessimistic-locks the row so this is safe under concurrent checkouts
     * for the same code — see findByCodeIgnoreCaseForUpdate.
     */
    @Transactional
    public DiscountResponse commit(String code, BigDecimal subtotal) {
        Coupon coupon = repo.findByCodeIgnoreCaseForUpdate(code)
                .orElseThrow(() -> new CouponInvalidException("Coupon code not found: " + code));
        if (!coupon.isCurrentlyRedeemable(Instant.now())) {
            throw new CouponInvalidException("Coupon is no longer valid: " + code);
        }
        coupon.setUsedCount(coupon.getUsedCount() + 1);
        repo.save(coupon);
        return new DiscountResponse(coupon.getCode(), calculateDiscount(coupon, subtotal));
    }

    /**
     * Compensation: called by checkout-svc's outbox when an order that had
     * already committed a coupon use ends up failing/cancelled downstream
     * (e.g. payment declined) — the customer shouldn't lose a redemption for
     * an order that never went through.
     */
    @Transactional
    public void release(String code) {
        repo.findByCodeIgnoreCaseForUpdate(code).ifPresent(c -> {
            c.setUsedCount(Math.max(0, c.getUsedCount() - 1));
            repo.save(c);
        });
    }

    private BigDecimal calculateDiscount(Coupon coupon, BigDecimal subtotal) {
        BigDecimal discount = switch (coupon.getDiscountType()) {
            case PERCENTAGE -> subtotal.multiply(coupon.getDiscountValue())
                    .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);
            case FIXED_AMOUNT -> coupon.getDiscountValue();
        };
        return discount.min(subtotal).setScale(2, RoundingMode.HALF_UP);
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
