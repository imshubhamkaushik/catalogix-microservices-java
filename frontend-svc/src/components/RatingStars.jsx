import React from "react";
import PropTypes from "prop-types";

// Five-star display for a product's average rating. Shows "No reviews yet"
// rather than a zero-star row — a product nobody has reviewed isn't the
// same thing as a product rated 0 stars (ratings only ever go 1-5).
// Shared between Products.jsx (product cards) and ProductDetail.jsx (the
// detail modal + individual review rows) — its own file specifically so
// neither of those two ever needs to import from the other.
export default function RatingStars({ averageRating, reviewCount }) {
  if (averageRating == null || !reviewCount) {
    return <span className="rating-none">No reviews yet</span>;
  }
  const rounded = Math.round(averageRating);
  return (
    <span className="rating-stars" title={`${averageRating.toFixed(1)} out of 5`}>
      {[1, 2, 3, 4, 5].map((n) => (
        <svg key={n} viewBox="0 0 16 16" width="12" height="12" fill="currentColor"
          className={n <= rounded ? "star-filled" : "star-empty"}>
          <path d="M8 .5l2.163 4.62 5.087.463-3.85 3.4.983 5.017L8 11.5l-4.383 2.5.983-5.017-3.85-3.4 5.087-.463z" />
        </svg>
      ))}
      <span className="rating-count">({reviewCount})</span>
    </span>
  );
}

RatingStars.propTypes = {
  averageRating: PropTypes.number,
  reviewCount: PropTypes.number,
};
