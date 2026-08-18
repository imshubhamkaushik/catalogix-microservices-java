import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach } from "vitest";
import Wishlist from "./Wishlist";
import { AuthProvider } from "../context/AuthContext";
import * as api from "../api";

vi.mock("../api");

function renderWishlist() {
  return render(
    <AuthProvider>
      <Wishlist />
    </AuthProvider>
  );
}

describe("Wishlist page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it("shows an empty state when nothing is saved", async () => {
    api.getWishlist.mockResolvedValue([]);
    renderWishlist();

    expect(await screen.findByText(/your wishlist is empty/i)).toBeInTheDocument();
  });

  it("lists saved items with price and stock", async () => {
    api.getWishlist.mockResolvedValue([
      { productId: 1, productName: "Phone", price: 100, stockQuantity: 5, addedAt: "2026-01-01T00:00:00Z" },
    ]);
    renderWishlist();

    expect(await screen.findByText("Phone")).toBeInTheDocument();
    expect(screen.getByText(/5 left/i)).toBeInTheDocument();
  });

  it("removes an item from the list", async () => {
    api.getWishlist.mockResolvedValue([
      { productId: 1, productName: "Phone", price: 100, stockQuantity: 5, addedAt: "2026-01-01T00:00:00Z" },
    ]);
    api.removeWishlistItem.mockResolvedValue({});
    renderWishlist();

    await userEvent.click(await screen.findByTitle(/remove from wishlist/i));

    await waitFor(() => expect(api.removeWishlistItem).toHaveBeenCalledWith(1));
    expect(await screen.findByText(/your wishlist is empty/i)).toBeInTheDocument();
  });

  it("moves an item to the cart and removes it from the list", async () => {
    api.getWishlist.mockResolvedValue([
      { productId: 1, productName: "Phone", price: 100, stockQuantity: 5, addedAt: "2026-01-01T00:00:00Z" },
    ]);
    api.moveWishlistItemToCart.mockResolvedValue({});
    renderWishlist();

    await userEvent.click(await screen.findByRole("button", { name: /move to cart/i }));

    await waitFor(() => expect(api.moveWishlistItemToCart).toHaveBeenCalledWith(1, 1));
    expect(await screen.findByText(/moved to your cart/i)).toBeInTheDocument();
  });

  it("disables move-to-cart for an out-of-stock item", async () => {
    api.getWishlist.mockResolvedValue([
      { productId: 1, productName: "Sold Out Widget", price: 50, stockQuantity: 0, addedAt: "2026-01-01T00:00:00Z" },
    ]);
    renderWishlist();

    expect(await screen.findByRole("button", { name: /move to cart/i })).toBeDisabled();
  });
});
