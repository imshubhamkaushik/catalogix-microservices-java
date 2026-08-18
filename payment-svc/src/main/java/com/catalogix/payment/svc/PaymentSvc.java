package com.catalogix.payment.svc;

import com.catalogix.payment.dto.PaymentResponse;
import com.catalogix.payment.dto.ProcessPaymentRequest;
import com.catalogix.payment.dto.ProcessRefundRequest;
import com.catalogix.payment.dto.RefundResponse;
import com.catalogix.payment.exception.DeclinedException;
import com.catalogix.payment.exception.NoSuchPaymentException;
import com.catalogix.payment.model.Payment;
import com.catalogix.payment.model.PaymentMethod;
import com.catalogix.payment.model.PaymentStatus;
import com.catalogix.payment.model.Refund;
import com.catalogix.payment.repository.PaymentRepository;
import com.catalogix.payment.repository.RefundRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.UUID;

/**
 * Mock payment processing across three methods. CARD is extracted verbatim
 * (same decline convention — cardLast4 "0000" simulates a hard decline)
 * from the original order-svc PaymentSvc. UPI and COD were added
 * afterward, each with real e-commerce behavior even though nothing here
 * talks to an actual card network or UPI switch:
 *
 * - CARD and UPI both capture payment now, synchronously (real UPI is a
 *   two-phase initiate-then-callback flow; this app models it as
 *   synchronous, matching the "mock, but honest about what it mocks"
 *   sophistication level CARD already had rather than introducing a new
 *   async pattern for one payment method).
 * - COD captures nothing: no decline path exists for it at all, since
 *   there's no real payment attempt to decline yet — it always succeeds
 *   into COD_PENDING, capped at MAX_COD_AMOUNT the same way real
 *   storefronts cap high-value orders out of COD eligibility.
 */
@Service
public class PaymentSvc {

    private final PaymentRepository repo;
    private final RefundRepository refundRepo;

    // Real storefronts cap COD by order value for fraud/logistics risk —
    // ₹50,000 is a reasonable, clearly-fictional round number for this
    // mock, not sourced from any real platform's actual policy.
    private static final BigDecimal MAX_COD_AMOUNT = new BigDecimal("50000");

    public PaymentSvc(PaymentRepository repo, RefundRepository refundRepo) {
        this.repo = repo;
        this.refundRepo = refundRepo;
    }

    // noRollbackFor is the whole point here: DeclinedException is an
    // expected business outcome, not an error, and the failed-attempt row
    // saved just below is exactly what an audit trail is for. Without this,
    // Spring's default behavior rolls back the entire method on any
    // unchecked exception — which would silently discard the very save()
    // this method makes right before throwing. (notification-svc's EmailSvc
    // has the identical shape and already carries the same annotation for
    // the same reason — see its Javadoc.)
    @Transactional(noRollbackFor = DeclinedException.class)
    public PaymentResponse process(ProcessPaymentRequest req, Long requestedByUserId) {
        Payment payment = switch (req.getMethod()) {
            case CARD -> processCard(req, requestedByUserId);
            case UPI -> processUpi(req, requestedByUserId);
            case COD -> processCod(req, requestedByUserId);
        };

        Payment saved = repo.save(payment);

        if (saved.getStatus() == PaymentStatus.FAILED) {
            throw new DeclinedException(declineMessage(req.getMethod()));
        }

        return toResponse(saved);
    }

    // Called only by checkout-svc's ReturnSvc, only for CARD/UPI orders —
    // COD is filtered out before this is ever reached (see ReturnSvc's
    // Javadoc), since a COD order never has a SUCCEEDED Payment row to find here.
    @Transactional
    public RefundResponse refund(ProcessRefundRequest req) {
        Payment original = repo.findByOrderIdOrderByCreatedAtDesc(req.getOrderId()).stream()
                .filter(p -> p.getStatus() == PaymentStatus.SUCCEEDED)
                .max(Comparator.comparing(Payment::getCreatedAt))
                .orElseThrow(() -> new NoSuchPaymentException(req.getOrderId()));

        BigDecimal alreadyRefunded = refundRepo.findByOrderId(req.getOrderId()).stream()
                .map(Refund::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);

        if (alreadyRefunded.add(req.getAmount()).compareTo(original.getAmount()) > 0) {
            throw new IllegalArgumentException(
                    "Refund of " + req.getAmount() + " would exceed the original payment of "
                    + original.getAmount() + " (" + alreadyRefunded + " already refunded)");
        }

        Refund refund = new Refund(req.getOrderId(), original.getId(), req.getAmount(),
                "MOCK-REFUND-" + UUID.randomUUID());
        Refund saved = refundRepo.save(refund);
        return new RefundResponse(saved.getId(), saved.getOrderId(), saved.getAmount(),
                saved.getReference(), saved.getCreatedAt());
    }

    private Payment processCard(ProcessPaymentRequest req, Long requestedByUserId) {
        if (req.getCardLast4() == null || req.getCardLast4().isBlank()) {
            throw new IllegalArgumentException("cardLast4 is required when method is CARD");
        }
        boolean declined = "0000".equals(req.getCardLast4());
        return new Payment(req.getOrderId(), requestedByUserId, req.getAmount(), PaymentMethod.CARD,
                declined ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED,
                declined ? null : "MOCK-CARD-" + UUID.randomUUID());
    }

    private Payment processUpi(ProcessPaymentRequest req, Long requestedByUserId) {
        if (req.getUpiId() == null || req.getUpiId().isBlank()) {
            throw new IllegalArgumentException("upiId is required when method is UPI");
        }
        boolean declined = req.getUpiId().toLowerCase().startsWith("fail@");
        return new Payment(req.getOrderId(), requestedByUserId, req.getAmount(), PaymentMethod.UPI,
                declined ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED,
                declined ? null : "MOCK-UPI-" + UUID.randomUUID());
    }

    private Payment processCod(ProcessPaymentRequest req, Long requestedByUserId) {
        if (req.getAmount().compareTo(MAX_COD_AMOUNT) > 0) {
            throw new IllegalArgumentException(
                    "Cash on Delivery is not available for orders over " + MAX_COD_AMOUNT);
        }
        // No decline path: there's no payment attempt to decline yet. The
        // order is confirmed now; the money (or lack of it) gets sorted out
        // at the doorstep, outside this system's view entirely.
        return new Payment(req.getOrderId(), requestedByUserId, req.getAmount(), PaymentMethod.COD,
                PaymentStatus.COD_PENDING, null);
    }

    private String declineMessage(PaymentMethod method) {
        return switch (method) {
            case CARD -> "Payment declined by issuer";
            case UPI -> "UPI payment declined";
            case COD -> throw new IllegalStateException("COD never declines"); // unreachable — see processCod
        };
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(p.getId(), p.getOrderId(), p.getAmount(), p.getMethod(),
                p.getStatus(), p.getReference(), p.getCreatedAt());
    }
}
