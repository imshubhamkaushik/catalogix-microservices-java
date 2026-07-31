import React, { useState } from "react";
import { useAuth } from "../context/AuthContext";
import { updateProfile, resendVerification, logoutEverywhere } from "../api";

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
