package com.catalogix.seller.repository;
import com.catalogix.seller.model.SellerLedgerEntry; import org.springframework.data.jpa.repository.JpaRepository; import org.springframework.data.jpa.repository.Query; import java.math.BigDecimal; import java.util.Optional;
public interface SellerLedgerRepository extends JpaRepository<SellerLedgerEntry,Long>{
 Optional<SellerLedgerEntry> findBySourceKey(String sourceKey);
 @Query("select coalesce(sum(case when e.type in (com.catalogix.seller.model.LedgerType.SALE_CREDIT, com.catalogix.seller.model.LedgerType.COMMISSION_REFUND, com.catalogix.seller.model.LedgerType.ADJUSTMENT) then e.amount else -e.amount end),0) from SellerLedgerEntry e where e.sellerUserId=:userId") BigDecimal balance(@org.springframework.data.repository.query.Param("userId") Long userId);
 @Query("select coalesce(sum(e.amount),0) from SellerLedgerEntry e where e.sellerUserId=:userId and e.type=com.catalogix.seller.model.LedgerType.SALE_CREDIT") BigDecimal grossSales(@org.springframework.data.repository.query.Param("userId") Long userId);
}
