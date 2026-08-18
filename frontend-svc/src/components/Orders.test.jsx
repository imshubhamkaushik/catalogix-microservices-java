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
    </AuthProvider>
  );
}

const emptyCart = { items: [], couponCode: null, subtotal: 0, discountAmount: 0, total: 0 };

describe("Orders page", () => {
  beforeEach(() => {
    vi.clearAllMocks();
    api.getCart.mockResolvedValue(emptyCart);
    api.getOrders.mockResolvedValue({ content: [], page: 0, size: 20, totalElements: 0, totalPages: 0 });
    api.getProducts.mockResolvedValue({ content: [], page: 0, size: 5, totalElements: 0, totalPages: 0 });
  });

  it("shows an empty state when there are no past orders", async () => {
    renderOrders();
    expect(await screen.findByText(/no orders yet/i)).toBeInTheDocument();
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

    renderOrders();
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

    renderOrders();
    const couponInput = await screen.findByPlaceholderText(/coupon code/i);
    await userEvent.type(couponInput, "save10");
    await userEvent.click(screen.getByRole("button", { name: /^apply$/i }));

    await waitFor(() => expect(api.applyCartCoupon).toHaveBeenCalledWith("SAVE10"));
    expect(await screen.findByText(/total: ₹90/i)).toBeInTheDocument();
  });

  it("checks out with a generated idempotency key and refreshes cart + order history", async () => {
    api.getCart.mockResolvedValue({
      items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100, availableStock: 5 }],
      couponCode: null, subtotal: 100, discountAmount: 0, total: 100,
    });
    api.checkoutCart.mockResolvedValue({ id: 10, status: "PENDING_PAYMENT" });

    renderOrders();
    await userEvent.click(await screen.findByRole("button", { name: /checkout/i }));

    await waitFor(() => expect(api.checkoutCart).toHaveBeenCalledTimes(1));
    const [idempotencyKey] = api.checkoutCart.mock.calls[0];
    expect(typeof idempotencyKey).toBe("string");
    expect(idempotencyKey.length).toBeGreaterThan(10);
    await waitFor(() => expect(api.getOrders).toHaveBeenCalledTimes(2));
  });

  it("shows a payment form for a pending-payment order and completes a card payment", async () => {
    api.getOrders.mockResolvedValue({
      content: [{
        id: 7, userId: 1, status: "PENDING_PAYMENT", totalAmount: 100,
        createdAt: new Date().toISOString(),
        items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100 }],
      }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    api.payOrder.mockResolvedValue({
      order: { id: 7, status: "CONFIRMED", paymentMethod: "CARD" },
      payment: { status: "SUCCEEDED" },
    });

    renderOrders();
    await userEvent.click(await screen.findByRole("button", { name: /^pay/i }));

    await waitFor(() => expect(api.payOrder).toHaveBeenCalledWith(7, "CARD", "4242", undefined));
    expect(await screen.findByText(/payment successful/i)).toBeInTheDocument();
  });

  it("pays via UPI when that method is selected", async () => {
    api.getOrders.mockResolvedValue({
      content: [{
        id: 7, userId: 1, status: "PENDING_PAYMENT", totalAmount: 100,
        createdAt: new Date().toISOString(),
        items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100 }],
      }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    api.payOrder.mockResolvedValue({
      order: { id: 7, status: "CONFIRMED", paymentMethod: "UPI" },
      payment: { status: "SUCCEEDED" },
    });

    renderOrders();
    await userEvent.selectOptions(await screen.findByDisplayValue(/^card$/i), "UPI");
    await userEvent.click(screen.getByRole("button", { name: /^pay/i }));

    await waitFor(() => expect(api.payOrder).toHaveBeenCalledWith(7, "UPI", undefined, "buyer@upi"));
  });

  it("confirms a COD order without claiming a payment happened", async () => {
    api.getOrders.mockResolvedValue({
      content: [{
        id: 7, userId: 1, status: "PENDING_PAYMENT", totalAmount: 100,
        createdAt: new Date().toISOString(),
        items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100 }],
      }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    api.payOrder.mockResolvedValue({
      order: { id: 7, status: "CONFIRMED", paymentMethod: "COD" },
      payment: { status: "SUCCEEDED" },
    });

    renderOrders();
    await userEvent.selectOptions(await screen.findByDisplayValue(/^card$/i), "COD");
    await userEvent.click(screen.getByRole("button", { name: /confirm.*on delivery/i }));

    await waitFor(() => expect(api.payOrder).toHaveBeenCalledWith(7, "COD", undefined, undefined));
    expect(await screen.findByText(/pay in cash when it arrives/i)).toBeInTheDocument();
  });

  it("shows a cancel button for cancellable orders and calls the API", async () => {
    api.getOrders.mockResolvedValue({
      content: [{
        id: 8, userId: 1, status: "CONFIRMED", totalAmount: 200,
        createdAt: new Date().toISOString(),
        items: [{ productId: 1, productName: "Phone", quantity: 2, unitPrice: 100, subtotal: 200 }],
      }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    api.cancelOrder.mockResolvedValue({});
    vi.stubGlobal("confirm", () => true);

    renderOrders();
    const cancelBtn = await screen.findByRole("button", { name: /cancel/i });
    await userEvent.click(cancelBtn);

    await waitFor(() => expect(api.cancelOrder).toHaveBeenCalledWith(8));
  });

  it("shows and hides the tracking timeline for an order", async () => {
    api.getOrders.mockResolvedValue({
      content: [{
        id: 9, userId: 1, status: "SHIPPED", totalAmount: 100,
        createdAt: new Date().toISOString(),
        items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100 }],
      }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    api.getOrderTracking.mockResolvedValue({
      orderId: 9, currentStatus: "SHIPPED",
      events: [
        { status: "PENDING_PAYMENT", note: "Order placed", createdAt: new Date().toISOString() },
        { status: "CONFIRMED", note: "Payment confirmed", createdAt: new Date().toISOString() },
        { status: "SHIPPED", note: "Order shipped", createdAt: new Date().toISOString() },
      ],
    });

    renderOrders();
    await userEvent.click(await screen.findByRole("button", { name: /^track order$/i }));

    await waitFor(() => expect(api.getOrderTracking).toHaveBeenCalledWith(9));
    expect(await screen.findByText("Order shipped")).toBeInTheDocument();
    expect(screen.getByText(/^CONFIRMED$/)).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /hide tracking/i }));
    expect(screen.queryByText("Order shipped")).not.toBeInTheDocument();
  });

  it("does not offer an invoice for an order that hasn't been paid", async () => {
    api.getOrders.mockResolvedValue({
      content: [{
        id: 10, userId: 1, status: "PENDING_PAYMENT", totalAmount: 100,
        createdAt: new Date().toISOString(),
        items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100 }],
      }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });

    renderOrders();
    await screen.findByText(/order #10/i);
    expect(screen.queryByRole("button", { name: /^invoice$/i })).not.toBeInTheDocument();
  });

  it("opens and closes the invoice modal for a paid order", async () => {
    api.getOrders.mockResolvedValue({
      content: [{
        id: 11, userId: 1, status: "CONFIRMED", totalAmount: 118,
        createdAt: new Date().toISOString(),
        items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100 }],
      }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    api.getOrderInvoice.mockResolvedValue({
      invoiceNumber: "INV-00000011", orderId: 11, orderDate: new Date().toISOString(),
      sellerName: "Catalogix Retail Pvt. Ltd.", customerEmail: "buyer@example.com",
      billingAddress: null,
      items: [{ productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100 }],
      itemsSubtotal: 100, discountAmount: 0, taxableValue: 100, taxRatePercent: 18, taxAmount: 18,
      totalAmount: 118, paymentMethod: "CARD", paymentReference: "MOCK-CARD-abc",
    });

    renderOrders();
    await userEvent.click(await screen.findByRole("button", { name: /^invoice$/i }));

    await waitFor(() => expect(api.getOrderInvoice).toHaveBeenCalledWith(11));
    expect(await screen.findByText("INV-00000011")).toBeInTheDocument();

    await userEvent.click(screen.getByRole("button", { name: /close/i }));
    expect(screen.queryByText("INV-00000011")).not.toBeInTheDocument();
  });

  it("offers a return request only for delivered orders, not others", async () => {
    api.getOrders.mockResolvedValue({
      content: [{
        id: 12, userId: 1, status: "SHIPPED", totalAmount: 100,
        createdAt: new Date().toISOString(),
        items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100 }],
      }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });

    renderOrders();
    await screen.findByText(/order #12/i);
    expect(screen.queryByRole("button", { name: /request return/i })).not.toBeInTheDocument();
  });

  it("submits a return request with the chosen item quantities and reason", async () => {
    api.getOrders.mockResolvedValue({
      content: [{
        id: 13, userId: 1, status: "DELIVERED", totalAmount: 200,
        createdAt: new Date().toISOString(),
        items: [{ productId: 1, productName: "Phone", quantity: 2, unitPrice: 100, subtotal: 200 }],
      }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });
    api.requestReturn.mockResolvedValue({});

    renderOrders();
    await userEvent.click(await screen.findByRole("button", { name: /request return/i }));

    const qtyInput = await screen.findByDisplayValue("0");
    await userEvent.clear(qtyInput);
    await userEvent.type(qtyInput, "1");
    await userEvent.type(screen.getByLabelText(/reason/i), "Wrong size");
    await userEvent.click(screen.getByRole("button", { name: /submit return request/i }));

    await waitFor(() => expect(api.requestReturn).toHaveBeenCalledWith(
      13, "Wrong size", [{ productId: 1, quantity: 1 }]
    ));
    expect(await screen.findByText(/return request submitted/i)).toBeInTheDocument();
  });

  it("requires at least one item selected before submitting a return", async () => {
    api.getOrders.mockResolvedValue({
      content: [{
        id: 14, userId: 1, status: "DELIVERED", totalAmount: 100,
        createdAt: new Date().toISOString(),
        items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 100, subtotal: 100 }],
      }],
      page: 0, size: 20, totalElements: 1, totalPages: 1,
    });

    renderOrders();
    await userEvent.click(await screen.findByRole("button", { name: /request return/i }));
    await userEvent.type(screen.getByLabelText(/reason/i), "Changed my mind");
    await userEvent.click(screen.getByRole("button", { name: /submit return request/i }));

    expect(await screen.findByText(/choose at least one item/i)).toBeInTheDocument();
    expect(api.requestReturn).not.toHaveBeenCalled();
  });
});
