package com.catalogix.seller.svc;

import com.catalogix.seller.dto.*;
import com.catalogix.seller.event.OrderConfirmedEvent;
import com.catalogix.seller.event.OrderItemEventData;
import com.catalogix.seller.event.SellerDomainEvent;
import com.catalogix.seller.model.*;
import com.catalogix.seller.repository.*;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.*;

@Service
public class SellerSvc {
    private static final String SALE_SUFFIX = ":sale";
    private static final String COMMISSION_SUFFIX = ":commission";

    private final SellerProfileRepository profiles;
    private final SellerLedgerRepository ledger;
    private final PayoutRepository payouts;
    private final ApplicationEventPublisher events;

    public SellerSvc(SellerProfileRepository p, SellerLedgerRepository l, PayoutRepository o,
            ApplicationEventPublisher events) {
        profiles = p;
        ledger = l;
        payouts = o;
        this.events = events;
    }

    @Transactional
    public SellerSummary onboard(Long userId, OnboardingRequest req) {
        SellerProfile p = profiles.findByUserId(userId).orElseGet(() -> {
            SellerProfile x = new SellerProfile();
            x.setUserId(userId);
            return x;
        });
        p.setDisplayName(req.displayName().trim());
        p.setBusinessName(blankToNull(req.businessName()));
        p.setDescription(blankToNull(req.description()));
        SellerProfile saved = profiles.save(p);
        events.publishEvent(
                new SellerDomainEvent("onboarded", userId, saved.getId(), saved.getStatus(), null, null,
                        Instant.now()));
        return summary(saved);
    }

    @Transactional(readOnly = true)
    public SellerSummary me(Long userId) {
        return findByUser(userId);
    }

    @Transactional(readOnly = true)
    public SellerSummary byUser(Long userId) {
        return findByUser(userId);
    }

    private SellerSummary findByUser(Long userId) {
        return profiles.findByUserId(userId).map(this::summary).orElse(null);
    }

    @Transactional
    public SellerSummary setStatus(Long id, SellerStatus status) {
        SellerProfile p = profiles.findById(id).orElseThrow();
        p.setStatus(status);
        SellerProfile saved = profiles.save(p);
        events.publishEvent(new SellerDomainEvent("status-changed", saved.getUserId(), saved.getId(), saved.getStatus(),
                null, null, Instant.now()));
        return summary(saved);
    }

    @Transactional(readOnly = true)
    public List<SellerSummary> list(SellerStatus status) {
        return (status == null ? profiles.findAll() : profiles.findByStatus(status)).stream().map(this::summary)
                .toList();
    }

    @Transactional
    public SellerSummary requestPayout(Long userId, BigDecimal amount, String idempotencyKey) {
        if (idempotencyKey == null || idempotencyKey.isBlank() || idempotencyKey.length() > 80)
            throw new IllegalArgumentException("Idempotency-Key is required and must be at most 80 characters");
        SellerProfile p = profiles.findByUserIdForUpdate(userId).orElseThrow();
        var existing = payouts.findByIdempotencyKey(idempotencyKey);
        if (existing.isPresent()) {
            if (!existing.get().getSellerUserId().equals(userId)
                    || existing.get().getAmount().compareTo(amount.setScale(2, java.math.RoundingMode.HALF_UP)) != 0)
                throw new IllegalArgumentException("Idempotency-Key was already used for a different payout");
            return summary(p);
        }
        if (p.getStatus() != SellerStatus.APPROVED)
            throw new IllegalStateException("Seller is not approved");
        BigDecimal requested = amount.setScale(2, java.math.RoundingMode.HALF_UP);
        if (requested.signum() <= 0)
            throw new IllegalArgumentException("Payout amount must be positive");
        BigDecimal balance = ledger.balance(userId);
        if (balance.compareTo(requested) < 0)
            throw new IllegalArgumentException("Insufficient available seller balance");
        String ref = "PAYOUT-" + UUID.randomUUID().toString().substring(0, 12).toUpperCase(Locale.ROOT);
        Payout payout = payouts.save(new Payout(userId, requested, ref, idempotencyKey));
        ledger.save(new SellerLedgerEntry(userId, LedgerType.PAYOUT_DEBIT, requested, ref, "Marketplace payout"));
        payout.complete();
        events.publishEvent(
                new SellerDomainEvent("payout-completed", userId, p.getId(), p.getStatus(), requested, ref,
                        Instant.now()));
        return summary(p);
    }

