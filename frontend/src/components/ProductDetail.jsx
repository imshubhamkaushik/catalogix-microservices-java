import React, { useEffect, useState, useCallback, useRef } from "react";
import PropTypes from "prop-types";
import { getProductReviews, submitReview, deleteReview } from "../api";
import { useAuth } from "../context/AuthContext";
import RatingStars from "./RatingStars";

function formatPrice(value) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency", currency: "INR", minimumFractionDigits: 0, maximumFractionDigits: 2,
  }).format(value);
}

function formatDate(iso) {
  return new Date(iso).toLocaleDateString(undefined, { year: "numeric", month: "short", day: "numeric" });
}

// Five clickable stars for choosing a 1-5 rating — distinct from Products'
// RatingStars, which only ever displays a value, never collects one.
function StarRatingInput({ value, onChange, disabled }) {
  return (
    <span className="rating-stars" style={{ cursor: disabled ? "default" : "pointer" }}>
      {[1, 2, 3, 4, 5].map((n) => (
        <button
          key={n}
          type="button"
          onClick={() => !disabled && onChange(n)}
          disabled={disabled}
          aria-label={`${n} star${n > 1 ? "s" : ""}`}
          style={{
            background: "transparent",
            border: "none",
            padding: 0,
            cursor: disabled ? "default" : "pointer",
            color: "inherit",
            lineHeight: 0,
          }}
        >
          <svg viewBox="0 0 16 16" width="18" height="18" fill="currentColor"
            className={n <= value ? "star-filled" : "star-empty"}
            aria-hidden="true"
          >
            <path d="M8 .5l2.163 4.62 5.087.463-3.85 3.4.983 5.017L8 11.5l-4.383 2.5.983-5.017-3.85-3.4 5.087-.463z" />
          </svg>
        </button>
      ))}
    </span>
  );
}
StarRatingInput.propTypes = { value: PropTypes.number.isRequired, onChange: PropTypes.func.isRequired, disabled: PropTypes.bool };

