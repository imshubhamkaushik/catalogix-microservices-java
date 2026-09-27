import React, { useEffect, useState } from "react";
import { getProduct, getProducts, getRecommendations } from "../api";

const money = (value) =>
  new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    maximumFractionDigits: 2,
  }).format(Number(value || 0));

export default function Recommendations() {
  const [products, setProducts] = useState([]);
  const [recommendations, setRecommendations] = useState({});
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    let cancelled = false;
    (async () => {
      try {
        const page = await getProducts({ page: 0, size: 6, sort: "id,desc" });
        const base = (page.content || []).slice(0, 6);
        if (cancelled) return;
        setProducts(base);

        const pairs = await Promise.all(
          base.map(async (product) => {
            const ids = await getRecommendations(product.id, 4);
            const full = await Promise.all(ids.map((id) => getProduct(id)));
            return [product.id, full.filter(Boolean)];
          }),
        );
        if (!cancelled) setRecommendations(Object.fromEntries(pairs));
      } catch {
        if (!cancelled)
          setError("Recommendations are temporarily unavailable.");
      } finally {
        if (!cancelled) setLoading(false);
      }
    })();
    return () => {
      cancelled = true;
    };
  }, []);

  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Recommendations</h1>
          <p className="page-subtitle">
            Products related through confirmed-order co-occurrence
          </p>
        </div>
      </div>
      <div className="page-content">
        {error && <div className="toast toast-error">{error}</div>}
        {loading ? (
          <div className="empty-state">Building recommendations…</div>
        ) : products.length === 0 ? (
          <div className="empty-state">
            No products are available for recommendations yet.
          </div>
        ) : (
          <div className="stack-form">
            {products.map((product) => {
              const related = recommendations[product.id] || [];
              return (
                <section className="panel" key={product.id}>
                  <div className="section-header">
                    <div>
                      <span className="section-title">You may also like</span>
                      <div className="subtle-note">
                        Based on orders containing {product.name}
                      </div>
                    </div>
                  </div>
                  {related.length === 0 ? (
                    <div className="empty-state compact-empty">
                      Not enough purchase history yet.
                    </div>
                  ) : (
                    <div className="service-grid">
                      {related.map((item) => (
                        <div className="service-card" key={item.id}>
                          <h3 className="service-card-title">{item.name}</h3>
                          <p className="service-card-desc">
                            {item.description || "No description"}
                          </p>
                          <strong>{money(item.price)}</strong>
                          <div className="subtle-note">
                            {item.category || "Uncategorized"}
                          </div>
                        </div>
                      ))}
                    </div>
                  )}
                </section>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
}
