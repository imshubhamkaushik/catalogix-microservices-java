package com.catalogix.order.svc;

import com.catalogix.order.client.NotificationClient;
import com.catalogix.order.dto.OrderResponse;

import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;

/**
 * Fires order-related emails off the request thread entirely. A separate
 * bean from OrderSvc on purpose: @Async (like @Transactional/@CircuitBreaker
 * elsewhere in this service) is AOP-proxy-based, so it only takes effect on
 * calls arriving from a different bean — see ProductSvcClient's Javadoc for
 * the fuller explanation of why this pattern keeps showing up.
 */
@Component
public class OrderNotifier {

    private final NotificationClient notificationClient;

    public OrderNotifier(NotificationClient notificationClient) {
        this.notificationClient = notificationClient;
    }

    @Async
    public void notifyOrderConfirmed(String email, OrderResponse order) {
        if (email == null || email.isBlank()) {
            return;
        }
        StringBuilder body = new StringBuilder();
        body.append("Thanks for your order!\n\n");
        body.append("Order #").append(order.getId()).append("\n");
        order.getItems().forEach(item -> body
                .append("  ").append(item.getQuantity()).append(" x ").append(item.getProductName())
                .append(" — ").append(formatPrice(item.getSubtotal())).append('\n'));
        body.append("\nTotal: ").append(formatPrice(order.getTotalAmount())).append('\n');

        notificationClient.sendEmail(email, "Your Catalogix order #" + order.getId() + " is confirmed", body.toString());
    }

    @Async
    public void notifyOrderCancelled(String email, OrderResponse order) {
        if (email == null || email.isBlank()) {
            return;
        }
        String body = "Your order #" + order.getId() + " has been cancelled. "
                + "Any reserved stock has been released back to the catalogue.";
        notificationClient.sendEmail(email, "Your Catalogix order #" + order.getId() + " was cancelled", body);
    }

    private String formatPrice(BigDecimal amount) {
        return "₹" + amount.setScale(2, java.math.RoundingMode.HALF_UP);
    }
}
