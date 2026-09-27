import React, { useEffect, useState } from "react";
import { getSellerProfile, onboardSeller, requestSellerPayout } from "../api";

const money = (v) =>
  new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 2,
  }).format(Number(v || 0));
export default function SellerDashboard() {
  const [seller, setSeller] = useState(null),
    [loading, setLoading] = useState(true),
    [error, setError] = useState(""),
    [form, setForm] = useState({
      displayName: "",
      businessName: "",
      description: "",
    }),
    [amount, setAmount] = useState(""),
    [busy, setBusy] = useState(false),
    [message, setMessage] = useState("");
  const load = async () => {
    try {
      setLoading(true);
      const x = await getSellerProfile();
      setSeller(x);
      setForm({
        displayName: x?.displayName || "",
        businessName: x?.businessName || "",
        description: x?.description || "",
      });
    } catch (e) {
      setError(
        e?.response?.status === 404
          ? "Create your seller profile to get started."
          : "Failed to load seller profile.",
      );
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    load();
  }, []);
  const save = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await onboardSeller(form);
      setMessage("Seller profile saved.");
      await load();
    } catch (e) {
      setError(e?.response?.data?.message || "Unable to save seller profile.");
    } finally {
      setBusy(false);
    }
  };
  const payout = async (e) => {
    e.preventDefault();
    setBusy(true);
    setError("");
    try {
      await requestSellerPayout(Number(amount), crypto.randomUUID());
      setAmount("");
      setMessage("Payout completed.");
      await load();
    } catch (e) {
      setError(e?.response?.data?.message || "Unable to request payout.");
    } finally {
      setBusy(false);
    }
  };
  if (loading)
    return (
      <div className="page-wrapper">
        <div className="page-content">
          <div className="empty-state">Loading seller dashboard…</div>
        </div>
      </div>
    );
  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Seller dashboard</h1>
          <p className="page-subtitle">
            Manage your marketplace profile, earnings and payouts
          </p>
        </div>
      </div>
      <div className="page-content">
        {error && <div className="toast toast-error">{error}</div>}
        {message && <div className="toast toast-success">{message}</div>}
        <div className="metric-grid">
          <div className="metric-card">
            <span>Gross sales</span>
            <strong>{money(seller?.grossSales)}</strong>
          </div>
          <div className="metric-card">
            <span>Available balance</span>
            <strong>{money(seller?.availableBalance)}</strong>
          </div>
          <div className="metric-card">
            <span>Commission</span>
            <strong>{seller?.commissionRate}%</strong>
          </div>
          <div className="metric-card">
            <span>Status</span>
            <strong>{seller?.status || "PENDING"}</strong>
          </div>
        </div>
        <div className="split-grid">
          <section className="panel">
            <div className="section-header">
              <span className="section-title">Seller profile</span>
            </div>
            <form onSubmit={save} className="stack-form">
              <label>
                Display name
                <input
                  required
                  value={form.displayName}
                  onChange={(e) =>
                    setForm({ ...form, displayName: e.target.value })
                  }
                />
              </label>
              <label>
                Business name
                <input
                  value={form.businessName}
                  onChange={(e) =>
                    setForm({ ...form, businessName: e.target.value })
                  }
                />
              </label>
              <label>
                Description
                <textarea
                  rows="4"
                  value={form.description}
                  onChange={(e) =>
                    setForm({ ...form, description: e.target.value })
                  }
                />
              </label>
              <button className="btn-primary" disabled={busy}>
                {busy ? "Saving…" : "Save profile"}
              </button>
            </form>
          </section>
          <section className="panel">
            <div className="section-header">
              <span className="section-title">Request payout</span>
            </div>
            <form onSubmit={payout} className="stack-form">
              <label>
                Amount
                <input
                  type="number"
                  min="0.01"
                  step="0.01"
                  max={Number(seller?.availableBalance || 0)}
                  required
                  value={amount}
                  onChange={(e) => setAmount(e.target.value)}
                />
              </label>
              <button
                className="btn-primary"
                disabled={busy || seller?.status !== "APPROVED"}
              >
                {seller?.status !== "APPROVED"
                  ? "Awaiting approval"
                  : "Request payout"}
              </button>
            </form>
            <div className="subtle-note">
              Payouts are recorded in the seller ledger so sales, commissions
              and withdrawals remain auditable.
            </div>
          </section>
        </div>
        <section className="panel">
          <div className="section-header">
            <span className="section-title">Payout history</span>
            <span className="section-count">
              {seller?.payouts?.length || 0} payouts
            </span>
          </div>
          {(seller?.payouts || []).length === 0 ? (
            <div className="empty-state">No payouts yet.</div>
          ) : (
            <div className="table-like">
              {seller.payouts.map((p) => (
                <div className="table-row" key={p.id}>
                  <span>{p.reference}</span>
                  <span>{money(p.amount)}</span>
                  <span>{p.status}</span>
                  <span>{new Date(p.createdAt).toLocaleString()}</span>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
