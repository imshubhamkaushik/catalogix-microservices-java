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
  getOrderTracking,
  getOrderInvoice,
  requestReturn,
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
    const timeoutId = setTimeout(onDone, 3000);

    return () => clearTimeout(timeoutId);
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

StatusBadge.propTypes = {
  status: PropTypes.string.isRequired,
};

// -------- Product picker --------

function ProductPicker({ onAdd }) {
  const [query, setQuery] = useState("");
  const [results, setResults] = useState([]);
  const [searching, setSearching] = useState(false);
  const debounceRef = useRef(null);

  useEffect(() => {
    if (debounceRef.current) {
      clearTimeout(debounceRef.current);
    }

    if (!query.trim()) {
      setResults([]);
      return undefined;
    }

    debounceRef.current = setTimeout(async () => {
      setSearching(true);

      try {
        const data = await getProducts({
          search: query,
          page: 0,
          size: 5,
        });

        setResults(data.content || []);
      } catch {
        setResults([]);
      } finally {
        setSearching(false);
      }
    }, 300);

    return () => clearTimeout(debounceRef.current);
  }, [query]);

  const handleAddProduct = (product) => {
    onAdd(product);
    setQuery("");
    setResults([]);
  };

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
          onChange={(event) => setQuery(event.target.value)}
        />
      </div>

      {(searching || results.length > 0) && (
        <div className="picker-results">
          {searching && <div className="picker-result-empty">Searching…</div>}

          {!searching &&
            results.map((product) => (
              <button
                key={product.id}
                type="button"
                className="picker-result-row"
                disabled={(product.stockQuantity ?? 0) === 0}
                onClick={() => handleAddProduct(product)}
              >
                <span className="picker-result-name">{product.name}</span>

                <span className="picker-result-price">
                  {formatPrice(product.price)}
                </span>

                <span
                  className={`badge ${
                    (product.stockQuantity ?? 0) === 0
                      ? "badge-out-of-stock"
                      : "badge-in-stock"
                  }`}
                >
                  {(product.stockQuantity ?? 0) === 0
                    ? "Out of stock"
                    : `${product.stockQuantity} in stock`}
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

ProductPicker.propTypes = {
  onAdd: PropTypes.func.isRequired,
};

// -------- Payment form --------

function PaymentForm({ order, onPaid, onError }) {
  const [method, setMethod] = useState("CARD");
  const [cardLast4, setCardLast4] = useState("");
  const [upiId, setUpiId] = useState("");
  const [paying, setPaying] = useState(false);

  let paymentButtonText = `Pay ${formatPrice(order.totalAmount)}`;

  if (method === "COD") {
    paymentButtonText = `Confirm ${formatPrice(order.totalAmount)} on delivery`;
  }

  if (paying) {
    paymentButtonText = "Processing…";
  }

  const handlePay = async (event) => {
    event.preventDefault();
    setPaying(true);

    let cardValue;
    let upiValue;

    if (method === "CARD") {
      cardValue = cardLast4 || "4242";
    } else if (method === "UPI") {
      upiValue = upiId || "buyer@upi";
    }

    try {
      const result = await payOrder(order.id, method, cardValue, upiValue);

      onPaid(result);
    } catch (error) {
      onError(error.response?.data?.message || "Payment failed.");
    } finally {
      setPaying(false);
    }
  };

  return (
    <form className="payment-form" onSubmit={handlePay}>
      <select
        className="sort-select"
        value={method}
        onChange={(event) => setMethod(event.target.value)}
        disabled={paying}
      >
        <option value="CARD">Card</option>
        <option value="UPI">UPI</option>
        <option value="COD">Cash on Delivery</option>
      </select>

      {method === "CARD" && (
        <input
          className="qty-input"
          style={{ width: 90 }}
          placeholder="Card last 4"
          maxLength={4}
          value={cardLast4}
          onChange={(event) => {
            setCardLast4(event.target.value.replace(/\D/g, ""));
          }}
          disabled={paying}
        />
      )}

      {method === "UPI" && (
        <input
          className="qty-input"
          style={{ width: 140 }}
          placeholder="you@upi"
          value={upiId}
          onChange={(event) => setUpiId(event.target.value)}
          disabled={paying}
        />
      )}

      <button
        className="btn-small btn-primary-small"
        type="submit"
        disabled={paying}
      >
        {paymentButtonText}
      </button>

      <span className="auth-help-text" style={{ margin: 0 }}>
        {method === "CARD" &&
          'Mock payment — any card works, "0000" simulates a decline.'}

        {method === "UPI" &&
          'Mock payment — any VPA works, one starting with "fail@" simulates a decline.'}

        {method === "COD" &&
          "Pay in cash when your order arrives — no payment is captured now."}
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

// -------- Tracking --------

function TrackingPanel({ orderId, onError }) {
  const [tracking, setTracking] = useState(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;

    getOrderTracking(orderId)
      .then((data) => {
        if (!cancelled) {
          setTracking(data);
        }
      })
      .catch(() => {
        if (!cancelled) {
          onError("Failed to load tracking for this order.");
        }
      })
      .finally(() => {
        if (!cancelled) {
          setLoading(false);
        }
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId]);

  if (loading) {
    return (
      <div className="tracking-panel">
        <span className="auth-help-text" style={{ margin: 0 }}>
          Loading tracking…
        </span>
      </div>
    );
  }

  if (!tracking) {
    return null;
  }

  return (
    <div className="tracking-panel">
      {tracking.events.map((event) => {
        const eventKey =
          event.id ?? `${event.status}-${event.createdAt}-${event.note ?? ""}`;

        return (
          <div key={eventKey} className="tracking-step">
            <div className="tracking-step-dot" />

            <div>
              <div className="tracking-step-status">
                {event.status.replace("_", " ")}
              </div>

              {event.note && (
                <div className="tracking-step-note">{event.note}</div>
              )}

              <div className="tracking-step-date">
                {formatDate(event.createdAt)}
              </div>
            </div>
          </div>
        );
      })}
    </div>
  );
}

TrackingPanel.propTypes = {
  orderId: PropTypes.number.isRequired,
  onError: PropTypes.func.isRequired,
};

// -------- Invoice modal --------

function InvoiceModal({ orderId, onClose, onError }) {
  const [invoice, setInvoice] = useState(null);

  useEffect(() => {
    let cancelled = false;

    getOrderInvoice(orderId)
      .then((data) => {
        if (!cancelled) {
          setInvoice(data);
        }
      })
      .catch(() => {
        if (!cancelled) {
          onError("Failed to load the invoice for this order.");
          onClose();
        }
      });

    return () => {
      cancelled = true;
    };
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [orderId]);

  if (!invoice) {
    return null;
  }

  return (
    <div className="modal-overlay">
      <button
        type="button"
        className="modal-backdrop"
        aria-label="Close invoice"
        onClick={onClose}
      />

      <div
        className="modal-card"
        role="dialog"
        aria-modal="true"
        aria-label={`Invoice ${invoice.invoiceNumber}`}
      >
        <div className="modal-header">
          <div>
            <p className="form-panel-label" style={{ marginBottom: 2 }}>
              {invoice.invoiceNumber}
            </p>

            <span className="auth-help-text" style={{ margin: 0 }}>
              {invoice.sellerName}
            </span>
          </div>

          <button
            className="modal-close"
            onClick={onClose}
            title="Close"
            type="button"
          >
            ✕
          </button>
        </div>

        <div className="invoice-meta-row">
          <span>Order</span>
          <span>#{invoice.orderId}</span>
        </div>

        <div className="invoice-meta-row">
          <span>Date</span>
          <span>{formatDate(invoice.orderDate)}</span>
        </div>

        <div className="invoice-meta-row">
          <span>Billed to</span>
          <span>{invoice.customerEmail}</span>
        </div>

        {invoice.billingAddress && (
          <div className="invoice-meta-row">
            <span>Shipping address</span>
            <span>
              {invoice.billingAddress.line1}, {invoice.billingAddress.city},{" "}
              {invoice.billingAddress.state} {invoice.billingAddress.pincode}
            </span>
          </div>
        )}

        <div className="invoice-meta-row">
          <span>Payment</span>
          <span>
            {invoice.paymentMethod}
            {invoice.paymentReference ? ` · ${invoice.paymentReference}` : ""}
          </span>
        </div>

        <table className="invoice-table">
          <thead>
            <tr>
              <th>Item</th>
              <th className="num">Qty</th>
              <th className="num">Unit price</th>
              <th className="num">Subtotal</th>
            </tr>
          </thead>

          <tbody>
            {invoice.items.map((line) => {
              const lineKey =
                line.productId ??
                `${line.productName}-${line.unitPrice}-${line.quantity}`;

              return (
                <tr key={lineKey}>
                  <td>{line.productName}</td>
                  <td className="num">{line.quantity}</td>
                  <td className="num">{formatPrice(line.unitPrice)}</td>
                  <td className="num">{formatPrice(line.subtotal)}</td>
                </tr>
              );
            })}
          </tbody>
        </table>

        <div className="invoice-totals">
          <div className="invoice-totals-row">
            <span>Items subtotal</span>
            <span>{formatPrice(invoice.itemsSubtotal)}</span>
          </div>

          {invoice.discountAmount > 0 && (
            <div className="invoice-totals-row">
              <span>Discount</span>
              <span>-{formatPrice(invoice.discountAmount)}</span>
            </div>
          )}

          <div className="invoice-totals-row">
            <span>Taxable value</span>
            <span>{formatPrice(invoice.taxableValue)}</span>
          </div>

          <div className="invoice-totals-row">
            <span>GST ({invoice.taxRatePercent}%, included)</span>
            <span>{formatPrice(invoice.taxAmount)}</span>
          </div>

          <div className="invoice-totals-row invoice-grand-total">
            <span>Total</span>
            <span>{formatPrice(invoice.totalAmount)}</span>
          </div>
        </div>

        <div
          style={{
            display: "flex",
            gap: 8,
            marginTop: 18,
          }}
        >
          <button
            className="btn-outline"
            onClick={() => globalThis.print()}
            type="button"
          >
            Print / Save as PDF
          </button>

          <button className="btn-outline" onClick={onClose} type="button">
            Close
          </button>
        </div>
      </div>
    </div>
  );
}

InvoiceModal.propTypes = {
  orderId: PropTypes.number.isRequired,
  onClose: PropTypes.func.isRequired,
  onError: PropTypes.func.isRequired,
};

// -------- Return request modal --------

function ReturnRequestModal({ order, onClose, onSubmitted, onError }) {
  const [quantities, setQuantities] = useState(() =>
    Object.fromEntries(order.items.map((item) => [item.productId, 0])),
  );

  const [reason, setReason] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const setQty = (productId, value, max) => {
    const quantity = Math.max(0, Math.min(max, Number(value) || 0));

    setQuantities((previous) => ({
      ...previous,
      [productId]: quantity,
    }));
  };

  const handleSubmit = async (event) => {
    event.preventDefault();

    const items = order.items
      .filter((item) => quantities[item.productId] > 0)
      .map((item) => ({
        productId: item.productId,
        quantity: quantities[item.productId],
      }));

    if (items.length === 0) {
      onError("Choose at least one item to return.");
      return;
    }

    if (!reason.trim()) {
      onError("A reason is required.");
      return;
    }

    setSubmitting(true);

    try {
      await requestReturn(order.id, reason, items);
      onSubmitted();
    } catch (error) {
      onError(
        error.response?.data?.message || "Failed to submit return request.",
      );
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <div className="modal-overlay">
      <button
        type="button"
        className="modal-backdrop"
        aria-label="Close return request"
        onClick={onClose}
        disabled={submitting}
      />

      <div
        className="modal-card"
        role="dialog"
        aria-modal="true"
        aria-label={`Request a return for order ${order.id}`}
      >
        <div className="modal-header">
          <p className="form-panel-label" style={{ marginBottom: 0 }}>
            Request a return — Order #{order.id}
          </p>

          <button
            className="modal-close"
            onClick={onClose}
            title="Close"
            type="button"
            disabled={submitting}
          >
            ✕
          </button>
        </div>

        <form className="auth-form" onSubmit={handleSubmit}>
          <div className="item-list" style={{ marginBottom: 12 }}>
            {order.items.map((item) => (
              <div key={item.productId} className="item-row">
                <div className="item-meta">
                  <div className="item-name">{item.productName}</div>

                  <div className="item-sub">
                    Purchased: {item.quantity} × {formatPrice(item.unitPrice)}
                  </div>
                </div>

                <div className="item-actions">
                  <span className="auth-help-text" style={{ margin: 0 }}>
                    Return qty:
                  </span>

                  <input
                    className="qty-input"
                    type="number"
                    min={0}
                    max={item.quantity}
                    value={quantities[item.productId]}
                    onChange={(event) => {
                      setQty(item.productId, event.target.value, item.quantity);
                    }}
                    disabled={submitting}
                  />
                </div>
              </div>
            ))}
          </div>

          <div className="field-wrap">
            <label className="field-label" htmlFor="return-reason">
              Reason
            </label>

            <textarea
              id="return-reason"
              className="field-input"
              rows={3}
              placeholder="Wrong size, damaged item, changed my mind…"
              value={reason}
              onChange={(event) => setReason(event.target.value)}
              disabled={submitting}
            />
          </div>

          <div
            style={{
              display: "flex",
              gap: 8,
            }}
          >
            <button
              className="form-submit auth-submit"
              type="submit"
              disabled={submitting}
            >
              {submitting ? "Submitting…" : "Submit return request"}
            </button>

            <button
              className="btn-outline"
              type="button"
              onClick={onClose}
              disabled={submitting}
            >
              Cancel
            </button>
          </div>

          <span className="auth-help-text">
            Returns are only accepted within 7 days of delivery. An admin will
            review your request.
          </span>
        </form>
      </div>
    </div>
  );
}

ReturnRequestModal.propTypes = {
  order: PropTypes.shape({
    id: PropTypes.number.isRequired,
    items: PropTypes.arrayOf(
      PropTypes.shape({
        productId: PropTypes.number,
        productName: PropTypes.string,
        quantity: PropTypes.number,
        unitPrice: PropTypes.number,
      }),
    ).isRequired,
  }).isRequired,
  onClose: PropTypes.func.isRequired,
  onSubmitted: PropTypes.func.isRequired,
  onError: PropTypes.func.isRequired,
};

// -------- Orders page --------

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

  const [trackingOpenFor, setTrackingOpenFor] = useState(null);
  const [invoiceOpenFor, setInvoiceOpenFor] = useState(null);
  const [returnOpenFor, setReturnOpenFor] = useState(null);

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
      const data = await getOrders({
        sort: "id,desc",
      });

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
    } catch (requestError) {
      setError(
        requestError.response?.data?.message || "Failed to add to cart.",
      );
    }
  };

  const updateQty = async (productId, quantity) => {
    try {
      setCart(await updateCartItemQuantity(productId, Math.max(1, quantity)));
    } catch (requestError) {
      setError(
        requestError.response?.data?.message || "Failed to update quantity.",
      );
    }
  };

  const removeFromCart = async (productId) => {
    try {
      setCart(await removeCartItem(productId));
    } catch {
      setError("Failed to remove item.");
    }
  };

  const handleApplyCoupon = async (event) => {
    event.preventDefault();

    if (!couponInput.trim()) {
      return;
    }

    setApplyingCoupon(true);
    setError("");

    try {
      setCart(await applyCartCoupon(couponInput.trim().toUpperCase()));

      setToast({
        message: "Coupon applied.",
        type: "success",
      });
    } catch (requestError) {
      setError(
        requestError.response?.data?.message || "That coupon code isn't valid.",
      );
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
    if (!cart || cart.items.length === 0) {
      return;
    }

    setCheckingOut(true);
    setError("");

    try {
      await checkoutCart(crypto.randomUUID());

      setCouponInput("");

      await Promise.all([fetchCart(), fetchOrders()]);

      setToast({
        message: "Order placed — complete payment below to confirm it.",
        type: "success",
      });
    } catch (requestError) {
      setError(
        requestError.response?.data?.message ||
          "Checkout failed — one or more items may be unavailable.",
      );
    } finally {
      setCheckingOut(false);
    }
  };

  const handlePaid = (result) => {
    fetchOrders();

    if (result.payment.status !== "SUCCEEDED") {
      setToast({
        message:
          "Payment declined — the order was cancelled and stock released.",
        type: "error",
      });

      return;
    }

    const isCod = result.order.paymentMethod === "COD";

    let successMessage = "Payment successful — order confirmed!";

    if (isCod) {
      successMessage = "Order confirmed — pay in cash when it arrives.";
    }

    setToast({
      message: successMessage,
      type: "success",
    });
  };

  const handleCancel = async (order) => {
    if (
      !globalThis.confirm(`Cancel order #${order.id}? Stock will be restored.`)
    ) {
      return;
    }

    try {
      await cancelOrder(order.id);
      await fetchOrders();

      setToast({
        message: `Order #${order.id} cancelled.`,
        type: "success",
      });
    } catch (requestError) {
      setError(
        requestError.response?.data?.message || "Failed to cancel order.",
      );
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
    } catch (requestError) {
      setError(
        requestError.response?.data?.message ||
          "Failed to update order status.",
      );
    }
  };

  const toggleTracking = (orderId) => {
    setTrackingOpenFor((currentOrderId) => {
      if (currentOrderId === orderId) {
        return null;
      }

      return orderId;
    });
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
                    onChange={(event) => {
                      updateQty(
                        line.productId,
                        Number.parseInt(event.target.value, 10) || 1,
                      );
                    }}
                  />

                  <span className="cart-line-subtotal">
                    {formatPrice(line.subtotal)}
                  </span>

                  <button
                    className="icon-btn"
                    type="button"
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
                      onChange={(event) => {
                        setCouponInput(event.target.value);
                      }}
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
                  type="button"
                  onClick={handleCheckout}
                  disabled={checkingOut}
                >
                  {checkingOut ? "Placing order…" : "Checkout"}
                </button>
              </div>
            </div>
          )}
        </div>

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
            <div className="skeleton-row" />
            <div className="skeleton-row" />
            <div className="skeleton-row" />
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
                        {" · "}user #{order.userId}
                      </span>
                    )}
                  </div>

                  <div className="item-sub">
                    {order.items
                      .map((item) => `${item.quantity} × ${item.productName}`)
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

                  <div className="order-detail-actions">
                    <button
                      className="btn-small"
                      type="button"
                      onClick={() => toggleTracking(order.id)}
                    >
                      {trackingOpenFor === order.id
                        ? "Hide tracking"
                        : "Track order"}
                    </button>

                    {order.status !== "PENDING_PAYMENT" && (
                      <button
                        className="btn-small"
                        type="button"
                        onClick={() => {
                          setInvoiceOpenFor(order.id);
                        }}
                      >
                        Invoice
                      </button>
                    )}
                  </div>

                  {trackingOpenFor === order.id && (
                    <TrackingPanel orderId={order.id} onError={setError} />
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
                      type="button"
                      onClick={() => handleCancel(order)}
                    >
                      Cancel
                    </button>
                  )}

                  {isAdmin && order.status === "CONFIRMED" && (
                    <button
                      className="btn-small"
                      type="button"
                      onClick={() => {
                        handleAdvanceStatus(order, "SHIPPED");
                      }}
                    >
                      Mark shipped
                    </button>
                  )}

                  {isAdmin && order.status === "SHIPPED" && (
                    <button
                      className="btn-small"
                      type="button"
                      onClick={() => {
                        handleAdvanceStatus(order, "DELIVERED");
                      }}
                    >
                      Mark delivered
                    </button>
                  )}

                  {!isAdmin && order.status === "DELIVERED" && (
                    <button
                      className="btn-small"
                      type="button"
                      onClick={() => {
                        setReturnOpenFor(order);
                      }}
                    >
                      Request return
                    </button>
                  )}
                </div>
              </div>
            ))}
          </div>
        )}
      </div>

      {invoiceOpenFor && (
        <InvoiceModal
          orderId={invoiceOpenFor}
          onClose={() => setInvoiceOpenFor(null)}
          onError={setError}
        />
      )}

      {returnOpenFor && (
        <ReturnRequestModal
          order={returnOpenFor}
          onClose={() => setReturnOpenFor(null)}
          onSubmitted={() => {
            setReturnOpenFor(null);

            setToast({
              message:
                "Return request submitted — you'll hear back once it's reviewed.",
              type: "success",
            });
          }}
          onError={setError}
        />
      )}
    </div>
  );
}
