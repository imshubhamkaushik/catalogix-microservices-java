package com.catalogix.seller.svc;

import com.catalogix.seller.dto.SellerSummary;
import com.catalogix.seller.model.*;
import com.catalogix.seller.repository.*;
import org.junit.jupiter.api.*;
import org.mockito.*;
import java.math.BigDecimal;
import java.util.*;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class SellerSvcTest {
    @Mock
    SellerProfileRepository profiles;
    @Mock
    SellerLedgerRepository ledger;
    @Mock
    PayoutRepository payouts;
    @Mock
    org.springframework.context.ApplicationEventPublisher events;
    SellerSvc svc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new SellerSvc(profiles, ledger, payouts, events);
    }

    @Test
    void payoutUsesLockedSellerProfileAndLeavesNoNegativeBalance() {
        SellerProfile p = new SellerProfile();
        p.setUserId(42L);
        p.setDisplayName("Seller");
        p.setStatus(SellerStatus.APPROVED);
        when(profiles.findByUserIdForUpdate(42L)).thenReturn(Optional.of(p));
        when(ledger.balance(42L)).thenReturn(new BigDecimal("125.00"), new BigDecimal("25.00"));
        when(ledger.grossSales(42L)).thenReturn(BigDecimal.ZERO);
        when(payouts.findBySellerUserIdOrderByCreatedAtDesc(42L)).thenReturn(List.of());
        when(payouts.findByIdempotencyKey("payout-key-1")).thenReturn(Optional.empty());
        when(payouts.save(any(Payout.class))).thenAnswer(i -> i.getArgument(0));
        SellerSummary result = svc.requestPayout(42L, new BigDecimal("100.129"), "payout-key-1");
        assertEquals(new BigDecimal("25.00"), result.availableBalance());
        verify(profiles).findByUserIdForUpdate(42L);
        verify(ledger).save(any(SellerLedgerEntry.class));
    }

    @Test
    void payoutRejectsInsufficientBalance() {
        SellerProfile p = new SellerProfile();
        p.setUserId(42L);
        p.setDisplayName("Seller");
        p.setStatus(SellerStatus.APPROVED);
        when(profiles.findByUserIdForUpdate(42L)).thenReturn(Optional.of(p));
        when(ledger.balance(42L)).thenReturn(new BigDecimal("20.00"));
        when(payouts.findByIdempotencyKey("payout-key-2")).thenReturn(Optional.empty());
        assertThrows(IllegalArgumentException.class,
                () -> svc.requestPayout(42L, new BigDecimal("21.00"), "payout-key-2"));
        verify(payouts, never()).save(any());
    }
}
