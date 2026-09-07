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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class PaymentSvcTest {

    private PaymentRepository repo;
    private RefundRepository refundRepo;
    private PaymentSvc svc;

    @BeforeEach
    void setUp() {
        repo = mock(PaymentRepository.class);
        refundRepo = mock(RefundRepository.class);
        svc = new PaymentSvc(repo, refundRepo);
        // Echo back whatever gets saved, with an id assigned, like a real repo would.
        when(repo.save(any(Payment.class))).thenAnswer(inv -> {
            Payment p = inv.getArgument(0);
            p.setId(1L);
            return p;
        });
        when(refundRepo.save(any(Refund.class))).thenAnswer(inv -> {
            Refund r = inv.getArgument(0);
            r.setId(1L);
            return r;
        });
    }

    private ProcessPaymentRequest cardReq(String cardLast4) {
        ProcessPaymentRequest r = new ProcessPaymentRequest();
        r.setOrderId(42L);
        r.setAmount(new BigDecimal("19.99"));
        r.setMethod(PaymentMethod.CARD);
        r.setCardLast4(cardLast4);
        return r;
    }

    private ProcessPaymentRequest upiReq(String upiId) {
        ProcessPaymentRequest r = new ProcessPaymentRequest();
        r.setOrderId(42L);
        r.setAmount(new BigDecimal("19.99"));
        r.setMethod(PaymentMethod.UPI);
        r.setUpiId(upiId);
        return r;
    }

    private ProcessPaymentRequest codReq(String amount) {
        ProcessPaymentRequest r = new ProcessPaymentRequest();
        r.setOrderId(42L);
        r.setAmount(new BigDecimal(amount));
        r.setMethod(PaymentMethod.COD);
        return r;
    }

    // ---- CARD ----

    @Test
    void successfulCardPaymentReturnsSucceededWithReference() {
        PaymentResponse resp = svc.process(cardReq("4242"), 7L);

        assertThat(resp.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(resp.getReference()).startsWith("MOCK-CARD-");
        assertThat(resp.getOrderId()).isEqualTo(42L);
    }

    @Test
    void cardLast4OfZerosIsDeclined() {
        assertThatThrownBy(() -> svc.process(cardReq("0000"), 7L))
                .isInstanceOf(DeclinedException.class);
    }

    @Test
    void declinedCardAttemptIsStillPersistedForAudit() {
        assertThatThrownBy(() -> svc.process(cardReq("0000"), 7L))
                .isInstanceOf(DeclinedException.class);

        ArgumentCaptor<Payment> captor = ArgumentCaptor.forClass(Payment.class);
        verify(repo).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(PaymentStatus.FAILED);
        assertThat(captor.getValue().getReference()).isNull();
    }

    @Test
    void cardWithoutLast4IsRejectedBeforeAnyPersistence() {
        ProcessPaymentRequest req = cardReq(null);

        assertThatThrownBy(() -> svc.process(req, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repo);
    }

    // ---- UPI ----

    @Test
    void successfulUpiPaymentReturnsSucceededWithReference() {
        PaymentResponse resp = svc.process(upiReq("buyer@upi"), 7L);

        assertThat(resp.getStatus()).isEqualTo(PaymentStatus.SUCCEEDED);
        assertThat(resp.getReference()).startsWith("MOCK-UPI-");
    }

    @Test
    void upiIdStartingWithFailIsDeclined() {
        assertThatThrownBy(() -> svc.process(upiReq("fail@upi"), 7L))
                .isInstanceOf(DeclinedException.class);
    }

    @Test
    void upiDeclineCheckIsCaseInsensitive() {
        assertThatThrownBy(() -> svc.process(upiReq("FAIL@UPI"), 7L))
                .isInstanceOf(DeclinedException.class);
    }

    @Test
    void upiWithoutIdIsRejectedBeforeAnyPersistence() {
        ProcessPaymentRequest req = upiReq(null);

        assertThatThrownBy(() -> svc.process(req, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repo);
    }

    // ---- COD ----

    @Test
    void codNeverDeclinesAndEntersPendingState() {
        PaymentResponse resp = svc.process(codReq("199.99"), 7L);

        assertThat(resp.getStatus()).isEqualTo(PaymentStatus.COD_PENDING);
        assertThat(resp.getReference()).isNull(); // nothing was actually captured
    }

    @Test
    void codAboveTheCapIsRejectedBeforeAnyPersistence() {
        ProcessPaymentRequest req = codReq("50000.01");

        assertThatThrownBy(() -> svc.process(req, 7L))
                .isInstanceOf(IllegalArgumentException.class);
        verifyNoInteractions(repo);
    }

    @Test
    void codExactlyAtTheCapIsAllowed() {
        PaymentResponse resp = svc.process(codReq("50000.00"), 7L);

        assertThat(resp.getStatus()).isEqualTo(PaymentStatus.COD_PENDING);
    }

    // ---- refund ----

    private Payment succeededPayment(BigDecimal amount) {
        Payment p = new Payment(42L, 7L, amount, PaymentMethod.CARD, PaymentStatus.SUCCEEDED, "MOCK-CARD-abc");
        p.setId(1L);
        return p;
    }

    private ProcessRefundRequest refundReq(String amount) {
        ProcessRefundRequest r = new ProcessRefundRequest();
        r.setOrderId(42L);
        r.setAmount(new BigDecimal(amount));
        return r;
    }

    @Test
    void refundSucceedsAgainstTheOriginalSuccessfulPayment() {
        when(repo.findByOrderIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(succeededPayment(new BigDecimal("100.00"))));
        when(refundRepo.findByOrderId(42L)).thenReturn(List.of());

        RefundResponse resp = svc.refund(refundReq("100.00"));

        assertThat(resp.getReference()).startsWith("MOCK-REFUND-");
        assertThat(resp.getAmount()).isEqualByComparingTo("100.00");
    }

    @Test
    void refundThrowsWhenThereIsNoSuccessfulPaymentForTheOrder() {
        when(repo.findByOrderIdOrderByCreatedAtDesc(42L)).thenReturn(List.of());

        assertThatThrownBy(() -> svc.refund(refundReq("100.00")))
                .isInstanceOf(NoSuchPaymentException.class);
    }

    @Test
    void refundIgnoresFailedPaymentAttemptsWhenFindingTheOriginal() {
        Payment declined = new Payment(42L, 7L, new BigDecimal("100.00"), PaymentMethod.CARD,
                PaymentStatus.FAILED, null);
        when(repo.findByOrderIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(declined));

        assertThatThrownBy(() -> svc.refund(refundReq("100.00")))
                .isInstanceOf(NoSuchPaymentException.class);
    }

    @Test
    void refundRejectsAnAmountExceedingTheOriginalPayment() {
        when(repo.findByOrderIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(succeededPayment(new BigDecimal("100.00"))));
        when(refundRepo.findByOrderId(42L)).thenReturn(List.of());

        assertThatThrownBy(() -> svc.refund(refundReq("100.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // Added to close a real over-refund gap: two partial refunds whose sum
    // exceeds what was actually paid must be rejected, not just a single
    // refund request checked in isolation against the original amount.
    @Test
    void refundRejectsWhenPriorPartialRefundsWouldPushTheTotalOverTheOriginal() {
        when(repo.findByOrderIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(succeededPayment(new BigDecimal("100.00"))));
        Refund priorRefund = new Refund(42L, 1L, new BigDecimal("60.00"), "MOCK-REFUND-prior");
        when(refundRepo.findByOrderId(42L)).thenReturn(List.of(priorRefund));

        assertThatThrownBy(() -> svc.refund(refundReq("40.01")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void refundAllowsPartialRefundsThatExactlySumToTheOriginal() {
        when(repo.findByOrderIdOrderByCreatedAtDesc(42L)).thenReturn(List.of(succeededPayment(new BigDecimal("100.00"))));
        Refund priorRefund = new Refund(42L, 1L, new BigDecimal("60.00"), "MOCK-REFUND-prior");
        when(refundRepo.findByOrderId(42L)).thenReturn(List.of(priorRefund));

        RefundResponse resp = svc.refund(refundReq("40.00"));

        assertThat(resp.getAmount()).isEqualByComparingTo("40.00");
    }
}
