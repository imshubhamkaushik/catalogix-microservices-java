import React, { useEffect, useState } from "react";
import { searchProducts } from "../api";
export default function SearchPage() {
  const [q, setQ] = useState(""),
    [items, setItems] = useState([]),
    [loading, setLoading] = useState(false),
    [error, setError] = useState("");
  const run = async () => {
    setLoading(true);
    setError("");
    try {
      const p = await searchProducts({ q, size: 24, page: 0 });
      setItems(p.content || []);
    } catch {
      setError("Search service is unavailable.");
    } finally {
      setLoading(false);
    }
  };
  useEffect(() => {
    run();
  }, []);
  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Search</h1>
          <p className="page-subtitle">Event-synchronized catalogue search</p>
        </div>
      </div>
      <div className="page-content">
        <div className="search-toolbar">
          <input
            value={q}
            onChange={(e) => setQ(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && run()}
            placeholder="Search products…"
          />
          <button className="btn-primary" onClick={run}>
            Search
          </button>
        </div>
        {error && <div className="toast toast-error">{error}</div>}
        {loading ? (
          <div className="empty-state">Searching…</div>
        ) : (
          <div className="service-grid">
            {items.map((p) => (
              <div className="service-card" key={p.id}>
                <h3 className="service-card-title">{p.name}</h3>
                <p className="service-card-desc">
                  {p.description || "No description"}
                </p>
                <strong>
                  {new Intl.NumberFormat("en-IN", {
                    style: "currency",
                    currency: "INR",
                  }).format(Number(p.price))}
                </strong>
                <div className="subtle-note">
                  {p.category || "Uncategorized"}
                </div>
              </div>
            ))}
            {items.length === 0 && !error && (
              <div className="empty-state">No published products found.</div>
            )}
          </div>
        )}
      </div>
    </div>
  );
}
