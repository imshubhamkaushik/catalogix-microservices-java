package com.catalogix.payment.svc;

import com.catalogix.payment.dto.PaymentResponse;
import com.catalogix.payment.dto.ProcessPaymentRequest;
import com.catalogix.payment.exception.DeclinedException;
import com.catalogix.payment.model.Payment;
import com.catalogix.payment.model.PaymentStatus;
import com.catalogix.payment.repository.PaymentRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentSvcTest {

    private PaymentRepository repo;
    private PaymentSvc svc;

    @BeforeEach
    void setUp() {
        repo = mock(PaymentRepository.class);
        svc = new PaymentSvc(repo);
        // Echo back whatever gets saved, with an id assigned, like a real repo would.
        when(repo.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });
    }

    private ProcessPaymentRequest req(String cardLast4) {
        ProcessPaymentRequest r = new ProcessPaymentRequest();
        r.setOrderId(42L);
        r.setAmount(new BigDecimal("19.99"));
        r.setMethod("card");
        r.setCardLast4(cardLast4);
        return r;
    }

    @Test
    void successfulPaymentReturnsSucceededWithReference() {
        PaymentResponse resp = svc.process(req("4242"), 7L);

        assertThat(resp.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(resp.getReference()).startsWith("MOCK-");
        assertThat(resp.getOrderId()).isEqualTo(42L);
    }

    @Test
    void cardLast4OfZerosIsDeclined() {
        assertThatThrownBy(() -> svc.process(req("0000"), 7L))
                .isInstanceOf(DeclinedException.class);
    }

    @Test
    void declinedAttemptIsStillPersistedForAudit() {
        assertThatThrownBy(() -> svc.process(req("0000"), 7L))
                .isInstanceOf(DeclinedException.class);

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(captor.getValue().getReference()).isNull();
    }
}
