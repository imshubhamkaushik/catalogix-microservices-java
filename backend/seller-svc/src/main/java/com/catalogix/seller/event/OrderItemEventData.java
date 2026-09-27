package com.catalogix.seller.event;
import java.math.BigDecimal;
public record OrderItemEventData(Long productId,Long sellerId,String productName,int quantity,BigDecimal unitPrice,BigDecimal subtotal){}
