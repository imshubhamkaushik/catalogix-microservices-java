package com.catalogix.promotions.svc;

import com.catalogix.promotions.dto.CreateCouponRequest;
import com.catalogix.promotions.dto.DiscountResponse;
import com.catalogix.promotions.exception.CouponInvalidException;
import com.catalogix.promotions.model.Coupon;
import com.catalogix.promotions.model.DiscountType;
import com.catalogix.promotions.repository.CouponRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
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

    // ---- preview (unlocked, read-only) ----

    @Test
    void previewReturnsDiscountForRedeemableCoupon() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 0, null, true);
        when(repo.findByCodeIgnoreCase("TEST10")).thenReturn(Optional.of(c));

        DiscountResponse resp = svc.preview("TEST10", new BigDecimal("200.00"));

        assertEquals("TEST10", resp.getCode());
        assertEquals(new BigDecimal("20.00"), resp.getDiscountAmount());
        // preview() is read-only — it must never touch usedCount or the locked read path.
        verify(repo, never()).save(any());
        verify(repo, never()).findByCodeIgnoreCaseForUpdate(any());
    }

    @Test
    void previewRejectsUnknownCode() {
        when(repo.findByCodeIgnoreCase("NOPE")).thenReturn(Optional.empty());
        assertThrows(CouponInvalidException.class, () -> svc.preview("NOPE", BigDecimal.TEN));
    }

    @Test
    void previewRejectsInactiveCoupon() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 0, null, false);
        when(repo.findByCodeIgnoreCase("TEST10")).thenReturn(Optional.of(c));
        assertThrows(CouponInvalidException.class, () -> svc.preview("TEST10", BigDecimal.TEN));
    }

    @Test
    void previewRejectsExpiredCoupon() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 0, Instant.now().minusSeconds(60), true);
        when(repo.findByCodeIgnoreCase("TEST10")).thenReturn(Optional.of(c));
        assertThrows(CouponInvalidException.class, () -> svc.preview("TEST10", BigDecimal.TEN));
    }

    @Test
    void previewRejectsExhaustedCoupon() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", 5, 5, null, true);
        when(repo.findByCodeIgnoreCase("TEST10")).thenReturn(Optional.of(c));
        assertThrows(CouponInvalidException.class, () -> svc.preview("TEST10", BigDecimal.TEN));
    }

    @Test
    void previewAllowsCouponBelowMaxUses() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", 5, 4, null, true);
        when(repo.findByCodeIgnoreCase("TEST10")).thenReturn(Optional.of(c));
        assertDoesNotThrow(() -> svc.preview("TEST10", BigDecimal.TEN));
    }

    // ---- commit (locked, mutates usedCount) ----

    @Test
    void commitIncrementsUsedCountAndReturnsDiscount() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 3, null, true);
        when(repo.findByCodeIgnoreCaseForUpdate("TEST10")).thenReturn(Optional.of(c));

        DiscountResponse resp = svc.commit("TEST10", new BigDecimal("200.00"));

        assertEquals(new BigDecimal("20.00"), resp.getDiscountAmount());
        assertEquals(4, c.getUsedCount());
        verify(repo).save(c);
    }

    @Test
    void commitUsesTheLockedReadNotTheUnlockedOne() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 0, null, true);
        when(repo.findByCodeIgnoreCaseForUpdate("TEST10")).thenReturn(Optional.of(c));

        svc.commit("TEST10", BigDecimal.TEN);

        verify(repo, never()).findByCodeIgnoreCase(any());
    }

    @Test
    void commitRejectsUnknownCodeAndDoesNotSave() {
        when(repo.findByCodeIgnoreCaseForUpdate("NOPE")).thenReturn(Optional.empty());
        assertThrows(CouponInvalidException.class, () -> svc.commit("NOPE", BigDecimal.TEN));
        verify(repo, never()).save(any());
    }

    @Test
    void commitRejectsExhaustedCouponAndDoesNotSave() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", 5, 5, null, true);
        when(repo.findByCodeIgnoreCaseForUpdate("TEST10")).thenReturn(Optional.of(c));

        assertThrows(CouponInvalidException.class, () -> svc.commit("TEST10", BigDecimal.TEN));
        verify(repo, never()).save(any());
    }

    // ---- release (compensation) ----

    @Test
    void releaseDecrementsUsedCount() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 3, null, true);
        when(repo.findByCodeIgnoreCaseForUpdate("TEST10")).thenReturn(Optional.of(c));

        svc.release("TEST10");

        assertEquals(2, c.getUsedCount());
        verify(repo).save(c);
    }

    @Test
    void releaseNeverGoesBelowZero() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 0, null, true);
        when(repo.findByCodeIgnoreCaseForUpdate("TEST10")).thenReturn(Optional.of(c));

        svc.release("TEST10");

        assertEquals(0, c.getUsedCount());
    }

    @Test
    void releaseIsANoOpWhenCodeDoesNotExist() {
        when(repo.findByCodeIgnoreCaseForUpdate("NOPE")).thenReturn(Optional.empty());

        assertDoesNotThrow(() -> svc.release("NOPE"));
        verify(repo, never()).save(any());
    }

    // ---- calculateDiscount (exercised indirectly above, plus edge cases) ----

    @Test
    void calculateDiscountForFixedAmountNeverExceedsSubtotal() {
        Coupon c = coupon(DiscountType.FIXED_AMOUNT, "500", null, 0, null, true);
        when(repo.findByCodeIgnoreCase("TEST10")).thenReturn(Optional.of(c));

        DiscountResponse resp = svc.preview("TEST10", new BigDecimal("200.00"));

        assertEquals(new BigDecimal("200.00"), resp.getDiscountAmount());
    }

    // ---- admin management ----

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

    @Test
    void listAllReturnsEveryCouponMapped() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 0, null, true);
        when(repo.findAll()).thenReturn(List.of(c));

        List<com.catalogix.promotions.dto.CouponResponse> resp = svc.listAll();

        assertEquals(1, resp.size());
        assertEquals("TEST10", resp.get(0).getCode());
    }

    @Test
    @SuppressWarnings("null")
    void deactivateSetsActiveFalse() {
        Coupon c = coupon(DiscountType.PERCENTAGE, "10", null, 0, null, true);
        when(repo.findById(1L)).thenReturn(Optional.of(c));
        when(repo.save(any(Coupon.class))).thenAnswer(inv -> inv.getArgument(0));

        var resp = svc.deactivate(1L);

        assertFalse(resp.isActive());
    }

    @Test
    void deactivateRejectsUnknownId() {
        when(repo.findById(99L)).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class, () -> svc.deactivate(99L));
    }
}
