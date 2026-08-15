package com.catalogix.cart.svc;

import com.catalogix.cart.client.CatalogClient;
import com.catalogix.cart.client.InventoryClient;
import com.catalogix.cart.client.ProductInfo;
import com.catalogix.cart.dto.AddCartItemRequest;
import com.catalogix.cart.dto.WishlistItemResponse;
import com.catalogix.cart.exception.WishlistItemNotFoundException;
import com.catalogix.cart.model.WishlistItem;
import com.catalogix.cart.repository.WishlistItemRepository;

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

    public WishlistSvc(WishlistItemRepository repo, CatalogClient catalogClient,
                        InventoryClient inventoryClient, CartSvc cartSvc) {
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

    // Idempotent by design, matching the one-tap "heart" icon UX real
    // storefronts use: adding a product that's already saved just returns
    // the existing entry rather than erroring or creating a duplicate.
    @Transactional
    public WishlistItemResponse add(Long userId, Long productId, String bearerToken) {
        Optional<WishlistItem> existing = repo.findByUserIdAndProductId(userId, productId);
        if (existing.isPresent()) {
            return toResponse(existing.get(), bearerToken);
        }
        // Fetches product up front so a bad productId fails fast with a
        // clear error, rather than silently saving a dangling reference —
        // same reasoning as CartSvc.addItem.
        catalogClient.fetch(productId, bearerToken);
        try {
            WishlistItem saved = repo.save(new WishlistItem(userId, productId));
            return toResponse(saved, bearerToken);
        } catch (org.springframework.dao.DataIntegrityViolationException raceLost) {
            // Two concurrent "add to wishlist" taps for the same product —
            // the uq_wishlist_user_product constraint (see migration) caught
            // it. Not an error from the caller's point of view: whichever
            // request won, the product IS on the wishlist now, which is all
            // this idempotent operation ever promised.
            return repo.findByUserIdAndProductId(userId, productId)
                    .map(item -> toResponse(item, bearerToken))
                    .orElseThrow(() -> raceLost);
        }
    }

    @Transactional
    public void remove(Long userId, Long productId) {
        WishlistItem item = repo.findByUserIdAndProductId(userId, productId)
                .orElseThrow(() -> new WishlistItemNotFoundException(productId));
        repo.delete(item);
    }

    // Adds the product to the cart (via CartSvc directly — same service,
    // same process, no HTTP hop needed) and removes it from the wishlist in
    // one call, matching the single "Move to Cart" button real storefronts
    // show next to a saved item.
    @Transactional
    public void moveToCart(Long userId, Long productId, int quantity, String bearerToken) {
        WishlistItem item = repo.findByUserIdAndProductId(userId, productId)
                .orElseThrow(() -> new WishlistItemNotFoundException(productId));

        AddCartItemRequest cartReq = new AddCartItemRequest();
        cartReq.setProductId(productId);
        cartReq.setQuantity(quantity);
        cartSvc.addItem(userId, cartReq, bearerToken);

        repo.delete(item);
    }

    private WishlistItemResponse toResponse(WishlistItem item, String bearerToken) {
        ProductInfo info = catalogClient.fetch(item.getProductId(), bearerToken);
        Integer stock = inventoryClient.fetchQuantity(item.getProductId(), bearerToken);
        return new WishlistItemResponse(item.getProductId(), info.getName(), info.getPrice(),
                stock, item.getAddedAt());
    }
}
