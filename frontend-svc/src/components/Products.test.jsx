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
    </AuthProvider>,
  );
}

describe("Products page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getProducts.mockResolvedValue({
      content: [
        {
          id: 1,
          name: "Phone",
          description: "A phone",
          price: 100,
          category: "electronics",
          stockQuantity: 5,
          ownerId: 99,
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    });
  });

  it("adds a product to the cart via the Add to cart button", async () => {
    api.addCartItem.mockResolvedValue({});
    renderProducts();

    await userEvent.click(
      await screen.findByRole("button", { name: /add to cart/i }),
    );

    await waitFor(() => expect(api.addCartItem).toHaveBeenCalledWith(1, 1));
    expect(
      await screen.findByText(/added 1 × "phone" to your cart/i),
    ).toBeInTheDocument();
  });

  it("disables the add-to-cart control when out of stock", async () => {
    api.getProducts.mockResolvedValue({
      content: [
        {
          id: 2,
          name: "Sold Out Widget",
          description: "",
          price: 50,
          category: "misc",
          stockQuantity: 0,
          ownerId: 99,
        },
      ],
      page: 0,
      size: 10,
      totalElements: 1,
      totalPages: 1,
    });
    renderProducts();

    expect(
      await screen.findByRole("button", { name: /add to cart/i }),
    ).toBeDisabled();
  });
});
