package com.catalogix.order.svc;

import com.catalogix.order.client.ProductSvcClient;
import com.catalogix.order.dto.CreateOrderRequest;
import com.catalogix.order.dto.OrderItemRequest;
import com.catalogix.order.dto.OrderResponse;
import com.catalogix.order.dto.ProductLookupResponse;
import com.catalogix.order.exception.ProductUnavailableException;
import com.catalogix.order.model.Order;
import com.catalogix.order.model.OrderStatus;
import com.catalogix.order.model.StockAdjustmentOutbox;
import com.catalogix.order.repository.OrderRepository;
import com.catalogix.order.repository.StockAdjustmentOutboxRepository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@SuppressWarnings("null")
class OrderSvcTest {

    @Mock
    private OrderRepository repo;

    @Mock
    private StockAdjustmentOutboxRepository outboxRepo;

    @Mock
    private ProductSvcClient productSvcClient;

    @Mock
    private OrderNotifier orderNotifier;

    private OrderSvc svc;

    private static final String TOKEN = "Bearer test-token";
    private static final String EMAIL = "buyer@example.com";

    @BeforeEach
    void setUp() {
        MockitoAnnotations.openMocks(this);
        svc = new OrderSvc(repo, outboxRepo, productSvcClient, orderNotifier);
        when(repo.save(any(Order.class))).thenAnswer(inv -> {
            Order o = inv.getArgument(0);
            o.setId(1L);
            return o;
        });
    }

    private ProductLookupResponse product(Long id, String name, String price, int stock) {
        ProductLookupResponse p = new ProductLookupResponse();
        p.setId(id);
        p.setName(name);
        p.setPrice(new BigDecimal(price));
        p.setStockQuantity(stock);
        return p;
    }

    private CreateOrderRequest requestFor(Long productId, int qty) {
        OrderItemRequest item = new OrderItemRequest();
        item.setProductId(productId);
        item.setQuantity(qty);
        CreateOrderRequest req = new CreateOrderRequest();
        req.setItems(List.of(item));
        return req;
    }

