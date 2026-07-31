import React, { useEffect, useState, useCallback, useRef } from "react";
import PropTypes from "prop-types";
import { getProducts, createOrder, getOrders, cancelOrder } from "../api";
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
    day: "2-digit", month: "short", year: "numeric", hour: "2-digit", minute: "2-digit",
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

function StatusBadge({ status }) {
  const cls = {
    CONFIRMED: "badge-in-stock",
    PENDING: "badge-low-stock",
    CANCELLED: "badge-out-of-stock",
  }[status] || "badge-user";
  return <span className={`badge ${cls}`}>{status}</span>;
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
        <svg viewBox="0 0 16 16" width="13" height="13" fill="currentColor" style={{ opacity: 0.4, flexShrink: 0 }}>
          <path d="M11.742 10.344a6.5 6.5 0 10-1.397 1.398l3.85 3.85a1 1 0 001.415-1.414l-3.868-3.834zm-5.242 1.156a5 5 0 110-10 5 5 0 010 10z" />
        </svg>
        <input
          placeholder="Search products to add to your order…"
          value={query}
          onChange={(e) => setQuery(e.target.value)}
        />
      </div>
      {(searching || results.length > 0) && (
        <div className="picker-results">
          {searching && <div className="picker-result-empty">Searching…</div>}
          {!searching && results.map((p) => (
            <button
              key={p.id}
              type="button"
              className="picker-result-row"
              disabled={(p.stockQuantity ?? 0) === 0}
              onClick={() => { onAdd(p); setQuery(""); setResults([]); }}
            >
              <span className="picker-result-name">{p.name}</span>
              <span className="picker-result-price">{formatPrice(p.price)}</span>
              <span className={`badge ${(p.stockQuantity ?? 0) === 0 ? "badge-out-of-stock" : "badge-in-stock"}`}>
                {(p.stockQuantity ?? 0) === 0 ? "Out of stock" : `${p.stockQuantity} in stock`}
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

export default function Orders() {
  const { isAdmin } = useAuth();

  const [cart, setCart] = useState([]); // [{ productId, name, price, quantity, maxStock }]
  const [placing, setPlacing] = useState(false);

  const [orders, setOrders] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [toast, setToast] = useState(null);

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

  useEffect(() => { fetchOrders(); }, [fetchOrders]);

  const handleAddToCart = (product) => {
    setCart((prev) => {
      const existing = prev.find((l) => l.productId === product.id);
      if (existing) {
        return prev.map((l) =>
          l.productId === product.id
            ? { ...l, quantity: Math.min(l.quantity + 1, product.stockQuantity || l.quantity + 1) }
            : l
        );
      }
      return [...prev, {
        productId: product.id,
        name: product.name,
        price: product.price,
        quantity: 1,
        maxStock: product.stockQuantity ?? 1,
      }];
    });
  };

  const updateQty = (productId, quantity) => {
    setCart((prev) => prev.map((l) =>
      l.productId === productId ? { ...l, quantity: Math.max(1, quantity) } : l
    ));
  };

  const removeFromCart = (productId) => {
    setCart((prev) => prev.filter((l) => l.productId !== productId));
  };

  const cartTotal = cart.reduce((sum, l) => sum + l.price * l.quantity, 0);

  const handlePlaceOrder = async () => {
    if (cart.length === 0) return;
    setPlacing(true);
    setError("");
    // A fresh key per click: if this request is retried (e.g. the response
    // is lost to a network blip and axios/the browser retries), order-svc
    // recognizes the same key and returns the original order instead of
    // creating a second one.
    const idempotencyKey = crypto.randomUUID();
    try {
      await createOrder(cart.map((l) => ({ productId: l.productId, quantity: l.quantity })), idempotencyKey);
      setCart([]);
      await fetchOrders();
      setToast({ message: "Order placed successfully.", type: "success" });
    } catch (err) {
      setError(err.response?.data?.message || "Failed to place order — one or more items may be unavailable.");
    } finally {
      setPlacing(false);
    }
  };

  const handleCancel = async (order) => {
    if (!globalThis.confirm(`Cancel order #${order.id}? Stock will be restored.`)) return;
    try {
      await cancelOrder(order.id);
      await fetchOrders();
      setToast({ message: `Order #${order.id} cancelled.`, type: "success" });
    } catch {
      setError("Failed to cancel order.");
    }
  };

  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Orders</h1>
          <p className="page-subtitle">
            {isAdmin ? "Build a cart and view every order" : "Build a cart and track your orders"}
          </p>
        </div>
      </div>

      <div className="page-content">
        {toast && <Toast message={toast.message} type={toast.type} onDone={() => setToast(null)} />}
        {error && <div className="toast toast-error">{error}</div>}

        {/* Cart builder */}
        <div className="form-panel">
          <p className="form-panel-label">Place a new order</p>
          <ProductPicker onAdd={handleAddToCart} />

          {cart.length > 0 && (
            <div className="cart-list">
              {cart.map((line) => (
                <div key={line.productId} className="cart-line">
                  <span className="cart-line-name">{line.name}</span>
                  <input
                    className="qty-input"
                    type="number"
                    min="1"
                    max={line.maxStock}
                    value={line.quantity}
                    onChange={(e) => updateQty(line.productId, Number.parseInt(e.target.value, 10) || 1)}
                  />
                  <span className="cart-line-subtotal">{formatPrice(line.price * line.quantity)}</span>
                  <button className="icon-btn" onClick={() => removeFromCart(line.productId)} title="Remove">
                    <svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor">
                      <path d="M2.146 2.854a.5.5 0 111.415-1.415L8 6.086l4.44-4.647a.5.5 0 01.708.707L8.707 6.793l4.647 4.647a.5.5 0 01-.708.708L8 7.5l-4.44 4.648a.5.5 0 01-.707-.708L7.293 6.793l-4.647-4.647.5.708z" />
                    </svg>
                  </button>
                </div>
              ))}
              <div className="cart-footer">
                <span className="cart-total">Total: {formatPrice(cartTotal)}</span>
                <button className="form-submit cart-submit" onClick={handlePlaceOrder} disabled={placing}>
                  {placing ? "Placing order…" : "Place order"}
                </button>
              </div>
            </div>
          )}
        </div>

        {/* Order history */}
        <div className="section-header">
          <div className="section-header-left">
            <span className="section-title">{isAdmin ? "All orders" : "Your orders"}</span>
            {!loading && <span className="section-count">{orders.length} orders</span>}
          </div>
        </div>

        {loading && (
          <div className="skeleton-list">
            {[1, 2, 3].map((i) => <div key={i} className="skeleton-row" />)}
          </div>
        )}

        {!loading && orders.length === 0 && (
          <div className="empty-state">
            <div className="empty-icon">
              <svg viewBox="0 0 16 16" width="20" height="20" fill="currentColor">
                <path d="M1 2.5A.5.5 0 011.5 2H3a.5.5 0 01.485.379L3.89 4H14.5a.5.5 0 01.491.592l-1 5A.5.5 0 0113.5 10H5a.5.5 0 01-.491-.408L3.01 4.607 2.61 3H1.5a.5.5 0 01-.5-.5zM5 12a1.5 1.5 0 100 3 1.5 1.5 0 000-3zm7 0a1.5 1.5 0 100 3 1.5 1.5 0 000-3z"/>
              </svg>
            </div>
            <p className="empty-title">No orders yet</p>
            <p className="empty-sub">Search for a product above to build your first order.</p>
          </div>
        )}

        {!loading && orders.length > 0 && (
          <div className="item-list">
            {orders.map((order) => (
              <div key={order.id} className="item-row order-row">
                <div className="item-meta">
                  <div className="item-name">
                    Order #{order.id}
                    {isAdmin && <span className="item-name-hint"> · user #{order.userId}</span>}
                  </div>
                  <div className="item-sub">
                    {order.items.map((i) => `${i.quantity} × ${i.productName}`).join(", ")}
                  </div>
                  <div className="item-sub item-sub-faint">{formatDate(order.createdAt)}</div>
                </div>
                <div className="item-actions">
                  <StatusBadge status={order.status} />
                  <span className="price-tag">{formatPrice(order.totalAmount)}</span>
                  {order.status !== "CANCELLED" && (
                    <button className="btn-small" onClick={() => handleCancel(order)}>
                      Cancel
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
