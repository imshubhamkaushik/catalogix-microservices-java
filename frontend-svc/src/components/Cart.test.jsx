import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach } from "vitest";
import Cart from "./Cart";
import * as api from "../api";

vi.mock("../api");

const mockNavigate = vi.fn();
vi.mock("react-router-dom", async (importOriginal) => {
  const actual = await importOriginal();
  return { ...actual, useNavigate: () => mockNavigate };
});

const emptyCart = { items: [], couponCode: null, subtotal: 0, discountAmount: 0, total: 0 };

function renderCart() {
  return render(<Cart />);
}

describe("Cart page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getCart.mockResolvedValue(emptyCart);
    api.getProducts.mockResolvedValue({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
  });

  it("shows an empty state when the cart has no items", async () => {
    renderCart();
    expect(await screen.findByText(/your cart is empty/i)).toBeInTheDocument();
  });

  it("lets you search for a product and add it to the server-side cart", async () => {
    api.getProducts.mockResolvedValue({
      content: [{ id: 1, name: "Phone", price: 100, stockQuantity: 5 }],
      page: 0, size: 5, totalElements: 1, totalPages: 1,
    });
    api.addCartItem.mockResolvedValue({
      items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100, availableStock: 5 }],
      couponCode: null, subtotal: 100, discountAmount: 0, total: 100,
    });

    renderCart();
    await userEvent.type(screen.getByPlaceholderText(/search products to add/i), "phone");
    const result = await screen.findByText("Phone");
    await userEvent.click(result);

    await waitFor(() => expect(api.addCartItem).toHaveBeenCalledWith(1, 1));
    expect(await screen.findByText(/^total: ₹100/i)).toBeInTheDocument();
  });

  it("applies a coupon code to the cart", async () => {
    api.getCart.mockResolvedValue({
      items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100, availableStock: 5 }],
      couponCode: null, subtotal: 100, discountAmount: 0, total: 100,
    });
    api.applyCartCoupon.mockResolvedValue({
      items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100, availableStock: 5 }],
      couponCode: "SAVE10", subtotal: 100, discountAmount: 10, total: 90,
    });

    renderCart();
    const couponInput = await screen.findByPlaceholderText(/coupon code/i);
    await userEvent.type(couponInput, "save10");
    await userEvent.click(screen.getByRole("button", { name: /^apply$/i }));

    await waitFor(() => expect(api.applyCartCoupon).toHaveBeenCalledWith("SAVE10"));
    expect(await screen.findByText(/total: ₹90/i)).toBeInTheDocument();
  });

  it("removes an item from the cart", async () => {
    api.getCart.mockResolvedValue({
      items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100, availableStock: 5 }],
      couponCode: null, subtotal: 100, discountAmount: 0, total: 100,
    });
    api.removeCartItem.mockResolvedValue(emptyCart);

    renderCart();
    await userEvent.click(await screen.findByTitle(/remove/i));

    await waitFor(() => expect(api.removeCartItem).toHaveBeenCalledWith(1));
    expect(await screen.findByText(/your cart is empty/i)).toBeInTheDocument();
  });

  it("checks out with a generated idempotency key, refreshes the cart, and sends you to Orders", async () => {
    api.getCart.mockResolvedValue({
      items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100, availableStock: 5 }],
      couponCode: null, subtotal: 100, discountAmount: 0, total: 100,
    });
    api.checkoutCart.mockResolvedValue({ id: 10, status: "PENDING_PAYMENT" });

    renderCart();
    await userEvent.click(await screen.findByRole("button", { name: /checkout/i }));

    await waitFor(() => expect(api.checkoutCart).toHaveBeenCalledTimes(1));
    const [idempotencyKey] = api.checkoutCart.mock.calls[0];
    expect(typeof idempotencyKey).toBe("string");
    expect(idempotencyKey.length).toBeGreaterThan(10);
    await waitFor(() => expect(api.getCart).toHaveBeenCalledTimes(2));
    await waitFor(() => expect(mockNavigate).toHaveBeenCalledWith("/orders"));
  });

  it("shows an error and does not navigate away when checkout fails", async () => {
    api.getCart.mockResolvedValue({
      items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100, availableStock: 5 }],
      couponCode: null, subtotal: 100, discountAmount: 0, total: 100,
    });
    api.checkoutCart.mockRejectedValue({
      response: { data: { message: "Phone is out of stock." } },
    });

    renderCart();
    await userEvent.click(await screen.findByRole("button", { name: /checkout/i }));

    expect(await screen.findByText(/phone is out of stock/i)).toBeInTheDocument();
    expect(mockNavigate).not.toHaveBeenCalled();
  });
});
