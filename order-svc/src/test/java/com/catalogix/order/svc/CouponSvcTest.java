package com.catalogix.order.svc;

import com.catalogix.order.dto.CreateCouponRequest;
import com.catalogix.order.exception.CouponInvalidException;
import com.catalogix.order.model.Coupon;
import com.catalogix.order.model.DiscountType;
import com.catalogix.order.repository.CouponRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class CouponSvcTest {

    @Mock private CouponRepository repo;

    private CouponSvc svc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new CouponSvc(repo);
    }

    private Coupon coupon(DiscountType type, String value, Integer maxUses, int usedCount, Instant expiresAt, boolean active) {
        Coupon c = new Coupon();
        c.setCode("TEST10");
        c.setDiscountType(type);
        c.setDiscountValue(new BigDecimal(value));
        c.setMaxUses(maxUses);
        c.setUsedCount(usedCount);
        c.setExpiresAt(expiresAt);
        c.setActive(active);
        return c;
    }

    @Test
    void validateReturnsCouponWhenRedeemable() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 0, null, true);
        when(repo.findByCodeIgnoreCase("TEST10")).thenReturn(Optional.of(c));

        assertEquals(c, svc.validate("TEST10"));
    }

    @Test
    void validateRejectsUnknownCode() {
        when(repo.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());
        assertThrows(CouponInvalidException.class, () -> svc.validate("NOPE"));
    }

    @Test
    void validateRejectsInactiveCoupon() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 0, null, false);
        when(repo.findByCodeIgnoreCase("TEST10")).thenReturn(Optional.of(c));
        assertThrows(CouponInvalidException.class, () -> svc.validate("TEST10"));
    }

    @Test
    void validateRejectsExpiredCoupon() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 0, Instant.now().minusSeconds(60), true);
        when(repo.findByCodeIgnoreCase("TEST10")).thenReturn(Optional.of(c));
        assertThrows(CouponInvalidException.class, () -> svc.validate("TEST10"));
    }

    @Test
    void validateRejectsExhaustedCoupon() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", 5, 5, null, true);
        when(repo.findByCodeIgnoreCase("TEST10")).thenReturn(Optional.of(c));
        assertThrows(CouponInvalidException.class, () -> svc.validate("TEST10"));
    }

    @Test
    void validateAllowsCouponBelowMaxUses() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", 5, 4, null, true);
        when(repo.findByCodeIgnoreCase("TEST10")).thenReturn(Optional.of(c));
        assertDoesNotThrow(() -> svc.validate("TEST10"));
    }

    @Test
    void calculateDiscountForPercentage() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 0, null, true);
        assertEquals(new BigDecimal("20.00"), svc.calculateDiscount(c, new BigDecimal("200.00")));
    }

    @Test
    void calculateDiscountForFixedAmount() {
        Coupon c = coupon(DiscountType.FIXED_AMOUNT, "50", null, 0, null, true);
        assertEquals(new BigDecimal("50.00"), svc.calculateDiscount(c, new BigDecimal("200.00")));
    }

    @Test
    void calculateDiscountNeverExceedsSubtotal() {
        Coupon c = coupon(DiscountType.FIXED_AMOUNT, "500", null, 0, null, true);
        assertEquals(new BigDecimal("200.00"), svc.calculateDiscount(c, new BigDecimal("200.00")));
    }

    @Test
    void recordUsageIncrementsUsedCount() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 3, null, true);
        svc.recordUsage(c);
        assertEquals(4, c.getUsedCount());
        verify(repo).save(c);
    }

    @Test
    void releaseUsageDecrementsUsedCountButNeverBelowZero() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 0, null, true);
        when(repo.findByCodeIgnoreCase("TEST10")).thenReturn(Optional.of(c));

        svc.releaseUsage("TEST10");

        assertEquals(0, c.getUsedCount());
        verify(repo).save(c);
    }

    @Test
    void createRejectsDuplicateCode() {
        when(repo.findByCodeIgnoreCase("DUP10")).thenReturn(Optional.of(new Coupon()));

        CreateCouponRequest req = new CreateCouponRequest();
        req.setCode("DUP10");
        req.setDiscountType(DiscountType.PERCENTAGE);
        req.setDiscountValue(BigDecimal.TEN);

        assertThrows(IllegalArgumentException.class, () -> svc.create(req));
        verify(repo, never()).save(any());
    }

    @Test
    @SuppressWarnings("null")
    void createUppercasesTheCode() {
        when(repo.findByCodeIgnoreCase("save10")).thenReturn(Optional.empty());
        when(repo.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));

        CreateCouponRequest req = new CreateCouponRequest();
        req.setCode("save10");
        req.setDiscountType(DiscountType.PERCENTAGE);
        req.setDiscountValue(BigDecimal.TEN);

        assertEquals("SAVE10", svc.create(req).getCode());
    }
}
