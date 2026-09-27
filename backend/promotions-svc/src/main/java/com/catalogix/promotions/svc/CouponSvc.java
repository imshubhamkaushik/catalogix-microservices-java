package com.catalogix.promotions.svc;

import com.catalogix.promotions.dto.*;
import com.catalogix.promotions.exception.CouponInvalidException;
import com.catalogix.promotions.model.Coupon;
import com.catalogix.promotions.model.CouponRedemption;
import com.catalogix.promotions.repository.CouponRedemptionRepository;
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
    private final CouponRedemptionRepository redemptionRepo;

    public CouponSvc(CouponRepository repo, CouponRedemptionRepository redemptionRepo) {
        this.repo = repo;
        this.redemptionRepo = redemptionRepo;
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
        return commitInternal(code, subtotal, null);
    }

    /** Idempotent redemption path used by checkout-svc. */
    @Transactional
    public DiscountResponse commit(String code, BigDecimal subtotal, String operationId) {
        return commitInternal(code, subtotal, operationId);
    }

    private DiscountResponse commitInternal(
            String code,
            BigDecimal subtotal,
            String operationId) {
        Coupon coupon = repo.findByCodeIgnoreCaseForUpdate(code)
                .orElseThrow(() -> new CouponInvalidException("Coupon code not found: " + code));

        if (operationId == null || operationId.isBlank()) {
            if (!coupon.isCurrentlyRedeemable(Instant.now())) {
                throw new CouponInvalidException("Coupon is no longer valid: " + code);
            }
            coupon.setUsedCount(coupon.getUsedCount() + 1);
            repo.save(coupon);
            return new DiscountResponse(coupon.getCode(), calculateDiscount(coupon, subtotal));
        }

        String normalizedOperationId = operationId.trim();
        var existing = redemptionRepo.findById(normalizedOperationId);
        if (existing.isPresent()) {
            CouponRedemption redemption = existing.get();
            if (!redemption.getCouponCode().equalsIgnoreCase(coupon.getCode())) {
                throw new IllegalArgumentException("Coupon operation id is already associated with another coupon");
            }
            if (redemption.getReleasedAt() != null) {
                // A compensation for this operation has already been recorded.
                // Never allow a late/ambiguous commit to consume a second use.
                throw new CouponInvalidException("Coupon redemption operation was already released");
            }
            if (redemption.getSubtotal().compareTo(subtotal) != 0) {
                throw new IllegalArgumentException(
                        "Coupon operation id was already used for a different subtotal");
            }
            return new DiscountResponse(coupon.getCode(), redemption.getDiscountAmount());
        }

        if (!coupon.isCurrentlyRedeemable(Instant.now())) {
            throw new CouponInvalidException("Coupon is no longer valid: " + code);
        }
        BigDecimal discount = calculateDiscount(coupon, subtotal);
        coupon.setUsedCount(coupon.getUsedCount() + 1);
        repo.save(coupon);
        redemptionRepo.save(new CouponRedemption(normalizedOperationId, coupon.getCode(), subtotal, discount));
        return new DiscountResponse(coupon.getCode(), discount);
    }

    /**
     * Compensation: called by checkout-svc's outbox when an order that had
     * already committed a coupon use ends up failing/cancelled downstream
     * (e.g. payment declined) — the customer shouldn't lose a redemption for
     * an order that never went through.
     */
    @Transactional
    public void release(String code) {
        releaseInternal(code, null);
    }

    /** Idempotent compensation path used by checkout-svc/outbox. */
    @Transactional
    public void release(String code, String operationId) {
        releaseInternal(code, operationId);
    }

    private void releaseInternal(String code, String operationId) {
        if (operationId == null || operationId.isBlank()) {
            repo.findByCodeIgnoreCaseForUpdate(code).ifPresent(c -> {
                c.setUsedCount(Math.max(0, c.getUsedCount() - 1));
                repo.save(c);
            });
            return;
        }

        String normalizedOperationId = operationId.trim();

        // A replay after a successful release is already complete. Check the
        // operation ledger first so a retry remains successful even if the
        // coupon record is later cleaned up/deactivated.
        CouponRedemption record = redemptionRepo.findById(normalizedOperationId).orElse(null);
        if (record != null && record.getReleasedAt() != null) {
            if (!record.getCouponCode().equalsIgnoreCase(code)) {
                throw new IllegalArgumentException("Coupon operation id belongs to another coupon");
            }
            return;
        }

        // Lock the coupon before deciding that a still-unreleased operation is
        // unknown. This serializes release with a concurrent commit for the
        // same coupon. Re-read the operation after taking the lock because a
        // commit may have become visible between the first lookup and the
        // lock acquisition.
        Coupon coupon = repo.findByCodeIgnoreCaseForUpdate(code)
                .orElseThrow(() -> new CouponInvalidException("Coupon code not found: " + code));
        record = redemptionRepo.findById(normalizedOperationId).orElse(null);
        if (record != null) {
            if (!record.getCouponCode().equalsIgnoreCase(coupon.getCode())) {
                throw new IllegalArgumentException("Coupon operation id belongs to another coupon");
            }
            if (record.getReleasedAt() != null) {
                return;
            }

            coupon.setUsedCount(Math.max(0, coupon.getUsedCount() - 1));
            repo.save(coupon);
            record.setReleasedAt(Instant.now());
            redemptionRepo.save(record);
            return;
        }

        // Release can arrive before the original commit is visible (e.g. a
        // timeout after the commit's downstream request). Persist a zero-use
        // tombstone so the late commit is rejected rather than consuming a
        // coupon after its compensation has already been requested.
        CouponRedemption tombstone = new CouponRedemption(
                normalizedOperationId, coupon.getCode(), BigDecimal.ZERO, BigDecimal.ZERO);
        tombstone.setReleasedAt(Instant.now());
        redemptionRepo.save(tombstone);
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
        CouponResponse response = new CouponResponse();

        response.setId(c.getId());
        response.setCode(c.getCode());
        response.setDiscountType(c.getDiscountType());
        response.setDiscountValue(c.getDiscountValue());
        response.setMaxUses(c.getMaxUses());
        response.setUsedCount(c.getUsedCount());
        response.setExpiresAt(c.getExpiresAt());
        response.setActive(c.isActive());
        response.setCreatedAt(c.getCreatedAt());

        return response;
    }
}
