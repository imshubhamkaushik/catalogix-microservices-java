package com.catalogix.cart.svc;

import com.catalogix.cart.client.CatalogClient;
import com.catalogix.cart.client.InventoryClient;
import com.catalogix.cart.client.ProductInfo;
import com.catalogix.cart.dto.AddCartItemRequest;
import com.catalogix.cart.dto.WishlistItemResponse;
import com.catalogix.cart.exception.WishlistItemNotFoundException;
import com.catalogix.cart.model.WishlistItem;
import com.catalogix.cart.repository.WishlistItemRepository;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

@Service
public class WishlistSvc {

    private final WishlistItemRepository repo;
    private final CatalogClient catalogClient;
    private final InventoryClient inventoryClient;
    private final CartSvc cartSvc;

    public WishlistSvc(
            WishlistItemRepository repo,
            CatalogClient catalogClient,
            InventoryClient inventoryClient,
            CartSvc cartSvc) {
        this.repo = repo;
        this.catalogClient = catalogClient;
        this.inventoryClient = inventoryClient;
        this.cartSvc = cartSvc;
    }

    @Transactional(readOnly = true)
    public List<WishlistItemResponse> list(Long userId, String bearerToken) {
        return repo.findByUserIdOrderByAddedAtDesc(userId).stream()
                .map(item -> toResponse(item, bearerToken))
                .toList();
    }

    // Idempotent by design: adding a product already present in the wishlist
    // simply returns the existing entry.
    @Transactional
    public WishlistItemResponse add(
            Long userId,
            Long productId,
            String bearerToken) {

        Optional<WishlistItem> existing =
                repo.findByUserIdAndProductId(userId, productId);

        if (existing.isPresent()) {
            return toResponse(existing.get(), bearerToken);
        }

        // Fetch product once. Besides validating that the product exists,
        // retain the returned ProductInfo so we can reuse it when building
        // the response instead of making another catalog-service call.
        ProductInfo productInfo =
                catalogClient.fetch(productId, bearerToken);

        try {
            WishlistItem saved =
                    repo.save(new WishlistItem(userId, productId));

            return toResponse(saved, bearerToken, productInfo);

        } catch (DataIntegrityViolationException raceLost) {
            // Two concurrent add requests can race between the existence
            // check and INSERT. The unique constraint makes only one win.
            // If this request lost the race, the item now exists, so return
            // the existing row while reusing the ProductInfo already fetched.
            return repo.findByUserIdAndProductId(userId, productId)
                    .map(item -> toResponse(item, bearerToken, productInfo))
                    .orElseThrow(() -> raceLost);
        }
    }

    @Transactional
    public void remove(Long userId, Long productId) {
        WishlistItem item =
                repo.findByUserIdAndProductId(userId, productId)
                        .orElseThrow(() ->
                                new WishlistItemNotFoundException(productId));

        repo.delete(item);
    }

    // Adds the product to the cart and then removes it from the wishlist.
    @Transactional
    public void moveToCart(
            Long userId,
            Long productId,
            int quantity,
            String bearerToken) {

        WishlistItem item =
                repo.findByUserIdAndProductId(userId, productId)
                        .orElseThrow(() ->
                                new WishlistItemNotFoundException(productId));

        AddCartItemRequest cartReq = new AddCartItemRequest();
        cartReq.setProductId(productId);
        cartReq.setQuantity(quantity);

        cartSvc.addItem(userId, cartReq, bearerToken);

        repo.delete(item);
    }

    /**
     * Normal response path: product information is not already available,
     * so fetch it from catalog-svc.
     */
    private WishlistItemResponse toResponse(
            WishlistItem item,
            String bearerToken) {

        ProductInfo info =
                catalogClient.fetch(item.getProductId(), bearerToken);

        return toResponse(item, bearerToken, info);
    }

    /**
     * Response path where ProductInfo was already fetched earlier.
     * This avoids a duplicate catalog-svc call.
     */
    private WishlistItemResponse toResponse(
            WishlistItem item,
            String bearerToken,
            ProductInfo info) {

        Integer stock =
                inventoryClient.fetchQuantity(
                        item.getProductId(),
                        bearerToken);

        return new WishlistItemResponse(
                item.getProductId(),
                info.getName(),
                info.getPrice(),
                stock,
                item.getAddedAt());
    }
}