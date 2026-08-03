import React, { useEffect, useState, useCallback, useRef } from "react";
import PropTypes from "prop-types";
import {
  getProducts,
  getOrders,
  cancelOrder,
  payOrder,
  updateOrderStatus,
  getCart,
  addCartItem,
  updateCartItemQuantity,
  removeCartItem,
  applyCartCoupon,
  removeCartCoupon,
  checkoutCart,
} from "../api";
import { useAuth } from "../context/AuthContext";

function formatPrice(value) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(value);
}

function formatDate(value) {
  return new Date(value).toLocaleString("en-IN", {
    day: "2-digit",
    month: "short",
    year: "numeric",
    hour: "2-digit",
    minute: "2-digit",
  });
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

const STATUS_BADGE_CLASS = {
  PENDING_PAYMENT: "badge-low-stock",
  CONFIRMED: "badge-in-stock",
  SHIPPED: "badge-category",
  DELIVERED: "badge-admin",
  CANCELLED: "badge-out-of-stock",
};

function StatusBadge({ status }) {
  return (
    <span className={`badge ${STATUS_BADGE_CLASS[status] || "badge-user"}`}>
      {status.replace("_", " ")}
    </span>
  );
}
StatusBadge.propTypes = { status: PropTypes.string.isRequired };

// -------- Product picker: type to search, click a result to add to cart --------
function ProductPicker({ onAdd }) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState([]);
  const [searching, setSearching] = useState(false);
  const debounceRef = useRef(null);

  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    if (!query.trim()) {
      setResults([]);
      return undefined;
    }
    debounceRef.current = setTimeout(async () => {
      setSearching(true);
      try {
        const data = await getProducts({ search: query, page: 0, size: 5 });
        setResults(data.content || []);
      } catch {
        setResults([]);
      } finally {
        setSearching(false);
      }
    }, 300);
    return () => clearTimeout(debounceRef.current);
  }, [query]);

  return (
    <div className="product-picker">
      <div className="search-box">
        <svg
          viewBox="0 0 16 16"
          width="13"
          height="13"
          fill="currentColor"
          style={{ opacity: 0.4, flexShrink: 0 }}
        >
          <path d="M11.742 10.344a6.5 6.5 0 10-1.397 1.398l3.85 3.85a1 1 0 001.415-1.414l-3.868-3.834zm-5.242 1.156a5 5 0 110-10 5 5 0 010 10z" />
        </svg>
        <input
          placeholder="Search products to add to your cart…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      {(searching || results.length > 0) && (
        <div className="picker-results">
          {searching && <div className="picker-result-empty">Searching…</div>}
          {!searching &&
            results.map((p) => (
              <button
                key={p.id}
                type="button"
                className="picker-result-row"
                disabled={(p.stockQuantity ?? 0) === 0}
                onClick={() => {
                  onAdd(p);
                  setQuery("");
                  setResults([]);
                }}
              >
                <span className="picker-result-name">{p.name}</span>
                <span className="picker-result-price">
                  {formatPrice(p.price)}
                </span>
                <span
                  className={`badge ${(p.stockQuantity ?? 0) === 0 ? "badge-out-of-stock" : "badge-in-stock"}`}
                >
                  {(p.stockQuantity ?? 0) === 0
                    ? "Out of stock"
                    : `${p.stockQuantity} in stock`}
                </span>
              </button>
            ))}
          {!searching && results.length === 0 && (
            <div className="picker-result-empty">No matching products.</div>
          )}
        </div>
      )}
    </div>
  );
}
ProductPicker.propTypes = { onAdd: PropTypes.func.isRequired };

