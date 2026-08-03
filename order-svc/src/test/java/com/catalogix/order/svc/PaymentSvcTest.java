package com.catalogix.order.svc;

import com.catalogix.order.dto.PayOrderRequest;
import com.catalogix.order.dto.PaymentResponse;
import com.catalogix.order.model.Payment;
import com.catalogix.order.model.PaymentStatus;
import com.catalogix.order.repository.PaymentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SuppressWarnings("null")
class PaymentSvcTest {

    @Mock private PaymentRepository repo;

    private PaymentSvc svc;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new PaymentSvc(repo);
        when(repo.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });
    }

    @Test
    void processSucceedsForNormalCard() {
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod("MOCK_CARD");
        req.setCardLast4("4242");

        PaymentResponse resp = svc.process(5L, new BigDecimal("100.00"), req);

        assertEquals(PaymentStatus.SUCCEEDED, resp.getStatus());
        assertEquals(5L, resp.getOrderId());
        assertNotNull(resp.getReference());
    }

    @Test
    void processDeclinesForMagicTestCard() {
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod("MOCK_CARD");
        req.setCardLast4("0000");

        PaymentResponse resp = svc.process(5L, new BigDecimal("100.00"), req);

        assertEquals(PaymentStatus.FAILED, resp.getStatus());
    }
}
