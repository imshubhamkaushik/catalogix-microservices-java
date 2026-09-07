import { render, screen, waitFor } from "@testing-library/react";
import userEvent from "@testing-library/user-event";
import { describe, it, expect, vi, beforeEach } from "vitest";
import Account from "./Account";
import { AuthProvider } from "../context/AuthContext";
import * as api from "../api";

vi.mock("../api");

function renderAccount() {
  return render(
    <AuthProvider>
      <Account />
    </AuthProvider>
  );
}

async function loginFirst() {
  api.login.mockResolvedValue({
    accessToken: "tok",
    accessTokenExpiresInMs: 900000,
    user: { id: 1, name: "Alice", email: "alice@example.com", role: "USER", verified: false },
  });
  // AuthProvider reads from localStorage on init, so seed it directly rather
  // than going through the login form (Account doesn't render one anyway).
  localStorage.setItem("catalogix.auth", JSON.stringify({
    accessToken: "tok",
    accessTokenExpiresInMs: 900000,
    user: { id: 1, name: "Alice", email: "alice@example.com", role: "USER", verified: false },
  }));
}

describe("Account page", () => {
  beforeEach(async () => {
    localStorage.clear();
    vi.clearAllMocks();
    await loginFirst();
    // Fetched unconditionally on mount (see Account.jsx's address book section).
    api.getAddresses.mockResolvedValue([]);
  });

  it("pre-fills the form with the current user's name and email", async () => {
    renderAccount();
    expect(screen.getByLabelText(/full name/i)).toHaveValue("Alice");
    expect(screen.getByLabelText(/^email address$/i)).toHaveValue("alice@example.com");
    // Lets the address-book effect (fired on mount) settle within this test's
    // act() cycle, rather than resolving after the test has already returned.
    await screen.findByText(/no saved addresses yet/i);
  });

  it("shows an unverified banner with a resend button", async () => {
    renderAccount();
    expect(screen.getByText(/isn't verified yet/i)).toBeInTheDocument();
    expect(screen.getByRole("button", { name: /resend verification email/i })).toBeInTheDocument();
    await screen.findByText(/no saved addresses yet/i);
  });

  it("calls resendVerification when the resend button is clicked", async () => {
    api.resendVerification.mockResolvedValue(undefined);
    renderAccount();

    await userEvent.click(screen.getByRole("button", { name: /resend verification email/i }));

    await waitFor(() => expect(api.resendVerification).toHaveBeenCalledTimes(1));
  });

  it("updates just the name without sending currentPassword", async () => {
    api.updateProfile.mockResolvedValue({
      id: 1, name: "Alicia", email: "alice@example.com", role: "USER", verified: false,
    });

    renderAccount();
    const nameInput = screen.getByLabelText(/full name/i);
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, "Alicia");
    await userEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(api.updateProfile).toHaveBeenCalledWith({ name: "Alicia" }));
  });

  it("includes currentPassword when the email is changed", async () => {
    api.updateProfile.mockResolvedValue({
      id: 1, name: "Alice", email: "new@example.com", role: "USER", verified: false,
    });

    renderAccount();
    const emailInput = screen.getByLabelText(/^email address$/i);
    await userEvent.clear(emailInput);
    await userEvent.type(emailInput, "new@example.com");
    await userEvent.type(screen.getByLabelText(/current password/i), "CorrectPass1");
    await userEvent.click(screen.getByRole("button", { name: /save changes/i }));

    await waitFor(() => expect(api.updateProfile).toHaveBeenCalledWith({
      name: "Alice", email: "new@example.com", currentPassword: "CorrectPass1",
    }));
  });

  it("shows an empty-state message when there are no saved addresses", async () => {
    renderAccount();
    expect(await screen.findByText(/no saved addresses yet/i)).toBeInTheDocument();
  });

  it("lists saved addresses with the default badge", async () => {
    api.getAddresses.mockResolvedValue([
      { id: 1, label: "Home", line1: "221B Baker Street", line2: null, city: "Chandigarh",
        state: "Punjab", pincode: "160001", phone: "+919812345678", default: true },
    ]);
    renderAccount();

    expect(await screen.findByText("Home")).toBeInTheDocument();
    expect(screen.getByText("Default")).toBeInTheDocument();
    expect(screen.getByText(/221B Baker Street/)).toBeInTheDocument();
    // Only address, already default — no "Set default" action needed.
    expect(screen.queryByRole("button", { name: /set default/i })).not.toBeInTheDocument();
  });

  it("adds a new address", async () => {
    api.createAddress.mockResolvedValue({ id: 2, label: "Office" });
    renderAccount();

    await userEvent.click(await screen.findByRole("button", { name: /add new address/i }));
    await userEvent.type(screen.getByLabelText(/^label$/i), "Office");
    await userEvent.type(screen.getByLabelText(/address line 1/i), "1 Tech Park");
    await userEvent.type(screen.getByLabelText(/^city$/i), "Chandigarh");
    await userEvent.type(screen.getByLabelText(/^state$/i), "Punjab");
    await userEvent.type(screen.getByLabelText(/pincode/i), "160101");
    await userEvent.type(screen.getByLabelText(/phone/i), "+919812345678");
    await userEvent.click(screen.getByRole("button", { name: /^add address$/i }));

    await waitFor(() => expect(api.createAddress).toHaveBeenCalledWith(expect.objectContaining({
      label: "Office", line1: "1 Tech Park", city: "Chandigarh", state: "Punjab",
      pincode: "160101", phone: "+919812345678",
    })));
  });

  it("sets an address as default", async () => {
    api.getAddresses.mockResolvedValue([
      { id: 1, label: "Home", line1: "221B Baker Street", city: "Chandigarh",
        state: "Punjab", pincode: "160001", phone: "+919812345678", default: false },
    ]);
    api.setDefaultAddress.mockResolvedValue({});
    renderAccount();

    await userEvent.click(await screen.findByRole("button", { name: /set default/i }));

    await waitFor(() => expect(api.setDefaultAddress).toHaveBeenCalledWith(1));
  });
});
