import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach } from "vitest";
import ProductDetail from "./ProductDetail";
import { AuthProvider } from "../context/AuthContext";
import * as api from "../api";

vi.mock("../api");

const SAMPLE_PRODUCT = {
  id: 1, name: "Phone", description: "A great phone", price: 100,
  category: "electronics", averageRating: 4.5, reviewCount: 2,
};

function renderDetail(product = SAMPLE_PRODUCT, onClose = vi.fn()) {
  return render(
    <AuthProvider>
      <ProductDetail product={product} onClose={onClose} />
    </AuthProvider>
  );
}

describe("ProductDetail modal", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    // Same convention as Account.test.jsx: AuthProvider reads from
    // localStorage on init, so seed a logged-in user (id 1) directly.
    localStorage.setItem("catalogix.auth", JSON.stringify({
      accessToken: "tok", accessTokenExpiresInMs: 900000, refreshToken: "ref",
      user: { id: 1, name: "Alice", email: "alice@example.com", role: "USER", verified: true },
    }));
    api.getProductReviews.mockResolvedValue({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
  });

  it("shows product info and an empty reviews message", async () => {
    renderDetail();

    expect(screen.getByText("Phone")).toBeInTheDocument();
    expect(screen.getByText("A great phone")).toBeInTheDocument();
    expect(await screen.findByText(/no reviews yet — be the first/i)).toBeInTheDocument();
  });

  it("lists existing reviews with the verified purchase badge", async () => {
    api.getProductReviews.mockResolvedValue({
      content: [{
        id: 1, productId: 1, userId: 99, reviewerEmail: "buyer@example.com",
        rating: 5, title: "Great!", body: "Works well.", verifiedPurchase: true,
        createdAt: "2026-01-01T00:00:00Z",
      }],
      page: 0, size: 5, totalElements: 1, totalPages: 1,
    });
    renderDetail();

    expect(await screen.findByText("Great!")).toBeInTheDocument();
    expect(screen.getByText("Verified Purchase")).toBeInTheDocument();
    expect(screen.getByText(/buyer@example\.com/)).toBeInTheDocument();
  });

  it("requires a star rating before submitting", async () => {
    renderDetail();

    await userEvent.click(await screen.findByRole("button", { name: /submit review/i }));

    expect(screen.getByText(/pick a star rating/i)).toBeInTheDocument();
    expect(api.submitReview).not.toHaveBeenCalled();
  });

  it("submits a review with the chosen rating, title, and body", async () => {
    api.submitReview.mockResolvedValue({});
    renderDetail();

    await userEvent.click(screen.getByRole("button", { name: /^4 stars$/i }));
    await userEvent.type(screen.getByLabelText(/title/i), "Pretty good");
    await userEvent.type(screen.getByLabelText(/review/i), "Does the job.");
    await userEvent.click(screen.getByRole("button", { name: /submit review/i }));

    await waitFor(() => expect(api.submitReview).toHaveBeenCalledWith(1, 4, "Pretty good", "Does the job."));
  });

  it("shows a delete button only on the current user's own review", async () => {
    api.getProductReviews.mockResolvedValue({
      content: [
        { id: 1, productId: 1, userId: 1, reviewerEmail: "alice@example.com", rating: 5,
          verifiedPurchase: false, createdAt: "2026-01-01T00:00:00Z" },
        { id: 2, productId: 1, userId: 999, reviewerEmail: "bob@example.com", rating: 3,
          verifiedPurchase: false, createdAt: "2026-01-01T00:00:00Z" },
      ],
      page: 0, size: 5, totalElements: 2, totalPages: 1,
    });
    renderDetail();

    await screen.findByText(/alice@example\.com/);
    // Logged-in test user is id 1 — only alice's review (userId 1) should
    // offer a delete button, not bob's (userId 999).
    expect(screen.getAllByTitle(/delete your review/i)).toHaveLength(1);
  });

  it("calls onClose when the close button is clicked", async () => {
    const onClose = vi.fn();
    renderDetail(SAMPLE_PRODUCT, onClose);

    await userEvent.click(screen.getByTitle("Close"));

    expect(onClose).toHaveBeenCalled();
  });
});
