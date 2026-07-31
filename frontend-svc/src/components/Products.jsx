import React, { useEffect, useState, useCallback, useRef } from "react";
import PropTypes from "prop-types";
import { getProducts, createProduct, deleteProduct, createOrder } from "../api";
import { useAuth } from "../context/AuthContext";

const PAGE_SIZE = 10;

// Format number as Indian rupee string e.g. ₹1,899.00
function formatPrice(value) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(value);
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

const ProductIcon = () => (
  <svg viewBox="0 0 16 16" width="15" height="15" fill="currentColor">
    <path d="M0 1.5A.5.5 0 01.5 1H2a.5.5 0 01.485.379L2.89 3H14.5a.5.5 0 01.491.592l-1.5 8A.5.5 0 0113 12H4a.5.5 0 01-.491-.408L2.01 3.607 1.61 2H.5a.5.5 0 01-.5-.5zM5 12a2 2 0 100 4 2 2 0 000-4zm7 0a2 2 0 100 4 2 2 0 000-4z" />
  </svg>
);

function StockBadge({ quantity }) {
  const qty = quantity ?? 0;
  let cls = "badge-in-stock";
  let label = `${qty} in stock`;
  if (qty === 0) {
    cls = "badge-out-of-stock";
    label = "Out of stock";
  } else if (qty <= 5) {
    cls = "badge-low-stock";
    label = `${qty} left`;
  }
  return <span className={`badge ${cls}`}>{label}</span>;
}

StockBadge.propTypes = {
  quantity: PropTypes.number,
};

// Per-row quantity + order control.
function OrderControl({ product, onOrdered, onError }) {
  const [qty, setQty] = useState(1);
  const [placing, setPlacing] = useState(false);
  const outOfStock = (product.stockQuantity ?? 0) === 0;

  const handleOrder = async () => {
    setPlacing(true);
    try {
      await createOrder(
        [{ productId: product.id, quantity: qty }],
        crypto.randomUUID(),
      );
      onOrdered(product, qty);
      setQty(1);
    } catch (err) {
      onError(err.response?.data?.message || "Failed to place order.");
    } finally {
      setPlacing(false);
    }
  };

  return (
    <div className="order-control">
      <input
        className="qty-input"
        type="number"
        min="1"
        max={product.stockQuantity || 1}
        value={qty}
        onChange={(e) =>
          setQty(Math.max(1, Number.parseInt(e.target.value, 10) || 1))
        }
        disabled={outOfStock || placing}
      />
      <button
        className="btn-small btn-primary-small"
        onClick={handleOrder}
        disabled={outOfStock || placing}
      >
        {placing ? "…" : "Order"}
      </button>
    </div>
  );
}

OrderControl.propTypes = {
  product: PropTypes.shape({
    id: PropTypes.number,
    stockQuantity: PropTypes.number,
  }).isRequired,
  onOrdered: PropTypes.func.isRequired,
  onError: PropTypes.func.isRequired,
};