// -------- Inline "complete payment" form shown on a PENDING_PAYMENT order --------
function PaymentForm({ order, onPaid, onError }) {
  const [cardLast4, setCardLast4] = useState("");
  const [paying, setPaying] = useState(false);

  const handlePay = async (e) => {
    e.preventDefault();
    setPaying(true);
    try {
      const result = await payOrder(order.id, "MOCK_CARD", cardLast4 || "4242");
      onPaid(result);
    } catch (err) {
      onError(err.response?.data?.message || "Payment failed.");
    } finally {
      setPaying(false);
    }
  };

  return (
    <form className="payment-form" onSubmit={handlePay}>
      <input
        className="qty-input"
        style={{ width: 90 }}
        placeholder="Card last 4"
        maxLength={4}
        value={cardLast4}
        onChange={(e) => setCardLast4(e.target.value.replace(/\D/g, ""))}
        disabled={paying}
      />
      <button
        className="btn-small btn-primary-small"
        type="submit"
        disabled={paying}
      >
        {paying ? "Processing…" : `Pay ${formatPrice(order.totalAmount)}`}
      </button>
      <span className="auth-help-text" style={{ margin: 0 }}>
        Mock payment — any card works, "0000" simulates a decline.
      </span>
    </form>
  );
}
PaymentForm.propTypes = {
  order: PropTypes.shape({
    id: PropTypes.number,
    totalAmount: PropTypes.number,
  }).isRequired,
  onPaid: PropTypes.func.isRequired,
  onError: PropTypes.func.isRequired,
};

