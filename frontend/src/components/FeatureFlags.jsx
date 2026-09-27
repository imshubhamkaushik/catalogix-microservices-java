import React, { useEffect, useState } from "react";
import { getFeatures, setFeature } from "../api";
export default function FeatureFlags() {
  const [flags, setFlags] = useState(null),
    [error, setError] = useState("");
  useEffect(() => {
    getFeatures()
      .then(setFlags)
      .catch(() => setError("Failed to load feature flags."));
  }, []);
  const toggle = async (n) => {
    try {
      setFlags(await setFeature(n, !flags[n]));
    } catch (e) {
      setError(e?.response?.data?.message || "Failed to update flag.");
    }
  };
  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Feature flags</h1>
          <p className="page-subtitle">
            Control marketplace capabilities without redeploying
          </p>
        </div>
      </div>
      <div className="page-content">
        {error && <div className="toast toast-error">{error}</div>}
        <section className="panel">
          {!flags ? (
            <div className="empty-state">Loading flags…</div>
          ) : (
            Object.entries(flags).map(([name, enabled]) => (
              <div className="table-row" key={name}>
                <div>
                  <strong>{name}</strong>
                  <div className="subtle-note">Runtime feature switch</div>
                </div>
                <span className="status-badge">
                  {enabled ? "ENABLED" : "DISABLED"}
                </span>
                <button
                  className={enabled ? "btn-outline" : "btn-primary"}
                  onClick={() => toggle(name)}
                >
                  {enabled ? "Disable" : "Enable"}
                </button>
              </div>
            ))
          )}
        </section>
      </div>
    </div>
  );
}