function WriteReviewForm({ productId, onSubmitted, onError }) {
  const [rating, setRating] = useState(0);
  const [title, setTitle] = useState("");
  const [body, setBody] = useState("");
  const [submitting, setSubmitting] = useState(false);

  const handleSubmit = async (e) => {
    e.preventDefault();
    if (rating === 0) { onError("Pick a star rating before submitting."); return; }
    setSubmitting(true);
    try {
      await submitReview(productId, rating, title || undefined, body || undefined);
      setRating(0); setTitle(""); setBody("");
      onSubmitted();
    } catch (err) {
      onError(err.response?.data?.message || "Failed to submit review.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <form className="auth-form" onSubmit={handleSubmit} style={{ marginBottom: 18 }}>
      <div className="field-wrap">
        <span className="field-label">Your rating</span>
        <StarRatingInput value={rating} onChange={setRating} disabled={submitting} />
      </div>
      <div className="field-wrap">
        <label className="field-label" htmlFor="review-title">Title (optional)</label>
        <input id="review-title" className="field-input" value={title}
          onChange={(e) => setTitle(e.target.value)} disabled={submitting} maxLength={120} />
      </div>
      <div className="field-wrap">
        <label className="field-label" htmlFor="review-body">Review (optional)</label>
        <textarea id="review-body" className="field-input" rows={3} value={body}
          onChange={(e) => setBody(e.target.value)} disabled={submitting} maxLength={2000} />
      </div>
      <button className="form-submit auth-submit" type="submit" disabled={submitting}>
        {submitting ? "Submitting…" : "Submit review"}
      </button>
      <span className="auth-help-text">
        Already reviewed this product? Submitting again replaces your existing review.
      </span>
    </form>
  );
}
WriteReviewForm.propTypes = { productId: PropTypes.number.isRequired, onSubmitted: PropTypes.func.isRequired, onError: PropTypes.func.isRequired };

function ReviewRow({ review, isOwn, onDeleted, onError }) {
  const [deleting, setDeleting] = useState(false);

  const handleDelete = async () => {
    if (!globalThis.confirm("Delete your review?")) return;
    setDeleting(true);
    try {
      await deleteReview(review.id);
      onDeleted(review.id);
    } catch {
      onError("Failed to delete review.");
    } finally {
      setDeleting(false);
    }
  };

  return (
    <div className="item-row" style={{ alignItems: "flex-start" }}>
      <div className="item-meta">
        <div className="item-name">
          <RatingStars averageRating={review.rating} reviewCount={1} />
          {review.verifiedPurchase && <span className="badge badge-in-stock" style={{ marginLeft: 8 }}>Verified Purchase</span>}
        </div>
        {review.title && <div className="item-name" style={{ fontSize: 13, marginTop: 4 }}>{review.title}</div>}
        {review.body && <div className="item-sub" style={{ whiteSpace: "normal" }}>{review.body}</div>}
        <div className="tracking-step-date" style={{ marginTop: 4 }}>
          {review.reviewerEmail} · {formatDate(review.createdAt)}
        </div>
      </div>
      {isOwn && (
        <button className="icon-btn" onClick={handleDelete} disabled={deleting} title="Delete your review" type="button">
          <svg viewBox="0 0 16 16" width="12" height="12" fill="currentColor">
            <path d="M11 1.5v1h3.5a.5.5 0 010 1H13v9a1 1 0 01-1 1H4a1 1 0 01-1-1v-9H1.5a.5.5 0 010-1H5v-1A1.5 1.5 0 016.5 0h3A1.5 1.5 0 0111 1.5zm-5 0v1h4v-1a.5.5 0 00-.5-.5h-3a.5.5 0 00-.5.5zM5.5 5.5a.5.5 0 00-1 0v6a.5.5 0 001 0v-6zm2.5 0a.5.5 0 00-1 0v6a.5.5 0 001 0v-6zm2.5 0a.5.5 0 00-1 0v6a.5.5 0 001 0v-6z" />
          </svg>
        </button>
      )}
    </div>
  );
}
ReviewRow.propTypes = {
  review: PropTypes.shape({
    id: PropTypes.number, userId: PropTypes.number, rating: PropTypes.number, title: PropTypes.string,
    body: PropTypes.string, verifiedPurchase: PropTypes.bool, reviewerEmail: PropTypes.string, createdAt: PropTypes.string,
  }).isRequired,
  isOwn: PropTypes.bool.isRequired,
  onDeleted: PropTypes.func.isRequired,
  onError: PropTypes.func.isRequired,
};

export default function ProductDetail({ product, onClose }) {
  const { user: currentUser } = useAuth();
  const dialogRef = useRef(null);

  const [reviews, setReviews] = useState([]);
  const [page, setPage] = useState(0);
  const [totalPages, setTotalPages] = useState(0);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState("");
  const [refreshKey, setRefreshKey] = useState(0);

  useEffect(() => {
    const dialog = dialogRef.current;

    if (dialog && !dialog.open) {
      dialog.showModal();
    }

    return () => {
      if (dialog?.open) {
        dialog.close();
      }
    };
  }, []);

  const handleDialogClose = () => {
    onClose();
  };

  const fetchReviews = useCallback(async () => {
    setLoading(true);

    try {
      const data = await getProductReviews(product.id, {
        page,
        size: 5,
        sort: "createdAt,desc",
      });

      setReviews(data.content || []);
      setTotalPages(data.totalPages || 0);
    } catch {
      setError("Failed to load reviews.");
    } finally {
      setLoading(false);
    }
  }, [product.id, page]);

  useEffect(() => {
    fetchReviews();
  }, [fetchReviews, refreshKey]);

  const handleReviewDeleted = (id) => {
    setReviews((previousReviews) =>
      previousReviews.filter((review) => review.id !== id),
    );
  };

  const handleReviewSubmitted = () => {
    setPage(0);
    setRefreshKey((currentKey) => currentKey + 1);
  };

  return (
    <dialog
      ref={dialogRef}
      className="modal-card"
      aria-label={`${product.name} details`}
      onClose={handleDialogClose}
    >
      <div className="modal-header">
        <div>
          <p className="form-panel-label" style={{ marginBottom: 4 }}>
            {product.name}
          </p>

          <RatingStars
            averageRating={product.averageRating}
            reviewCount={product.reviewCount}
          />
        </div>

        <button
          className="modal-close"
          onClick={() => dialogRef.current?.close()}
          title="Close"
          type="button"
          aria-label="Close product details"
        >
          ✕
        </button>
      </div>

      {product.description && (
        <p className="auth-help-text">{product.description}</p>
      )}

      <div className="invoice-meta-row">
        <span>Price</span>
        <span>{formatPrice(product.price)}</span>
      </div>

      <div className="invoice-meta-row">
        <span>Category</span>
        <span>{product.category}</span>
      </div>

      {error && <div className="toast toast-error">{error}</div>}

      <div
        className="form-panel-label"
        style={{ marginTop: 18, marginBottom: 10 }}
      >
        Write a review
      </div>

      <WriteReviewForm
        productId={product.id}
        onSubmitted={handleReviewSubmitted}
        onError={setError}
      />

      <div className="form-panel-label" style={{ marginBottom: 10 }}>
        Reviews
      </div>

      {loading && <p className="auth-help-text">Loading reviews…</p>}

      {!loading && reviews.length === 0 && (
        <p className="auth-help-text">No reviews yet — be the first.</p>
      )}

      {!loading && reviews.length > 0 && (
        <div className="item-list">
          {reviews.map((review) => (
            <ReviewRow
              key={review.id}
              review={review}
              isOwn={currentUser?.id === review.userId}
              onDeleted={handleReviewDeleted}
              onError={setError}
            />
          ))}
        </div>
      )}

      {totalPages > 1 && (
        <div className="pagination">
          <button
            className="btn-small"
            type="button"
            onClick={() =>
              setPage((currentPage) => Math.max(0, currentPage - 1))
            }
            disabled={page === 0}
          >
            ← Prev
          </button>

          <span className="pagination-label">
            Page {page + 1} of {totalPages}
          </span>

          <button
            className="btn-small"
            type="button"
            onClick={() =>
              setPage((currentPage) =>
                Math.min(totalPages - 1, currentPage + 1),
              )
            }
            disabled={page >= totalPages - 1}
          >
            Next →
          </button>
        </div>
      )}

      <div style={{ marginTop: 18 }}>
        <button
          className="btn-outline"
          onClick={() => dialogRef.current?.close()}
          type="button"
        >
          Close
        </button>
      </div>
    </dialog>
  );
}

ProductDetail.propTypes = {
  product: PropTypes.shape({
    id: PropTypes.number.isRequired, name: PropTypes.string, description: PropTypes.string,
    price: PropTypes.number, category: PropTypes.string, averageRating: PropTypes.number, reviewCount: PropTypes.number,
  }).isRequired,
  onClose: PropTypes.func.isRequired,
};
