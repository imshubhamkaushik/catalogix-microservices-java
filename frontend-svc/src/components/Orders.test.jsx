import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach } from "vitest";
import Orders from "./Orders";
import { AuthProvider } from "../context/AuthContext";
import * as api from "../api";

vi.mock("../api");

function renderOrders() {
  return render(
    <AuthProvider>
      <Orders />
    </AuthProvider>,
  );
}

const emptyCart = {
  items: [],
  couponCode: null,
  subtotal: 0,
  discountAmount: 0,
  total: 0,
};

describe("Orders page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getCart.mockResolvedValue(emptyCart);
    api.getOrders.mockResolvedValue({
      content: [],
      page: 0,
      size: 20,
      totalElements: 0,
      totalPages: 0,
    });
    api.getProducts.mockResolvedValue({
      content: [],
      page: 0,
      size: 5,
      totalElements: 0,
      totalPages: 0,
    });
  });

  it("shows an empty state when there are no past orders", async () => {
    renderOrders();
    expect(await screen.findByText(/no orders yet/i)).toBeInTheDocument();
  });

  it("lets you search for a product and add it to the server-side cart", async () => {
    api.getProducts.mockResolvedValue({
      content: [{ id: 1, name: "Phone", price: 100, stockQuantity: 5 }],
      page: 0,
      size: 5,
      totalElements: 1,
      totalPages: 1,
    });
    api.addCartItem.mockResolvedValue({
      items: [
        {
          productId: 1,
          productName: "Phone",
          quantity: 1,
          unitPrice: 100,
          subtotal: 100,
          availableStock: 5,
        },
      ],
      couponCode: null,
      subtotal: 100,
      discountAmount: 0,
      total: 100,
    });

    renderOrders();
    await userEvent.type(
      screen.getByPlaceholderText(/search products to add/i),
      "phone",
    );
    const result = await screen.findByText("Phone");
    await userEvent.click(result);

    await waitFor(() => expect(api.addCartItem).toHaveBeenCalledWith(1, 1));
    expect(await screen.findByText(/^total: ₹100/i)).toBeInTheDocument();
  });

  it("applies a coupon code to the cart", async () => {
    api.getCart.mockResolvedValue({
      items: [
        {
          productId: 1,
          productName: "Phone",
          quantity: 1,
          unitPrice: 100,
          subtotal: 100,
          availableStock: 5,
        },
      ],
      couponCode: null,
      subtotal: 100,
      discountAmount: 0,
      total: 100,
    });
    api.applyCartCoupon.mockResolvedValue({
      items: [
        {
          productId: 1,
          productName: "Phone",
          quantity: 1,
          unitPrice: 100,
          subtotal: 100,
          availableStock: 5,
        },
      ],
      couponCode: "SAVE10",
      subtotal: 100,
      discountAmount: 10,
      total: 90,
    });

    renderOrders();
    const couponInput = await screen.findByPlaceholderText(/coupon code/i);
    await userEvent.type(couponInput, "save10");
    await userEvent.click(screen.getByRole("button", { name: /^apply$/i }));

    await waitFor(() =>
      expect(api.applyCartCoupon).toHaveBeenCalledWith("SAVE10"),
    );
    expect(await screen.findByText(/total: ₹90/i)).toBeInTheDocument();
  });

  it("checks out with a generated idempotency key and refreshes cart + order history", async () => {
    api.getCart.mockResolvedValue({
      items: [
        {
          productId: 1,
          productName: "Phone",
          quantity: 1,
          unitPrice: 100,
          subtotal: 100,
          availableStock: 5,
        },
      ],
      couponCode: null,
      subtotal: 100,
      discountAmount: 0,
      total: 100,
    });
    api.checkoutCart.mockResolvedValue({ id: 10, status: "PENDING_PAYMENT" });

    renderOrders();
    await userEvent.click(
      await screen.findByRole("button", { name: /checkout/i }),
    );

    await waitFor(() => expect(api.checkoutCart).toHaveBeenCalledTimes(1));
    const [idempotencyKey] = api.checkoutCart.mock.calls[0];
    expect(typeof idempotencyKey).toBe("string");
    expect(idempotencyKey.length).toBeGreaterThan(10);
    await waitFor(() => expect(api.getOrders).toHaveBeenCalledTimes(2));
  });

  it("shows a payment form for a pending-payment order and completes payment", async () => {
    api.getOrders.mockResolvedValue({
      content: [
        {
          id: 7,
          userId: 1,
          status: "PENDING_PAYMENT",
          totalAmount: 100,
          createdAt: new Date().toISOString(),
          items: [
            {
              productId: 1,
              productName: "Phone",
              quantity: 1,
              unitPrice: 100,
              subtotal: 100,
            },
          ],
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    api.payOrder.mockResolvedValue({
      order: { id: 7, status: "CONFIRMED" },
      payment: { status: "SUCCEEDED" },
    });

    renderOrders();
    await userEvent.click(await screen.findByRole("button", { name: /^pay/i }));

    await waitFor(() =>
      expect(api.payOrder).toHaveBeenCalledWith(7, "MOCK_CARD", "4242"),
    );
    expect(await screen.findByText(/payment successful/i)).toBeInTheDocument();
  });

  it("shows a cancel button for cancellable orders and calls the API", async () => {
    api.getOrders.mockResolvedValue({
      content: [
        {
          id: 8,
          userId: 1,
          status: "CONFIRMED",
          totalAmount: 200,
          createdAt: new Date().toISOString(),
          items: [
            {
              productId: 1,
              productName: "Phone",
              quantity: 2,
              unitPrice: 100,
              subtotal: 200,
            },
          ],
        },
      ],
      page: 0,
      size: 20,
      totalElements: 1,
      totalPages: 1,
    });
    api.cancelOrder.mockResolvedValue({});
    vi.stubGlobal("confirm", () => true);

    renderOrders();
    const cancelBtn = await screen.findByRole("button", { name: /cancel/i });
    await userEvent.click(cancelBtn);

    await waitFor(() => expect(api.cancelOrder).toHaveBeenCalledWith(8));
  });
});
