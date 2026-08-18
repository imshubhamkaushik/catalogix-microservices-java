import React, { useEffect, useState } from "react";
import { useAuth } from "../context/AuthContext";
import {
  updateProfile, resendVerification, logoutEverywhere,
  getAddresses, createAddress, updateAddress, setDefaultAddress, deleteAddress,
} from "../api";

const EMPTY_ADDRESS_FORM = { label: "", line1: "", line2: "", city: "", state: "", pincode: "", phone: "", makeDefault: false };

export default function Account() {
  const { user, updateUser, logout } = useAuth();

  const [name, setName] = useState(user?.name || "");
  const [email, setEmail] = useState(user?.email || "");
  const [newPassword, setNewPassword] = useState("");
  const [currentPassword, setCurrentPassword] = useState("");
  const [submitting, setSubmitting] = useState(false);
  const [error, setError] = useState("");
  const [toast, setToast] = useState("");
  const [resending, setResending] = useState(false);

  // ---- Address book ----
  const [addresses, setAddresses] = useState([]);
  const [addressesLoading, setAddressesLoading] = useState(false);
  const [addressForm, setAddressForm] = useState(EMPTY_ADDRESS_FORM);
  const [editingAddressId, setEditingAddressId] = useState(null);
  const [showAddressForm, setShowAddressForm] = useState(false);
  const [addressSubmitting, setAddressSubmitting] = useState(false);
  const [addressBusyId, setAddressBusyId] = useState(null);

  const fetchAddresses = async () => {
    setAddressesLoading(true);
    try {
      const data = await getAddresses();
      setAddresses(data || []);
    } catch {
      setError("Failed to load your saved addresses.");
    } finally {
      setAddressesLoading(false);
    }
  };

  useEffect(() => { fetchAddresses(); }, []); // eslint-disable-line react-hooks/exhaustive-deps

  const openAddAddressForm = () => {
    setAddressForm(EMPTY_ADDRESS_FORM);
    setEditingAddressId(null);
    setShowAddressForm(true);
  };

  const openEditAddressForm = (address) => {
    setAddressForm({
      label: address.label, line1: address.line1, line2: address.line2 || "",
      city: address.city, state: address.state, pincode: address.pincode,
      phone: address.phone, makeDefault: false,
    });
    setEditingAddressId(address.id);
    setShowAddressForm(true);
  };

  const handleAddressSubmit = async (e) => {
    e.preventDefault();
    setAddressSubmitting(true);
    setError("");
    try {
      if (editingAddressId) {
        await updateAddress(editingAddressId, addressForm);
        setToast("Address updated.");
      } else {
        await createAddress(addressForm);
        setToast("Address added.");
      }
      setShowAddressForm(false);
      await fetchAddresses();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to save address.");
    } finally {
      setAddressSubmitting(false);
    }
  };

  const handleSetDefaultAddress = async (id) => {
    setAddressBusyId(id);
    try {
      await setDefaultAddress(id);
      await fetchAddresses();
    } catch {
      setError("Failed to set default address.");
    } finally {
      setAddressBusyId(null);
    }
  };

  const handleDeleteAddress = async (id) => {
    if (!globalThis.confirm("Delete this address?")) return;
    setAddressBusyId(id);
    try {
      await deleteAddress(id);
      await fetchAddresses();
    } catch {
      setError("Failed to delete address.");
    } finally {
      setAddressBusyId(null);
    }
  };

  const handleSubmit = async (e) => {
    e.preventDefault();
    setSubmitting(true);
    setError("");
    try {
      const updates = { name };
      if (email.trim() !== user?.email) updates.email = email.trim();
      if (newPassword) updates.newPassword = newPassword;
      if (updates.email || updates.newPassword) updates.currentPassword = currentPassword;

      const updated = await updateProfile(updates);
      updateUser(updated);
      setNewPassword("");
      setCurrentPassword("");
      setToast("Profile updated.");
    } catch (err) {
      setError(err.response?.data?.message || "Failed to update profile.");
    } finally {
      setSubmitting(false);
    }
  };

  const handleResend = async () => {
    setResending(true);
    try {
      await resendVerification();
      setToast("Verification email sent — check Mailpit / your inbox.");
    } catch {
      setError("Failed to resend verification email.");
    } finally {
      setResending(false);
    }
  };

  const handleLogoutEverywhere = async () => {
    if (!globalThis.confirm("Log out of every device/session for this account?")) return;
    try {
      await logoutEverywhere();
    } finally {
      await logout();
    }
  };

  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Account</h1>
          <p className="page-subtitle">Manage your profile and sessions</p>
        </div>
      </div>

      <div className="page-content">
        {toast && <div className="toast toast-success"><div className="toast-dot" />{toast}</div>}
        {error && <div className="toast toast-error">{error}</div>}

        {!user?.verified && (
          <div className="toast toast-error" style={{ alignItems: "center" }}>
            <div className="toast-dot" />
            Your email isn't verified yet.
            <button
              className="btn-small"
              style={{ marginLeft: 10 }}
              onClick={handleResend}
              disabled={resending}
              type="button"
            >
              {resending ? "Sending…" : "Resend verification email"}
            </button>
          </div>
        )}

        <div className="form-panel">
          <p className="form-panel-label">Profile</p>
          <form className="auth-form" onSubmit={handleSubmit} style={{ maxWidth: 420 }}>
            <div className="field-wrap">
              <label className="field-label" htmlFor="acct-name">Full name</label>
              <input
                id="acct-name"
                className="field-input"
                value={name}
                onChange={(e) => setName(e.target.value)}
                disabled={submitting}
              />
            </div>
            <div className="field-wrap">
              <label className="field-label" htmlFor="acct-email">Email address</label>
              <input
                id="acct-email"
                className="field-input"
                type="email"
                value={email}
                onChange={(e) => setEmail(e.target.value)}
                disabled={submitting}
              />
            </div>
            <div className="field-wrap">
              <label className="field-label" htmlFor="acct-new-password">New password (optional)</label>
              <input
                id="acct-new-password"
                className="field-input"
                type="password"
                placeholder="Leave blank to keep your current password"
                value={newPassword}
                onChange={(e) => setNewPassword(e.target.value)}
                disabled={submitting}
              />
            </div>
            <div className="field-wrap">
              <label className="field-label" htmlFor="acct-current-password">Current password</label>
              <input
                id="acct-current-password"
                className="field-input"
                type="password"
                placeholder="Required only if changing email or password"
                value={currentPassword}
                onChange={(e) => setCurrentPassword(e.target.value)}
                disabled={submitting}
              />
            </div>
            <button className="form-submit auth-submit" type="submit" disabled={submitting}>
              {submitting ? "Saving…" : "Save changes"}
            </button>
          </form>
        </div>

        <div className="form-panel">
          <p className="form-panel-label">Addresses</p>

          {addressesLoading && <p className="auth-help-text">Loading…</p>}

          {!addressesLoading && addresses.length === 0 && !showAddressForm && (
            <p className="auth-help-text">
              No saved addresses yet. Add one so checkout can ship straight to you.
            </p>
          )}

          {!addressesLoading && addresses.length > 0 && (
            <div className="item-list" style={{ marginBottom: 12 }}>
              {addresses.map((addr) => (
                <div key={addr.id} className="item-row">
                  <div className="item-meta">
                    <div className="item-name">
                      {addr.label}
                      {addr.default && <span className="badge badge-default">Default</span>}
                    </div>
                    <div className="item-sub">
                      {addr.line1}{addr.line2 ? `, ${addr.line2}` : ""}, {addr.city}, {addr.state} {addr.pincode} · {addr.phone}
                    </div>
                  </div>
                  <div className="item-actions">
                    {!addr.default && (
                      <button
                        className="btn-small"
                        onClick={() => handleSetDefaultAddress(addr.id)}
                        disabled={addressBusyId === addr.id}
                        type="button"
                      >
                        Set default
                      </button>
                    )}
                    <button className="btn-small" onClick={() => openEditAddressForm(addr)} type="button">
                      Edit
                    </button>
                    <button
                      className="icon-btn"
                      onClick={() => handleDeleteAddress(addr.id)}
                      disabled={addressBusyId === addr.id}
                      title="Delete address"
                      type="button"
                    >
                      <svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor">
                        <path d="M11 1.5v1h3.5a.5.5 0 010 1H13v9a1 1 0 01-1 1H4a1 1 0 01-1-1v-9H1.5a.5.5 0 010-1H5v-1A1.5 1.5 0 016.5 0h3A1.5 1.5 0 0111 1.5zm-5 0v1h4v-1a.5.5 0 00-.5-.5h-3a.5.5 0 00-.5.5zM5.5 5.5a.5.5 0 00-1 0v6a.5.5 0 001 0v-6zm2.5 0a.5.5 0 00-1 0v6a.5.5 0 001 0v-6zm2.5 0a.5.5 0 00-1 0v6a.5.5 0 001 0v-6z" />
                      </svg>
                    </button>
                  </div>
                </div>
              ))}
            </div>
          )}

          {!showAddressForm && (
            <button className="btn-small" onClick={openAddAddressForm} type="button">
              + Add new address
            </button>
          )}

          {showAddressForm && (
            <form className="auth-form" onSubmit={handleAddressSubmit} style={{ maxWidth: 420, marginTop: addresses.length ? 0 : 8 }}>
              <div className="field-wrap">
                <label className="field-label" htmlFor="addr-label">Label</label>
                <input id="addr-label" className="field-input" placeholder="Home, Office…"
                  value={addressForm.label}
                  onChange={(e) => setAddressForm({ ...addressForm, label: e.target.value })}
                  disabled={addressSubmitting} required />
              </div>
              <div className="field-wrap">
                <label className="field-label" htmlFor="addr-line1">Address line 1</label>
                <input id="addr-line1" className="field-input"
                  value={addressForm.line1}
                  onChange={(e) => setAddressForm({ ...addressForm, line1: e.target.value })}
                  disabled={addressSubmitting} required />
              </div>
              <div className="field-wrap">
                <label className="field-label" htmlFor="addr-line2">Address line 2 (optional)</label>
                <input id="addr-line2" className="field-input"
                  value={addressForm.line2}
                  onChange={(e) => setAddressForm({ ...addressForm, line2: e.target.value })}
                  disabled={addressSubmitting} />
              </div>
              <div className="field-wrap">
                <label className="field-label" htmlFor="addr-city">City</label>
                <input id="addr-city" className="field-input"
                  value={addressForm.city}
                  onChange={(e) => setAddressForm({ ...addressForm, city: e.target.value })}
                  disabled={addressSubmitting} required />
              </div>
              <div className="field-wrap">
                <label className="field-label" htmlFor="addr-state">State</label>
                <input id="addr-state" className="field-input"
                  value={addressForm.state}
                  onChange={(e) => setAddressForm({ ...addressForm, state: e.target.value })}
                  disabled={addressSubmitting} required />
              </div>
              <div className="field-wrap">
                <label className="field-label" htmlFor="addr-pincode">Pincode</label>
                <input id="addr-pincode" className="field-input"
                  value={addressForm.pincode}
                  onChange={(e) => setAddressForm({ ...addressForm, pincode: e.target.value })}
                  disabled={addressSubmitting} required />
              </div>
              <div className="field-wrap">
                <label className="field-label" htmlFor="addr-phone">Phone</label>
                <input id="addr-phone" className="field-input"
                  value={addressForm.phone}
                  onChange={(e) => setAddressForm({ ...addressForm, phone: e.target.value })}
                  disabled={addressSubmitting} required />
              </div>
              {!editingAddressId && (
                <label className="field-wrap" style={{ flexDirection: "row", alignItems: "center", gap: 8 }}>
                  <input type="checkbox" checked={addressForm.makeDefault}
                    onChange={(e) => setAddressForm({ ...addressForm, makeDefault: e.target.checked })}
                    disabled={addressSubmitting} />
                  <span className="field-label" style={{ margin: 0 }}>Make this my default address</span>
                </label>
              )}
              <div style={{ display: "flex", gap: 8 }}>
                <button className="form-submit auth-submit" type="submit" disabled={addressSubmitting}>
                  {(() => {
                    if (addressSubmitting) return "Saving…";
                    if (editingAddressId) return "Save address";
                    return "Add address";
                  })()}
                </button>
                <button className="btn-outline" type="button" onClick={() => setShowAddressForm(false)} disabled={addressSubmitting}>
                  Cancel
                </button>
              </div>
            </form>
          )}
        </div>

        <div className="form-panel">
          <p className="form-panel-label">Sessions</p>
          <p className="auth-help-text">
            Signed in on this device. If you think another device might have access to your account,
            you can sign out everywhere at once.
          </p>
          <button className="btn-small" onClick={handleLogoutEverywhere} type="button">
            Log out of all devices
          </button>
        </div>
      </div>
    </div>
  );
}
