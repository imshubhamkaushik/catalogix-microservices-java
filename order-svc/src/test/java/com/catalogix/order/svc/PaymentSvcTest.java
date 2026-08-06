package com.catalogix.order.svc;

import com.catalogix.order.dto.PayOrderRequest;
import com.catalogix.order.dto.PaymentResponse;
import com.catalogix.order.model.Payment;
import com.catalogix.order.model.PaymentStatus;
import com.catalogix.order.repository.PaymentRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@SuppressWarnings("null")
class PaymentSvcTest {

    @Mock private PaymentRepository repo;

    @InjectMocks
    private PaymentSvc svc;

    private static final String MOCK_CARD = "MOCK_CARD";
    private static final String AMOUNT_100 = "100.00";
    private static final Long ORDER_ID = 5L;

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        when(repo.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });
    }

    @Test
    void processSucceedsForNormalCard() {
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod(MOCK_CARD);
        req.setCardLast4("4242");

        PaymentResponse resp = svc.process(ORDER_ID, new BigDecimal(AMOUNT_100), req);

        assertEquals(PaymentStatus.SUCCEEDED, resp.getStatus());
        assertEquals(ORDER_ID, resp.getOrderId());
        assertNotNull(resp.getReference());
    }

    @Test
    void processDeclinesForMagicTestCard() {
        PayOrderRequest req = new PayOrderRequest();
        req.setMethod(MOCK_CARD);
        req.setCardLast4("0000");

        PaymentResponse resp = svc.process(ORDER_ID, new BigDecimal(AMOUNT_100), req);

        assertEquals(PaymentStatus.FAILED, resp.getStatus());
    }
}