    @Transactional
    public void creditSale(OrderConfirmedEvent ev) {
        if (ev == null || ev.items() == null)
            return;
        for (var item : ev.items()) {
            if (item.sellerId() == null || item.subtotal() == null)
                continue;
            SellerProfile p = profiles.findByUserId(item.sellerId()).orElseGet(() -> defaultProfile(item.sellerId()));
            BigDecimal commission = item.subtotal().multiply(p.getCommissionRate()).divide(new BigDecimal("100"), 2,
                    java.math.RoundingMode.HALF_UP);
            String base = ev.orderId() + ":" + item.productId();
            if (ledger.findBySourceKey(base + SALE_SUFFIX).isEmpty())
                ledger.save(
                        new SellerLedgerEntry(item.sellerId(), LedgerType.SALE_CREDIT, item.subtotal(), base + SALE_SUFFIX,
                                "Order " + ev.orderId() + " sale"));
            if (ledger.findBySourceKey(base + COMMISSION_SUFFIX).isEmpty())
                ledger.save(new SellerLedgerEntry(item.sellerId(), LedgerType.COMMISSION_DEBIT, commission,
                        base + COMMISSION_SUFFIX, "Marketplace commission"));
        }
    }

    @Transactional
    public void reverseSale(com.catalogix.seller.event.OrderCancelledEvent ev) {
        if (ev == null || ev.items() == null)
            return;
        reverseItems(ev.orderId(), ev.items(), "order-cancelled");
    }

    @Transactional
    public void reverseSale(com.catalogix.seller.event.ReturnRefundedEvent ev) {
        if (ev == null || ev.items() == null)
            return;
        reverseItems(ev.orderId(), ev.items(), "return-refunded:" + ev.returnId());
    }

    private void reverseItems(Long orderId, List<OrderItemEventData> items, String reason) {
        for (var item : items) {
            if (item.sellerId() == null || item.productId() == null)
                continue;
            String saleKey = orderId + ":" + item.productId() + SALE_SUFFIX;
            String commissionKey = orderId + ":" + item.productId() + COMMISSION_SUFFIX;
            var sale = ledger.findBySourceKey(saleKey);
            var commission = ledger.findBySourceKey(commissionKey);
            if (sale.isPresent() && ledger.findBySourceKey(saleKey + ":reverse:" + reason).isEmpty())
                ledger.save(new SellerLedgerEntry(item.sellerId(), LedgerType.SALE_REVERSAL, sale.get().getAmount(),
                        saleKey + ":reverse:" + reason, "Reversed sale for " + reason));
            if (commission.isPresent() && ledger.findBySourceKey(commissionKey + ":refund:" + reason).isEmpty())
                ledger.save(new SellerLedgerEntry(item.sellerId(), LedgerType.COMMISSION_REFUND,
                        commission.get().getAmount(),
                        commissionKey + ":refund:" + reason, "Reversed commission for " + reason));
        }
    }

    private SellerProfile defaultProfile(Long userId) {
        SellerProfile p = new SellerProfile();
        p.setUserId(userId);
        p.setDisplayName("Seller " + userId);
        p.setStatus(SellerStatus.APPROVED);
        return profiles.save(p);
    }

    private SellerSummary summary(SellerProfile p) {
        return new SellerSummary(p.getId(), p.getUserId(), p.getDisplayName(), p.getBusinessName(), p.getDescription(),
                p.getStatus(), p.getCommissionRate(), ledger.balance(p.getUserId()), ledger.grossSales(p.getUserId()),
                payouts.findBySellerUserIdOrderByCreatedAtDesc(p.getUserId()).stream()
                        .map(x -> new SellerSummary.PayoutView(x.getId(), x.getAmount(), x.getStatus(),
                                x.getReference(),
                                x.getCreatedAt(), x.getCompletedAt()))
                        .toList(),
                p.getCreatedAt());
    }

    private static String blankToNull(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }
}
