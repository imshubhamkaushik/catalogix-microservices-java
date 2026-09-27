package com.catalogix.seller.dto;
import com.catalogix.seller.model.*; import java.math.BigDecimal; import java.time.Instant; import java.util.*;
public record SellerSummary(Long id,Long userId,String displayName,String businessName,String description,SellerStatus status,BigDecimal commissionRate,BigDecimal availableBalance,BigDecimal grossSales,List<PayoutView> payouts,Instant createdAt){
 public record PayoutView(Long id,BigDecimal amount,PayoutStatus status,String reference,Instant createdAt,Instant completedAt){}
}
