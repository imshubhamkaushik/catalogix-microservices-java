package com.catalogix.payment.svc;

import com.catalogix.payment.dto.PaymentResponse;
import com.catalogix.payment.dto.ProcessPaymentRequest;
import com.catalogix.payment.exception.DeclinedException;
import com.catalogix.payment.model.Payment;
import com.catalogix.payment.model.PaymentStatus;
import com.catalogix.payment.repository.PaymentRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

/**
 * Mock payment processing, extracted verbatim (same decline convention —
 * cardLast4 "0000" simulates a hard decline) from the original order-svc
 * PaymentSvc. The behavior is unchanged; what changed is the boundary: this
 * is now the one and only place in the system that ever sees a "card", and
 * checkout-svc calls it exactly the way it used to call this logic in-process.
 */
@Service
public class PaymentSvc {

    private final PaymentRepository repo;

    public PaymentSvc(PaymentRepository repo) {
        this.repo = repo;
    }

    // noRollbackFor is the whole point here: DeclinedException is an
    // expected business outcome, not an error, and the failed-attempt row
    // saved just below is exactly what an audit trail is for. Without this,
    // Spring's default behavior rolls back the entire method on any
    // unchecked exception — which would silently discard the very save()
    // this method makes right before throwing. (This is the fix for a real
    // bug the audit found in notification-svc's EmailSvc, which has the
    // identical shape and does NOT have this annotation — worth porting
    // the same fix back there.)
    @Transactional(noRollbackFor = DeclinedException.class)
    public PaymentResponse process(ProcessPaymentRequest req, Long requestedByUserId) {
        boolean declined = "0000".equals(req.getCardLast4());

        Payment payment = new Payment(
                req.getOrderId(),
                requestedByUserId,
                req.getAmount(),
                req.getMethod(),
                declined ? PaymentStatus.FAILED : PaymentStatus.SUCCEEDED,
                declined ? null : "MOCK-" + UUID.randomUUID()
        );
        Payment saved = repo.save(payment);

        if (declined) {
            throw new DeclinedException("Payment declined by issuer");
        }

        return toResponse(saved);
    }

    private PaymentResponse toResponse(Payment p) {
        return new PaymentResponse(p.getId(), p.getOrderId(), p.getAmount(), p.getMethod(),
                p.getStatus(), p.getReference(), p.getCreatedAt());
    }
}