export default function Orders() {
  const { isAdmin } = useAuth();

  const [cart, setCart] = useState(null);
  const [couponInput, setCouponInput] = useState("");
  const [applyingCoupon, setApplyingCoupon] = useState(false);
  const [checkingOut, setCheckingOut] = useState(false);

  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [toast, setToast] = useState(null);

  const fetchCart = useCallback(async () => {
    try {
      setCart(await getCart());
    } catch {
      setError("Failed to load your cart.");
    }
  }, []);

  const fetchOrders = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      const data = await getOrders({ sort: "id,desc" });
      setOrders(data.content || []);
    } catch {
      setError("Failed to load orders. Is the backend running?");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => {
    fetchCart();
    fetchOrders();
  }, [fetchCart, fetchOrders]);

  const handleAddToCart = async (product) => {
    try {
      setCart(await addCartItem(product.id, 1));
    } catch (err) {
      setError(err.response?.data?.message || "Failed to add to cart.");
    }
  };

  const updateQty = async (productId, quantity) => {
    try {
      setCart(await updateCartItemQuantity(productId, Math.max(1, quantity)));
    } catch (err) {
      setError(err.response?.data?.message || "Failed to update quantity.");
    }
  };

  const removeFromCart = async (productId) => {
    try {
      setCart(await removeCartItem(productId));
    } catch {
      setError("Failed to remove item.");
    }
  };

  const handleApplyCoupon = async (e) => {
    e.preventDefault();
    if (!couponInput.trim()) return;
    setApplyingCoupon(true);
    setError("");
    try {
      setCart(await applyCartCoupon(couponInput.trim().toUpperCase()));
      setToast({ message: "Coupon applied.", type: "success" });
    } catch (err) {
      setError(err.response?.data?.message || "That coupon code isn't valid.");
    } finally {
      setApplyingCoupon(false);
    }
  };

  const handleRemoveCoupon = async () => {
    try {
      setCart(await removeCartCoupon());
    } catch {
      setError("Failed to remove coupon.");
    }
  };

  const handleCheckout = async () => {
    if (!cart || cart.items.length === 0) return;
    setCheckingOut(true);
    setError("");
    try {
      // A fresh key per click: if this request is retried (e.g. the response
      // is lost to a network blip), order-svc recognizes the same key and
      // returns the original order instead of creating a second one.
      await checkoutCart(crypto.randomUUID());
      setCouponInput("");
      await Promise.all([fetchCart(), fetchOrders()]);
      setToast({
        message: "Order placed — complete payment below to confirm it.",
        type: "success",
      });
    } catch (err) {
      setError(
        err.response?.data?.message ||
          "Checkout failed — one or more items may be unavailable.",
      );
    } finally {
      setCheckingOut(false);
    }
  };

  const handlePaid = (result) => {
    fetchOrders();
    setToast({
      message:
        result.payment.status === "SUCCEEDED"
          ? "Payment successful — order confirmed!"
          : "Payment declined — the order was cancelled and stock released.",
      type: result.payment.status === "SUCCEEDED" ? "success" : "error",
    });
  };

  const handleCancel = async (order) => {
    if (
      !globalThis.confirm(`Cancel order #${order.id}? Stock will be restored.`)
    )
      return;
    try {
      await cancelOrder(order.id);
      await fetchOrders();
      setToast({ message: `Order #${order.id} cancelled.`, type: "success" });
    } catch (err) {
      setError(err.response?.data?.message || "Failed to cancel order.");
    }
  };

  const handleAdvanceStatus = async (order, nextStatus) => {
    try {
      await updateOrderStatus(order.id, nextStatus);
      await fetchOrders();
      setToast({
        message: `Order #${order.id} marked ${nextStatus.toLowerCase()}.`,
        type: "success",
      });
    } catch (err) {
      setError(err.response?.data?.message || "Failed to update order status.");
    }
  };

  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Orders</h1>
          <p className="page-subtitle">
            {isAdmin
              ? "Build a cart and manage every order"
              : "Build a cart and track your orders"}
          </p>
        </div>
      </div>

      <div className="page-content">
        {toast && (
          <Toast
            message={toast.message}
            type={toast.type}
            onDone={() => setToast(null)}
          />
        )}
        {error && <div className="toast toast-error">{error}</div>}

        {/* Cart builder */}
        <div className="form-panel">
          <p className="form-panel-label">Your cart</p>
          <ProductPicker onAdd={handleAddToCart} />

          {cart && cart.items.length > 0 && (
            <div className="cart-list">
              {cart.items.map((line) => (
                <div key={line.productId} className="cart-line">
                  <span className="cart-line-name">{line.productName}</span>
                  <input
                    className="qty-input"
                    type="number"
                    min="1"
                    value={line.quantity}
                    onChange={(e) =>
                      updateQty(
                        line.productId,
                        Number.parseInt(e.target.value, 10) || 1,
                      )
                    }
                  />
                  <span className="cart-line-subtotal">
                    {formatPrice(line.subtotal)}
                  </span>
                  <button
                    className="icon-btn"
                    onClick={() => removeFromCart(line.productId)}
                    title="Remove"
                  >
                    <svg
                      viewBox="0 0 16 16"
                      width="12"
                      height="12"
                      fill="currentColor"
                    >
                      <path d="M2.146 2.854a.5.5 0 111.415-1.415L8 6.086l4.44-4.647a.5.5 0 01.708.707L8.707 6.793l4.647 4.647a.5.5 0 01-.708.708L8 7.5l-4.44 4.648a.5.5 0 01-.707-.708L7.293 6.793l-4.647-4.647.5.708z" />
                    </svg>
                  </button>
                </div>
              ))}

              <form className="coupon-row" onSubmit={handleApplyCoupon}>
                {cart.couponCode ? (
                  <>
                    <span className="badge badge-in-stock">
                      Coupon: {cart.couponCode}
                    </span>
                    <button
                      className="btn-small"
                      type="button"
                      onClick={handleRemoveCoupon}
                    >
                      Remove
                    </button>
                  </>
                ) : (
                  <>
                    <input
                      className="field-input"
                      style={{ maxWidth: 160 }}
                      placeholder="Coupon code"
                      value={couponInput}
                      onChange={(e) => setCouponInput(e.target.value)}
                      disabled={applyingCoupon}
                    />
                    <button
                      className="btn-small"
                      type="submit"
                      disabled={applyingCoupon}
                    >
                      {applyingCoupon ? "Applying…" : "Apply"}
                    </button>
                  </>
                )}
              </form>

              <div className="cart-footer">
                <div>
                  <div className="auth-help-text" style={{ margin: 0 }}>
                    Subtotal: {formatPrice(cart.subtotal)}
                  </div>
                  {cart.discountAmount > 0 && (
                    <div className="auth-help-text" style={{ margin: 0 }}>
                      Discount: -{formatPrice(cart.discountAmount)}
                    </div>
                  )}
                  <span className="cart-total">
                    Total: {formatPrice(cart.total)}
                  </span>
                </div>
                <button
                  className="form-submit cart-submit"
                  onClick={handleCheckout}
                  disabled={checkingOut}
                >
                  {checkingOut ? "Placing order…" : "Checkout"}
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Order history */}
        <div className="section-header">
          <div className="section-header-left">
            <span className="section-title">
              {isAdmin ? "All orders" : "Your orders"}
            </span>
            {!loading && (
              <span className="section-count">{orders.length} orders</span>
            )}
          </div>
        </div>

        {loading && (
          <div className="skeleton-list">
            {[1, 2, 3].map((i) => (
              <div key={i} className="skeleton-row" />
            ))}
          </div>
        )}

        {!loading && orders.length === 0 && (
          <div className="empty-state">
            <div className="empty-icon">
              <svg
                viewBox="0 0 16 16"
                width="20"
                height="20"
                fill="currentColor"
              >
                <path d="M1 2.5A.5.5 0 011.5 2H3a.5.5 0 01.485.379L3.89 4H14.5a.5.5 0 01.491.592l-1 5A.5.5 0 0113.5 10H5a.5.5 0 01-.491-.408L3.01 4.607 2.61 3H1.5a.5.5 0 01-.5-.5zM5 12a1.5 1.5 0 100 3 1.5 1.5 0 000-3zm7 0a1.5 1.5 0 100 3 1.5 1.5 0 000-3z" />
              </svg>
            </div>
            <p className="empty-title">No orders yet</p>
            <p className="empty-sub">
              Search for a product above to build your first order.
            </p>
          </div>
        )}

        {!loading && orders.length > 0 && (
          <div className="item-list">
            {orders.map((order) => (
              <div key={order.id} className="item-row order-row">
                <div className="item-meta">
                  <div className="item-name">
                    Order #{order.id}
                    {isAdmin && (
                      <span className="item-name-hint">
                        {" "}
                        · user #{order.userId}
                      </span>
                    )}
                  </div>
                  <div className="item-sub">
                    {order.items
                      .map((i) => `${i.quantity} × ${i.productName}`)
                      .join(", ")}
                  </div>
                  <div className="item-sub item-sub-faint">
                    {formatDate(order.createdAt)}
                    {order.appliedCouponCode &&
                      ` · Coupon ${order.appliedCouponCode} (-${formatPrice(order.discountAmount)})`}
                  </div>
                  {order.status === "PENDING_PAYMENT" && (
                    <PaymentForm
                      order={order}
                      onPaid={handlePaid}
                      onError={setError}
                    />
                  )}
                </div>
                <div className="item-actions">
                  <StatusBadge status={order.status} />
                  <span className="price-tag">
                    {formatPrice(order.totalAmount)}
                  </span>
                  {(order.status === "PENDING_PAYMENT" ||
                    order.status === "CONFIRMED") && (
                    <button
                      className="btn-small"
                      onClick={() => handleCancel(order)}
                    >
                      Cancel
                    </button>
                  )}
                  {isAdmin && order.status === "CONFIRMED" && (
                    <button
                      className="btn-small"
                      onClick={() => handleAdvanceStatus(order, "SHIPPED")}
                    >
                      Mark shipped
                    </button>
                  )}
                  {isAdmin && order.status === "SHIPPED" && (
                    <button
                      className="btn-small"
                      onClick={() => handleAdvanceStatus(order, "DELIVERED")}
                    >
                      Mark delivered
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
