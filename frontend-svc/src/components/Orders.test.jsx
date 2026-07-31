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

describe("Orders page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
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

  it("lets you search for a product, add it to the cart, and computes the running total", async () => {
    api.getProducts.mockResolvedValue({
      content: [{ id: 1, name: "Phone", price: 100, stockQuantity: 5 }],
      page: 0,
      size: 5,
      totalElements: 1,
      totalPages: 1,
    });

    renderOrders();
    await userEvent.type(
      screen.getByPlaceholderText(/search products to add/i),
      "phone",
    );

    const result = await screen.findByText("Phone");
    await userEvent.click(result);

    expect(await screen.findByText(/total: ₹100/i)).toBeInTheDocument();
  });

  it("won't let you add an out-of-stock product", async () => {
    api.getProducts.mockResolvedValue({
      content: [
        { id: 1, name: "Sold Out Widget", price: 50, stockQuantity: 0 },
      ],
      page: 0,
      size: 5,
      totalElements: 1,
      totalPages: 1,
    });

    renderOrders();
    await userEvent.type(
      screen.getByPlaceholderText(/search products to add/i),
      "widget",
    );

    const result = await screen.findByText("Sold Out Widget");
    expect(result.closest("button")).toBeDisabled();
  });

  it("places an order with a generated idempotency key and refreshes order history", async () => {
    api.getProducts.mockResolvedValue({
      content: [{ id: 1, name: "Phone", price: 100, stockQuantity: 5 }],
      page: 0,
      size: 5,
      totalElements: 1,
      totalPages: 1,
    });
    api.createOrder.mockResolvedValue({ id: 10 });

    renderOrders();
    await userEvent.type(
      screen.getByPlaceholderText(/search products to add/i),
      "phone",
    );
    const result = await screen.findByText("Phone");
    await userEvent.click(result);

    await userEvent.click(
      await screen.findByRole("button", { name: /place order/i }),
    );

    await waitFor(() => expect(api.createOrder).toHaveBeenCalledTimes(1));
    const [items, idempotencyKey] = api.createOrder.mock.calls[0];
    expect(items).toEqual([{ productId: 1, quantity: 1 }]);
    expect(typeof idempotencyKey).toBe("string");
    expect(idempotencyKey.length).toBeGreaterThan(10);

    // Order history reloads after a successful placement.
    await waitFor(() => expect(api.getOrders).toHaveBeenCalledTimes(2));
  });

  it("shows a cancel button for non-cancelled orders and calls the API", async () => {
    api.getOrders.mockResolvedValue({
      content: [
        {
          id: 7,
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

    await waitFor(() => expect(api.cancelOrder).toHaveBeenCalledWith(7));
  });
});
