import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach } from "vitest";
import Products from "./Products";
import { AuthProvider } from "../context/AuthContext";
import * as api from "../api";

vi.mock("../api");

function renderProducts() {
  return render(
    <AuthProvider>
      <Products />
    </AuthProvider>
  );
}

describe("Products page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getProducts.mockResolvedValue({
      content: [{ id: 1, name: "Phone", description: "A phone", price: 100, category: "electronics",
                  stockQuantity: 5, ownerId: 99, averageRating: null, reviewCount: 0 }],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    // Fetched unconditionally on mount (see Products.jsx) — without this,
    // every test below throws on the unmocked call before it can render anything.
    api.getWishlist.mockResolvedValue([]);
  });

  it("adds a product to the cart via the Add to cart button", async () => {
    api.addCartItem.mockResolvedValue({});
    renderProducts();

    await userEvent.click(await screen.findByRole("button", { name: /add to cart/i }));

    await waitFor(() => expect(api.addCartItem).toHaveBeenCalledWith(1, 1));
    expect(await screen.findByText(/added 1 × "phone" to your cart/i)).toBeInTheDocument();
  });

  it("disables the add-to-cart control when out of stock", async () => {
    api.getProducts.mockResolvedValue({
      content: [{ id: 2, name: "Sold Out Widget", description: "", price: 50, category: "misc",
                  stockQuantity: 0, ownerId: 99, averageRating: null, reviewCount: 0 }],
      page: 0, size: 10, totalElements: 1, totalPages: 1,
    });
    renderProducts();

    expect(await screen.findByRole("button", { name: /add to cart/i })).toBeDisabled();
  });

  it("shows \"No reviews yet\" for a product with no rating data", async () => {
    renderProducts();

    expect(await screen.findByText(/no reviews yet/i)).toBeInTheDocument();
  });

  it("saves a product to the wishlist and reflects the new state", async () => {
    api.addWishlistItem.mockResolvedValue({});
    renderProducts();

    const heart = await screen.findByRole("button", { name: /save to wishlist/i });
    await userEvent.click(heart);

    await waitFor(() => expect(api.addWishlistItem).toHaveBeenCalledWith(1));
    expect(await screen.findByRole("button", { name: /remove from wishlist/i })).toBeInTheDocument();
  });

  it("shows a product already on the wishlist as saved from the start", async () => {
    api.getWishlist.mockResolvedValue([{ productId: 1, productName: "Phone" }]);
    renderProducts();

    expect(await screen.findByRole("button", { name: /remove from wishlist/i })).toBeInTheDocument();
  });
});
