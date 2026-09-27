package com.catalogix.fulfillment.event;
import java.math.BigDecimal; import java.time.Instant; import java.util.List;
public record OrderConfirmedEvent(Long orderId,Long userId,String userEmail,List<OrderItemEventData> items,BigDecimal totalAmount,Instant occurredAt){}