    @Test
    void createOrderSucceedsComputesTotalAndSendsConfirmationEmail() {
        when(productSvcClient.fetchProduct(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00", 10));

        OrderSvc.OrderCreationResult result = svc.createOrder(42L, requestFor(1L, 2), TOKEN, null, EMAIL);

        assertTrue(result.wasNew());
        assertEquals(OrderStatus.CONFIRMED, result.order().getStatus());
        assertEquals(new BigDecimal("200.00"), result.order().getTotalAmount());
        verify(productSvcClient).adjustStock(1L, -2, TOKEN);
        verify(repo).save(any(Order.class));
        verify(orderNotifier).notifyOrderConfirmed(eq(EMAIL), any(OrderResponse.class));
    }

    @Test
    void createOrderThrowsAndDoesNotSaveOrNotifyWhenStockInsufficient() {
        when(productSvcClient.fetchProduct(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00", 1));
        doThrow(new ProductUnavailableException("Insufficient stock for product 1"))
                .when(productSvcClient).adjustStock(1L, -5, TOKEN);

        CreateOrderRequest req = requestFor(1L, 5);
        assertThrows(ProductUnavailableException.class,
                () -> svc.createOrder(42L, req, TOKEN, null, EMAIL));
        verify(repo, never()).save(any());
        verifyNoInteractions(orderNotifier);
    }

    @Test
    void createOrderCompensatesEarlierItemsWhenLaterItemFails() {
        CreateOrderRequest req = new CreateOrderRequest();
        OrderItemRequest item1 = new OrderItemRequest();
        item1.setProductId(1L);
        item1.setQuantity(1);
        OrderItemRequest item2 = new OrderItemRequest();
        item2.setProductId(2L);
        item2.setQuantity(1);
        req.setItems(List.of(item1, item2));

        when(productSvcClient.fetchProduct(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00", 10));
        when(productSvcClient.fetchProduct(2L, TOKEN)).thenReturn(product(2L, "Tablet", "200.00", 0));
        doThrow(new ProductUnavailableException("Insufficient stock for product 2"))
                .when(productSvcClient).adjustStock(2L, -1, TOKEN);

        assertThrows(ProductUnavailableException.class, () -> svc.createOrder(42L, req, TOKEN, null, EMAIL));

        // Compensation: stock for product 1 should be restored (+1).
        verify(productSvcClient).adjustStock(1L, -1, TOKEN); // the original reservation
        verify(productSvcClient).adjustStock(1L, 1, TOKEN);  // the compensating restore
        verify(repo, never()).save(any());
    }

    @Test
    void createOrderQueuesToOutboxWhenLiveCompensationAlsoFails() {
        CreateOrderRequest req = new CreateOrderRequest();
        OrderItemRequest item1 = new OrderItemRequest();
        item1.setProductId(1L);
        item1.setQuantity(1);
        OrderItemRequest item2 = new OrderItemRequest();
        item2.setProductId(2L);
        item2.setQuantity(1);
        req.setItems(List.of(item1, item2));

        when(productSvcClient.fetchProduct(1L, TOKEN)).thenReturn(product(1L, "Phone", "100.00", 10));
        when(productSvcClient.fetchProduct(2L, TOKEN)).thenReturn(product(2L, "Tablet", "200.00", 0));
        doThrow(new ProductUnavailableException("Insufficient stock for product 2"))
                .when(productSvcClient).adjustStock(2L, -1, TOKEN);
        // The compensating restore for product 1 ALSO fails live (e.g. product-svc down)...
        doThrow(new ProductUnavailableException("unreachable"))
                .when(productSvcClient).adjustStock(1L, 1, TOKEN);

        assertThrows(ProductUnavailableException.class, () -> svc.createOrder(42L, req, TOKEN, null, EMAIL));

        // ...so it should be queued to the outbox instead of silently dropped.
        verify(outboxRepo).save(argThat(entry ->
                entry.getProductId().equals(1L) && entry.getDelta() == 1));
    }

    @Test
    void createOrderWithMatchingIdempotencyKeyReturnsExistingOrderWithoutReReservingOrReNotifying() {
        Order existing = new Order();
        existing.setId(9L);
        existing.setUserId(42L);
        existing.setStatus(OrderStatus.CONFIRMED);
        existing.setTotalAmount(new BigDecimal("50.00"));
        when(repo.findByUserIdAndIdempotencyKey(42L, "key-123")).thenReturn(Optional.of(existing));

        OrderSvc.OrderCreationResult result = svc.createOrder(42L, requestFor(1L, 2), TOKEN, "key-123", EMAIL);

        assertFalse(result.wasNew());
        assertEquals(9L, result.order().getId());
        verifyNoInteractions(productSvcClient);
        verify(repo, never()).save(any());
        verifyNoInteractions(orderNotifier); // it's a replay, not a new order — no duplicate confirmation email
    }

    @Test
    void cancelOrderRestocksEachItemAndSendsCancellationEmail() {
        Order order = new Order();
        order.setId(5L);
        order.setUserId(42L);
        order.setStatus(OrderStatus.CONFIRMED);
        order.addItem(new com.catalogix.order.model.OrderItem(1L, "Phone", 2, new BigDecimal("100.00")));
        when(repo.findById(5L)).thenReturn(Optional.of(order));
        when(repo.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));

        var resp = svc.cancelOrder(5L, 42L, "USER", TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, resp.getStatus());
        verify(productSvcClient).adjustStock(1L, 2, TOKEN);
        verify(orderNotifier).notifyOrderCancelled(eq(EMAIL), any(OrderResponse.class));
    }

    @Test
    void cancelOrderQueuesToOutboxWhenRestockFailsLive() {
        Order order = new Order();
        order.setId(5L);
        order.setUserId(42L);
        order.setStatus(OrderStatus.CONFIRMED);
        order.addItem(new com.catalogix.order.model.OrderItem(1L, "Phone", 2, new BigDecimal("100.00")));
        when(repo.findById(5L)).thenReturn(Optional.of(order));
        when(repo.save(any(Order.class))).thenAnswer(inv -> inv.getArgument(0));
        doThrow(new ProductUnavailableException("unreachable"))
                .when(productSvcClient).adjustStock(1L, 2, TOKEN);

        var resp = svc.cancelOrder(5L, 42L, "USER", TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, resp.getStatus()); // still cancelled locally
        verify(outboxRepo).save(argThat((StockAdjustmentOutbox entry) ->
                entry.getProductId().equals(1L) && entry.getDelta() == 2));
    }

    @Test
    void cancelOrderIsANoOpAndDoesNotReNotifyIfAlreadyCancelled() {
        Order order = new Order();
        order.setId(5L);
        order.setUserId(42L);
        order.setStatus(OrderStatus.CANCELLED);
        when(repo.findById(5L)).thenReturn(Optional.of(order));

        var resp = svc.cancelOrder(5L, 42L, "USER", TOKEN, EMAIL);

        assertEquals(OrderStatus.CANCELLED, resp.getStatus());
        verifyNoInteractions(productSvcClient);
        verifyNoInteractions(orderNotifier);
        verify(repo, never()).save(any());
    }
}
