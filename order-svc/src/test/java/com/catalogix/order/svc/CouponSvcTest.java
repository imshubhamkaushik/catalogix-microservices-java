package com.catalogix.order.svc;

import com.catalogix.order.dto.CreateCouponRequest;
import com.catalogix.order.exception.CouponInvalidException;
import com.catalogix.order.model.Coupon;
import com.catalogix.order.model.DiscountType;
import com.catalogix.order.repository.CouponRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SuppressWarnings("null")
class CouponSvcTest {

    @Mock private CouponRepository repo;

    @InjectMocks
    private CouponSvc svc;

    private static final String COUPON_TEST10 = "TEST10";
    private static final String DISCOUNT_10 = "10";
    private static final String SUBTOTAL_200 = "200.00";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
    }

    private Coupon coupon(DiscountType type, String value, Integer maxUses, int usedCount, Instant expiresAt, boolean active) {
        Coupon c = new Coupon();
        c.setCode(COUPON_TEST10);
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
        Coupon c = coupon(DiscountType.PERCENTAGE, DISCOUNT_10, null, 0, null, true);
        when(repo.findByCodeIgnoreCase(COUPON_TEST10)).thenReturn(Optional.of(c));

        assertEquals(c, svc.validate(COUPON_TEST10));
    }

    @Test
    void validateRejectsUnknownCode() {
        when(repo.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());
        assertThrows(CouponInvalidException.class, () -> svc.validate("NOPE"));
    }

    @Test
    void validateRejectsInactiveCoupon() {
        Coupon c = coupon(DiscountType.PERCENTAGE, DISCOUNT_10, null, 0, null, false);
        when(repo.findByCodeIgnoreCase(COUPON_TEST10)).thenReturn(Optional.of(c));
        assertThrows(CouponInvalidException.class, () -> svc.validate(COUPON_TEST10));
    }

    @Test
    void validateRejectsExpiredCoupon() {
        Coupon c = coupon(DiscountType.PERCENTAGE, DISCOUNT_10, null, 0, Instant.now().minusSeconds(60), true);
        when(repo.findByCodeIgnoreCase(COUPON_TEST10)).thenReturn(Optional.of(c));
        assertThrows(CouponInvalidException.class, () -> svc.validate(COUPON_TEST10));
    }

    @Test
    void validateRejectsExhaustedCoupon() {
        Coupon c = coupon(DiscountType.PERCENTAGE, DISCOUNT_10, 5, 5, null, true);
        when(repo.findByCodeIgnoreCase(COUPON_TEST10)).thenReturn(Optional.of(c));
        assertThrows(CouponInvalidException.class, () -> svc.validate(COUPON_TEST10));
    }

    @Test
    void validateAllowsCouponBelowMaxUses() {
        Coupon c = coupon(DiscountType.PERCENTAGE, DISCOUNT_10, 5, 4, null, true);
        when(repo.findByCodeIgnoreCase(COUPON_TEST10)).thenReturn(Optional.of(c));
        assertDoesNotThrow(() -> svc.validate(COUPON_TEST10));
    }

    @Test
    void calculateDiscountForPercentage() {
        Coupon c = coupon(DiscountType.PERCENTAGE, DISCOUNT_10, null, 0, null, true);
        assertEquals(new BigDecimal("20.00"), svc.calculateDiscount(c, new BigDecimal(SUBTOTAL_200)));
    }

    @Test
    void calculateDiscountForFixedAmount() {
        Coupon c = coupon(DiscountType.FIXED_AMOUNT, "50", null, 0, null, true);
        assertEquals(new BigDecimal("50.00"), svc.calculateDiscount(c, new BigDecimal(SUBTOTAL_200)));
    }

    @Test
    void calculateDiscountNeverExceedsSubtotal() {
        Coupon c = coupon(DiscountType.FIXED_AMOUNT, "500", null, 0, null, true);
        assertEquals(new BigDecimal(SUBTOTAL_200), svc.calculateDiscount(c, new BigDecimal(SUBTOTAL_200)));
    }

    @Test
    void recordUsageIncrementsUsedCount() {
        Coupon c = coupon(DiscountType.PERCENTAGE, DISCOUNT_10, null, 3, null, true);
        svc.recordUsage(c);
        assertEquals(4, c.getUsedCount());
        verify(repo).save(c);
    }

    @Test
    void releaseUsageDecrementsUsedCountButNeverBelowZero() {
        Coupon c = coupon(DiscountType.PERCENTAGE, DISCOUNT_10, null, 0, null, true);
        when(repo.findByCodeIgnoreCase(COUPON_TEST10)).thenReturn(Optional.of(c));

        svc.releaseUsage(COUPON_TEST10);

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