package com.catalogix.seller.event;
import com.catalogix.seller.model.SellerStatus; import java.math.BigDecimal; import java.time.Instant;
public record SellerDomainEvent(String action,Long userId,Long sellerId,SellerStatus status,BigDecimal amount,String reference,Instant occurredAt) {}
