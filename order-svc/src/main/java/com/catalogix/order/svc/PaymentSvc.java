package com.catalogix.order.svc;

import com.catalogix.order.dto.PayOrderRequest;
import com.catalogix.order.dto.PaymentResponse;
import com.catalogix.order.model.Payment;
import com.catalogix.order.model.PaymentStatus;
import com.catalogix.order.repository.PaymentRepository;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.security.SecureRandom;

/**
 * A MOCK payment processor — no real card network, gateway, or money is
 * involved anywhere here. Every attempt succeeds, EXCEPT card number "0000"
 * (used by tests/demos to exercise the decline path) so the failure branch
 * of OrderSvc.payOrder is actually reachable without needing a real gateway
 * sandbox.
 */
@Service
public class PaymentSvc {

    private static final SecureRandom RANDOM = new SecureRandom();
    private static final String SIMULATED_DECLINE_CARD = "0000";

    private final PaymentRepository repo;

    public PaymentSvc(PaymentRepository repo) {
        this.repo = repo;
    }

    @Transactional
    public PaymentResponse process(Long orderId, BigDecimal amount, PayOrderRequest req) {
        boolean simulateFailure = SIMULATED_DECLINE_CARD.equals(req.getCardLast4());
        PaymentStatus status = simulateFailure ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED;
        String reference = "MOCK-" + System.currentTimeMillis() + "-" + RANDOM.nextInt(1_000_000);

        Payment payment = new Payment(orderId, amount, req.getMethod(), status, reference);
        return toResponse(repo.save(payment));
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(
                p.getId(), p.getOrderId(), p.getAmount(), p.getMethod(), p.getStatus(), p.getReference(), p.getCreatedAt());
    }
}
