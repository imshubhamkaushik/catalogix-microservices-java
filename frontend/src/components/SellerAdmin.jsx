import React, { useEffect, useState } from "react";
import { getSellers, updateSellerStatus } from "../api";

const STATUS_OPTIONS = ["PENDING", "APPROVED", "SUSPENDED"];

export default function SellerAdmin() {
  const [sellers, setSellers] = useState([]);
  const [status, setStatus] = useState("");
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [savingId, setSavingId] = useState(null);

  const load = async () => {
    setLoading(true);
    setError("");
    try {
      setSellers(await getSellers(status || undefined));
    } catch (err) {
      setError(err?.response?.data?.message || "Failed to load sellers.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // status intentionally drives the refresh; load itself is stable for this page.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [status]);

  const changeStatus = async (sellerId, nextStatus) => {
    setSavingId(sellerId);
    setError("");
    try {
      await updateSellerStatus(sellerId, nextStatus);
      await load();
    } catch (err) {
      setError(
        err?.response?.data?.message || "Unable to update seller status.",
      );
    } finally {
      setSavingId(null);
    }
  };

  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Seller management</h1>
          <p className="page-subtitle">
            Approve, suspend and review marketplace sellers
          </p>
        </div>
        <select
          className="compact-select"
          value={status}
          onChange={(e) => setStatus(e.target.value)}
        >
          <option value="">All statuses</option>
          {STATUS_OPTIONS.map((value) => (
            <option key={value} value={value}>
              {value}
            </option>
          ))}
        </select>
      </div>

      <div className="page-content">
        {error && <div className="toast toast-error">{error}</div>}
        <section className="panel">
          {loading ? (
            <div className="empty-state">Loading sellers…</div>
          ) : sellers.length === 0 ? (
            <div className="empty-state">
              No seller profiles match this filter.
            </div>
          ) : (
            <div className="table-like">
              {sellers.map((seller) => (
                <div className="table-row seller-row" key={seller.id}>
                  <div>
                    <strong>{seller.displayName}</strong>
                    <div className="subtle-note">
                      User #{seller.userId}
                      {seller.businessName ? ` · ${seller.businessName}` : ""}
                    </div>
                  </div>
                  <span>{seller.status}</span>
                  <span>{seller.commissionRate}% commission</span>
                  <span>
                    Balance ₹
                    {Number(seller.availableBalance || 0).toLocaleString(
                      "en-IN",
                      { minimumFractionDigits: 2 },
                    )}
                  </span>
                  <div className="inline-actions">
                    {STATUS_OPTIONS.map((next) => (
                      <button
                        key={next}
                        className={
                          next === seller.status
                            ? "btn-outline"
                            : "btn-small btn-primary-small"
                        }
                        disabled={
                          savingId === seller.id || next === seller.status
                        }
                        onClick={() => changeStatus(seller.id, next)}
                        type="button"
                      >
                        {savingId === seller.id && next !== seller.status
                          ? "…"
                          : next}
                      </button>
                    ))}
                  </div>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
