import React, { useEffect, useState, useCallback } from "react";
import PropTypes from "prop-types";
import { getCoupons, createCoupon, deactivateCoupon } from "../api";

function Toast({ message, onDone }) {
  useEffect(() => {
    const t = setTimeout(onDone, 3000);
    return () => clearTimeout(t);
  }, [onDone]);
  return (
    <div className="toast toast-success">
      <div className="toast-dot" />
      {message}
    </div>
  );
}
Toast.propTypes = { message: PropTypes.string.isRequired, onDone: PropTypes.func.isRequired };

function formatDiscount(coupon) {
  return coupon.discountType === "PERCENTAGE"
    ? `${coupon.discountValue}% off`
    : `₹${coupon.discountValue} off`;
}

export default function Coupons() {
  const [coupons, setCoupons] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [toast, setToast] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const [code, setCode] = useState("");
  const [discountType, setDiscountType] = useState("PERCENTAGE");
  const [discountValue, setDiscountValue] = useState("");
  const [maxUses, setMaxUses] = useState("");

  const fetchCoupons = useCallback(async () => {
    setLoading(true);
    setError("");
    try {
      setCoupons(await getCoupons());
    } catch {
      setError("Failed to load coupons.");
    } finally {
      setLoading(false);
    }
  }, []);

  useEffect(() => { fetchCoupons(); }, [fetchCoupons]);

  const handleCreate = async (e) => {
    e.preventDefault();
    if (!code.trim() || !discountValue) return;
    setSubmitting(true);
    setError("");
    try {
      await createCoupon({
        code: code.trim(),
        discountType,
        discountValue: Number.parseFloat(discountValue),
        maxUses: maxUses === "" ? undefined : Number.parseInt(maxUses, 10),
      });
      setCode(""); setDiscountValue(""); setMaxUses("");
      await fetchCoupons();
      setToast("Coupon created.");
    } catch (err) {
      setError(err.response?.data?.message || "Failed to create coupon.");
    } finally {
      setSubmitting(false);
    }
  };

  const handleDeactivate = async (coupon) => {
    if (!globalThis.confirm(`Deactivate coupon "${coupon.code}"? It can't be re-activated.`)) return;
    try {
      await deactivateCoupon(coupon.id);
      await fetchCoupons();
      setToast(`"${coupon.code}" deactivated.`);
    } catch {
      setError("Failed to deactivate coupon.");
    }
  };

  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Coupons</h1>
          <p className="page-subtitle">Admin-managed discount codes</p>
        </div>
      </div>

      <div className="page-content">
        {toast && <Toast message={toast} onDone={() => setToast("")} />}
        {error && <div className="toast toast-error">{error}</div>}

        <div className="form-panel">
          <p className="form-panel-label">Create a coupon</p>
          <form className="form-fields form-fields-4" onSubmit={handleCreate}>
            <div className="field-wrap">
              <label className="field-label" htmlFor="coupon-code">Code</label>
              <input
                id="coupon-code"
                className="field-input"
                placeholder="SAVE10"
                value={code}
                onChange={(e) => setCode(e.target.value.toUpperCase())}
                required
                disabled={submitting}
              />
            </div>
            <div className="field-wrap">
              <label className="field-label" htmlFor="coupon-type">Type</label>
              <select
                id="coupon-type"
                className="field-input"
                value={discountType}
                onChange={(e) => setDiscountType(e.target.value)}
                disabled={submitting}
              >
                <option value="PERCENTAGE">Percentage off</option>
                <option value="FIXED_AMOUNT">Fixed amount off</option>
              </select>
            </div>
            <div className="field-wrap">
              <label className="field-label" htmlFor="coupon-value">
                {discountType === "PERCENTAGE" ? "Percent (e.g. 10)" : "Amount (₹)"}
              </label>
              <input
                id="coupon-value"
                className="field-input"
                type="number"
                min="0.01"
                step="0.01"
                value={discountValue}
                onChange={(e) => setDiscountValue(e.target.value)}
                required
                disabled={submitting}
              />
            </div>
            <div className="field-wrap">
              <label className="field-label" htmlFor="coupon-max-uses">Max uses (optional)</label>
              <input
                id="coupon-max-uses"
                className="field-input"
                type="number"
                min="1"
                placeholder="Unlimited"
                value={maxUses}
                onChange={(e) => setMaxUses(e.target.value)}
                disabled={submitting}
              />
            </div>
            <button className="form-submit" type="submit" disabled={submitting}>
              {submitting ? "Creating…" : "Create coupon"}
            </button>
          </form>
        </div>

        <div className="section-header">
          <div className="section-header-left">
            <span className="section-title">All coupons</span>
            {!loading && <span className="section-count">{coupons.length} total</span>}
          </div>
        </div>

        {loading && (
          <div className="skeleton-list">
            {[1, 2].map((i) => <div key={i} className="skeleton-row" />)}
          </div>
        )}

        {!loading && coupons.length === 0 && (
          <div className="empty-state">
            <p className="empty-title">No coupons yet</p>
            <p className="empty-sub">Create one using the form above.</p>
          </div>
        )}

        {!loading && coupons.length > 0 && (
          <div className="item-list">
            {coupons.map((coupon) => (
              <div key={coupon.id} className="item-row">
                <div className="item-meta">
                  <div className="item-name">{coupon.code}</div>
                  <div className="item-sub">
                    {formatDiscount(coupon)} · used {coupon.usedCount}
                    {coupon.maxUses ? ` / ${coupon.maxUses}` : ""} times
                    {coupon.expiresAt && ` · expires ${new Date(coupon.expiresAt).toLocaleDateString()}`}
                  </div>
                </div>
                <div className="item-actions">
                  <span className={`badge ${coupon.active ? "badge-in-stock" : "badge-out-of-stock"}`}>
                    {coupon.active ? "Active" : "Inactive"}
                  </span>
                  {coupon.active && (
                    <button className="icon-btn" onClick={() => handleDeactivate(coupon)} title="Deactivate">
                      <svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor">
                        <path d="M8 15A7 7 0 108 1a7 7 0 000 14zm0 1A8 8 0 118 0a8 8 0 010 16z"/>
                        <path d="M4 8a.5.5 0 01.5-.5h7a.5.5 0 010 1h-7A.5.5 0 014 8z"/>
                      </svg>
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
