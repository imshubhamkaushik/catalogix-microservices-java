import React, { useEffect, useState, useCallback } from "react";
import PropTypes from "prop-types";
import { getWishlist, removeWishlistItem, moveWishlistItemToCart } from "../api";
import { useAuth } from "../context/AuthContext";

// Format number as Indian rupee string e.g. ₹1,899.00
function formatPrice(value) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(value);
}

function Toast({ message, type, onDone }) {
  useEffect(() => {
    const t = setTimeout(onDone, 3000);
    return () => clearTimeout(t);
  }, [onDone]);

  return (
    <div className={`toast toast-${type}`}>
      <div className="toast-dot" />
      {message}
    </div>
  );
}

Toast.propTypes = {
  message: PropTypes.string.isRequired,
  type: PropTypes.oneOf(["success", "error"]).isRequired,
  onDone: PropTypes.func.isRequired,
};

const WishlistIcon = () => (
  <svg viewBox="0 0 16 16" width="15" height="15" fill="currentColor">
    <path d="M8 14.5s-6.5-4-6.5-8.5C1.5 3.3 3.4 1.5 5.7 1.5c1.2 0 2.3.6 2.3 1.7 0-1.1 1.1-1.7 2.3-1.7 2.3 0 4.2 1.8 4.2 4.5 0 4.5-6.5 8.5-6.5 8.5z" />
  </svg>
);

function StockBadge({ quantity }) {
  const qty = quantity ?? 0;
  let cls = "badge-in-stock";
  let label = `${qty} in stock`;
  if (qty === 0) {
    cls = "badge-out-of-stock";
    label = "Out of stock";
  } else if (qty <= 5) {
    cls = "badge-low-stock";
    label = `${qty} left`;
  }
  return <span className={`badge ${cls}`}>{label}</span>;
}

StockBadge.propTypes = {
  quantity: PropTypes.number,
};

export default function Wishlist() {
  const { user: currentUser } = useAuth();

  const [items, setItems] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [toast, setToast] = useState(null);
  const [busyIds, setBusyIds] = useState(new Set());

  const fetchWishlist = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const data = await getWishlist();
      setItems(data || []);
    } catch {
      setError("Failed to load your wishlist. Is the backend running?");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchWishlist(); }, [fetchWishlist]);

  const withBusy = async (productId, action) => {
    setBusyIds((prev) => new Set(prev).add(productId));
    try {
      await action();
    } finally {
      setBusyIds((prev) => {
        const next = new Set(prev);
        next.delete(productId);
        return next;
      });
    }
  };

  const handleRemove = (item) => withBusy(item.productId, async () => {
    try {
      await removeWishlistItem(item.productId);
      setItems((prev) => prev.filter((i) => i.productId !== item.productId));
      setToast({ message: `"${item.productName}" removed from your wishlist.`, type: "success" });
    } catch {
      setError("Failed to remove item.");
    }
  });

  const handleMoveToCart = (item) => withBusy(item.productId, async () => {
    try {
      await moveWishlistItemToCart(item.productId, 1);
      setItems((prev) => prev.filter((i) => i.productId !== item.productId));
      setToast({ message: `"${item.productName}" moved to your cart.`, type: "success" });
    } catch (err) {
      setError(err.response?.data?.message || "Failed to move item to cart.");
    }
  });

  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Wishlist</h1>
          <p className="page-subtitle">Saved items for {currentUser?.name}</p>
        </div>
      </div>

      <div className="page-content">
        {toast && <Toast message={toast.message} type={toast.type} onDone={() => setToast(null)} />}
        {error && <div className="toast toast-error">{error}</div>}

        <div className="section-header">
          <div className="section-header-left">
            <span className="section-title">Saved for later</span>
            {!loading && <span className="section-count">{items.length} items</span>}
          </div>
        </div>

        {loading && (
          <div className="skeleton-list">
            {[1, 2, 3].map((i) => <div key={i} className="skeleton-row" />)}
          </div>
        )}

        {!loading && items.length === 0 && (
          <div className="empty-state">
            <div className="empty-icon"><WishlistIcon /></div>
            <p className="empty-title">Your wishlist is empty</p>
            <p className="empty-sub">Tap the heart on any product to save it here for later.</p>
          </div>
        )}

        {!loading && items.length > 0 && (
          <div className="item-list">
            {items.map((item) => {
              const busy = busyIds.has(item.productId);
              const outOfStock = (item.stockQuantity ?? 0) === 0;
              return (
                <div key={item.productId} className="item-row">
                  <div className="product-icon-wrap"><WishlistIcon /></div>
                  <div className="item-meta">
                    <div className="item-name">{item.productName}</div>
                    <div className="item-sub">
                      Saved {item.addedAt ? new Date(item.addedAt).toLocaleDateString() : ""}
                    </div>
                  </div>
                  <div className="item-actions item-actions-product">
                    <StockBadge quantity={item.stockQuantity} />
                    <span className="price-tag">{formatPrice(item.price)}</span>
                    <button
                      className="btn-small btn-primary-small"
                      type="button"
                      onClick={() => handleMoveToCart(item)}
                      disabled={busy || outOfStock}
                      title={outOfStock ? "Out of stock" : "Move to cart"}
                    >
                      {busy ? "…" : "Move to cart"}
                    </button>
                    <button
                      className="icon-btn"
                      onClick={() => handleRemove(item)}
                      disabled={busy}
                      title="Remove from wishlist"
                      type="button"
                    >
                      <svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor">
                        <path d="M11 1.5v1h3.5a.5.5 0 010 1H13v9a1 1 0 01-1 1H4a1 1 0 01-1-1v-9H1.5a.5.5 0 010-1H5v-1A1.5 1.5 0 016.5 0h3A1.5 1.5 0 0111 1.5zm-5 0v1h4v-1a.5.5 0 00-.5-.5h-3a.5.5 0 00-.5.5zM5.5 5.5a.5.5 0 00-1 0v6a.5.5 0 001 0v-6zm2.5 0a.5.5 0 00-1 0v6a.5.5 0 001 0v-6zm2.5 0a.5.5 0 00-1 0v6a.5.5 0 001 0v-6z" />
                      </svg>
                    </button>
                  </div>
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
