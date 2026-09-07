import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach } from "vitest";
import Returns from "./Returns";
import { AuthProvider } from "../context/AuthContext";
import * as api from "../api";

vi.mock("../api");

function seedUser(role = "USER") {
  localStorage.setItem("catalogix.auth", JSON.stringify({
    accessToken: "tok", accessTokenExpiresInMs: 900000,
    user: { id: 1, name: "Alice", email: "alice@example.com", role, verified: true },
  }));
}

function renderReturns() {
  return render(
    <AuthProvider>
      <Returns />
    </AuthProvider>
  );
}

const SAMPLE_RETURN = {
  id: 5, orderId: 20, userId: 1, reason: "Wrong size", status: "REQUESTED",
  refundAmount: 200, decisionNote: null, createdAt: "2026-01-01T00:00:00Z",
  items: [{ productId: 1, productName: "Phone", quantity: 1, unitPrice: 200, subtotal: 200 }],
};

describe("Returns page", () => {
  beforeEach(() => {
    localStorage.clear();
    vi.clearAllMocks();
    api.getMyReturns.mockResolvedValue([]);
  });

  describe("as a regular customer", () => {
    beforeEach(() => seedUser("USER"));

    it("shows an empty state when there are no returns", async () => {
      renderReturns();
      expect(await screen.findByText(/no return requests yet/i)).toBeInTheDocument();
    });

    it("lists the customer's own returns with status", async () => {
      api.getMyReturns.mockResolvedValue([SAMPLE_RETURN]);
      renderReturns();

      expect(await screen.findByText(/return #5/i)).toBeInTheDocument();
      expect(screen.getByText("REQUESTED")).toBeInTheDocument();
      expect(screen.getByText(/1 × Phone/)).toBeInTheDocument();
    });

    it("does not show the admin review queue", async () => {
      renderReturns();
      await screen.findByText(/no return requests yet/i);
      expect(screen.queryByText(/review queue/i)).not.toBeInTheDocument();
      expect(api.getAllReturns).not.toHaveBeenCalled();
    });
  });

  describe("as an admin", () => {
    beforeEach(() => seedUser("ADMIN"));

    it("shows the review queue defaulted to pending requests", async () => {
      api.getAllReturns.mockResolvedValue({
        content: [SAMPLE_RETURN], page: 0, size: 50, totalElements: 1, totalPages: 1,
      });
      renderReturns();

      await waitFor(() => expect(api.getAllReturns).toHaveBeenCalledWith(
        expect.objectContaining({ status: "REQUESTED" })
      ));
      expect(await screen.findByText(/review queue/i)).toBeInTheDocument();
    });

    it("approves a return", async () => {
      api.getAllReturns.mockResolvedValue({
        content: [SAMPLE_RETURN], page: 0, size: 50, totalElements: 1, totalPages: 1,
      });
      api.approveReturn.mockResolvedValue({});
      renderReturns();

      await userEvent.click(await screen.findByRole("button", { name: /approve/i }));

      await waitFor(() => expect(api.approveReturn).toHaveBeenCalledWith(5));
      expect(await screen.findByText(/approved — refund issued/i)).toBeInTheDocument();
    });

    it("rejects a return with a reason", async () => {
      api.getAllReturns.mockResolvedValue({
        content: [SAMPLE_RETURN], page: 0, size: 50, totalElements: 1, totalPages: 1,
      });
      api.rejectReturn.mockResolvedValue({});
      vi.stubGlobal("prompt", () => "Item shows signs of use");

      renderReturns();
      await userEvent.click(await screen.findByRole("button", { name: /reject/i }));

      await waitFor(() => expect(api.rejectReturn).toHaveBeenCalledWith(5, "Item shows signs of use"));
    });

    it("does not offer approve/reject for an already-decided return", async () => {
      api.getAllReturns.mockResolvedValue({
        content: [{ ...SAMPLE_RETURN, status: "REFUNDED", decisionNote: "Refunded: MOCK-REFUND-xyz" }],
        page: 0, size: 50, totalElements: 1, totalPages: 1,
      });
      renderReturns();

      await screen.findByText(/return #5/i);
      expect(screen.queryByRole("button", { name: /^approve$/i })).not.toBeInTheDocument();
      expect(screen.getByText(/refunded: mock-refund-xyz/i)).toBeInTheDocument();
    });
  });
});