export default function Products() {
  const { user: currentUser, isAdmin } = useAuth();

  const [products, setProducts] = useState([]);
  const [loading, setLoading] = useState(false);
  const [error, setError] = useState("");
  const [toast, setToast] = useState(null);
  const [submitting, setSubmitting] = useState(false);

  // Form state
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [price, setPrice] = useState("");
  const [category, setCategory] = useState("");
  const [stockQuantity, setStockQuantity] = useState("");

  // Search / filter / pagination (server-side)
  const [search, setSearch] = useState("");
  const [categoryFilter, setCategoryFilter] = useState("");
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [totalElements, setTotalElements] = useState(0);
  const debounceRef = useRef(null);

  const fetchProducts = useCallback(
    async (opts = {}) => {
      setLoading(true);
      setError("");
      try {
        const data = await getProducts({
          search: opts.search ?? search,
          category: opts.categoryFilter ?? categoryFilter,
          page: opts.page ?? page,
          size: PAGE_SIZE,
          sort: "id,desc",
        });
        setProducts(data.content || []);
        setTotalPages(data.totalPages || 0);
        setTotalElements(data.totalElements || 0);
      } catch {
        setError("Failed to load products. Is the backend running?");
      } finally {
        setLoading(false);
      }
      // eslint-disable-next-line react-hooks/exhaustive-deps
    },
    [page, search, categoryFilter],
  );

  useEffect(() => {
    fetchProducts();
  }, [page]); // eslint-disable-line react-hooks/exhaustive-deps

  // Debounce search/category changes, then reset to page 0 and refetch.
  useEffect(() => {
    if (debounceRef.current) clearTimeout(debounceRef.current);
    debounceRef.current = setTimeout(() => {
      setPage(0);
      fetchProducts({ page: 0 });
    }, 350);
    return () => clearTimeout(debounceRef.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [search, categoryFilter]);

  const handleAdd = async (e) => {
    e.preventDefault();
    if (!name.trim() || !price) return;
    const numericPrice = Number.parseFloat(price);
    if (Number.isNaN(numericPrice) || numericPrice <= 0) {
      setError("Price must be a positive number.");
      return;
    }
    setSubmitting(true);
    setError("");
    try {
      await createProduct({
        name: name.trim(),
        description: description.trim(),
        price: numericPrice,
        category: category.trim() || undefined,
        stockQuantity:
          stockQuantity === "" ? undefined : Number.parseInt(stockQuantity, 10),
      });
      setName("");
      setDescription("");
      setPrice("");
      setCategory("");
      setStockQuantity("");
      await fetchProducts({ page: 0 });
      setPage(0);
      setToast({ message: "Product added successfully.", type: "success" });
    } catch (err) {
      const msg = err.response?.data?.message || "Failed to add product.";
      setError(msg);
    } finally {
      setSubmitting(false);
    }
  };

  const handleDelete = async (product) => {
    if (!globalThis.confirm(`Remove "${product.name}"? This cannot be undone.`))
      return;
    try {
      await deleteProduct(product.id);
      await fetchProducts();
      setToast({ message: `"${product.name}" removed.`, type: "success" });
    } catch {
      setError("Failed to delete product.");
    }
  };

  const handleOrdered = (product, qty) => {
    setToast({
      message: `Ordered ${qty} × "${product.name}".`,
      type: "success",
    });
    fetchProducts();
  };

  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Products</h1>
          <p className="page-subtitle">Browsing as {currentUser?.name}</p>
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

        {/* Add product form */}
        <div className="form-panel">
          <p className="form-panel-label">Add new product</p>
          <form
            className="form-fields form-fields-product"
            onSubmit={handleAdd}
          >
            <div className="field-wrap field-wide">
              <label className="field-label" htmlFor="prod-name">
                Product name
              </label>
              <input
                id="prod-name"
                className="field-input"
                placeholder="e.g. Wireless headphones"
                value={name}
                onChange={(e) => setName(e.target.value)}
                required
                disabled={submitting}
              />
            </div>
            <div className="field-wrap field-wide">
              <label className="field-label" htmlFor="prod-desc">
                Description (optional)
              </label>
              <input
                id="prod-desc"
                className="field-input"
                placeholder="Short description"
                value={description}
                onChange={(e) => setDescription(e.target.value)}
                disabled={submitting}
              />
            </div>
            <div className="field-wrap">
              <label className="field-label" htmlFor="prod-category">
                Category
              </label>
              <input
                id="prod-category"
                className="field-input"
                placeholder="e.g. electronics"
                value={category}
                onChange={(e) => setCategory(e.target.value)}
                disabled={submitting}
              />
            </div>
            <div className="field-wrap">
              <label className="field-label" htmlFor="prod-stock">
                Initial stock
              </label>
              <input
                id="prod-stock"
                className="field-input"
                type="number"
                min="0"
                step="1"
                placeholder="0"
                value={stockQuantity}
                onChange={(e) => setStockQuantity(e.target.value)}
                disabled={submitting}
              />
            </div>
            <div className="field-wrap">
              <label className="field-label" htmlFor="prod-price">
                Price (₹)
              </label>
              <div className="price-field-wrap">
                <span className="price-prefix">₹</span>
                <input
                  id="prod-price"
                  className="field-input price-input"
                  type="number"
                  placeholder="0.00"
                  min="0.01"
                  step="0.01"
                  value={price}
                  onChange={(e) => setPrice(e.target.value)}
                  required
                  disabled={submitting}
                />
              </div>
            </div>
            <button className="form-submit" type="submit" disabled={submitting}>
              {submitting ? "Saving…" : "Add product"}
            </button>
          </form>
        </div>

        {/* List header */}
        <div className="section-header">
          <div className="section-header-left">
            <span className="section-title">All products</span>
            {!loading && (
              <span className="section-count">{totalElements} items</span>
            )}
          </div>
          <div className="filter-controls">
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
                placeholder="Search products…"
                value={search}
                onChange={(e) => setSearch(e.target.value)}
              />
            </div>
            <input
              className="field-input category-filter-input"
              placeholder="Filter by category…"
              value={categoryFilter}
              onChange={(e) => setCategoryFilter(e.target.value)}
            />
          </div>
        </div>

        {/* Loading skeletons */}
        {loading && (
          <div className="skeleton-list">
            {[1, 2, 3].map((i) => (
              <div key={i} className="skeleton-row" />
            ))}
          </div>
        )}

        {/* Empty state */}
        {!loading && products.length === 0 && (
          <div className="empty-state">
            <div className="empty-icon">
              <ProductIcon />
            </div>
            <p className="empty-title">
              {search || categoryFilter
                ? "No products match your filters"
                : "No products yet"}
            </p>
            <p className="empty-sub">
              {search || categoryFilter
                ? "Try different search terms."
                : "Add your first product using the form above."}
            </p>
          </div>
        )}

        {/* Product rows */}
        {!loading && products.length > 0 && (
          <>
            <div className="item-list">
              {products.map((product) => {
                const canManage =
                  isAdmin || product.ownerId === currentUser?.id;
                return (
                  <div key={product.id} className="item-row">
                    <div className="product-icon-wrap">
                      <ProductIcon />
                    </div>
                    <div className="item-meta">
                      <div className="item-name">{product.name}</div>
                      <div className="item-sub">
                        {product.description || `ID #${product.id}`}
                        <span className="badge badge-category">
                          {product.category}
                        </span>
                      </div>
                    </div>
                    <div className="item-actions item-actions-product">
                      <StockBadge quantity={product.stockQuantity} />
                      <span className="price-tag">
                        {formatPrice(product.price)}
                      </span>
                      <OrderControl
                        product={product}
                        onOrdered={handleOrdered}
                        onError={setError}
                      />
                      {canManage && (
                        <button
                          className="icon-btn"
                          onClick={() => handleDelete(product)}
                          title="Remove product"
                        >
                          <svg
                            viewBox="0 0 16 16"
                            width="12"
                            height="12"
                            fill="currentColor"
                          >
                            <path d="M11 1.5v1h3.5a.5.5 0 010 1H13v9a1 1 0 01-1 1H4a1 1 0 01-1-1v-9H1.5a.5.5 0 010-1H5v-1A1.5 1.5 0 016.5 0h3A1.5 1.5 0 0111 1.5zm-5 0v1h4v-1a.5.5 0 00-.5-.5h-3a.5.5 0 00-.5.5zM5.5 5.5a.5.5 0 00-1 0v6a.5.5 0 001 0v-6zm2.5 0a.5.5 0 00-1 0v6a.5.5 0 001 0v-6zm2.5 0a.5.5 0 00-1 0v6a.5.5 0 001 0v-6z" />
                          </svg>
                        </button>
                      )}
                    </div>
                  </div>
                );
              })}
            </div>

            {/* Pagination */}
            {totalPages > 1 && (
              <div className="pagination">
                <button
                  className="btn-small"
                  onClick={() => setPage((p) => Math.max(0, p - 1))}
                  disabled={page === 0}
                >
                  ← Prev
                </button>
                <span className="pagination-label">
                  Page {page + 1} of {totalPages}
                </span>
                <button
                  className="btn-small"
                  onClick={() =>
                    setPage((p) => Math.min(totalPages - 1, p + 1))
                  }
                  disabled={page >= totalPages - 1}
                >
                  Next →
                </button>
              </div>
            )}
          </>
        )}
      </div>
    </div>
  );
}
