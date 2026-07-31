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
    </AuthProvider>,
  );
}

async function loginFirst() {
  api.login.mockResolvedValue({
    accessToken: "tok",
    accessTokenExpiresInMs: 900000,
    refreshToken: "ref",
    user: {
      id: 1,
      name: "Alice",
      email: "alice@example.com",
      role: "USER",
      verified: false,
    },
  });
  // AuthProvider reads from localStorage on init, so seed it directly rather
  // than going through the login form (Account doesn't render one anyway).
  localStorage.setItem(
    "catalogix.auth",
    JSON.stringify({
      accessToken: "tok",
      accessTokenExpiresInMs: 900000,
      refreshToken: "ref",
      user: {
        id: 1,
        name: "Alice",
        email: "alice@example.com",
        role: "USER",
        verified: false,
      },
    }),
  );
}

describe("Account page", () => {
  beforeEach(async () => {
    localStorage.clear();
    vi.clearAllMocks();
    await loginFirst();
  });

  it("pre-fills the form with the current user's name and email", () => {
    renderAccount();
    expect(screen.getByLabelText(/full name/i)).toHaveValue("Alice");
    expect(screen.getByLabelText(/^email address$/i)).toHaveValue(
      "alice@example.com",
    );
  });

  it("shows an unverified banner with a resend button", () => {
    renderAccount();
    expect(screen.getByText(/isn't verified yet/i)).toBeInTheDocument();
    expect(
      screen.getByRole("button", { name: /resend verification email/i }),
    ).toBeInTheDocument();
  });

  it("calls resendVerification when the resend button is clicked", async () => {
    api.resendVerification.mockResolvedValue(undefined);
    renderAccount();

    await userEvent.click(
      screen.getByRole("button", { name: /resend verification email/i }),
    );

    await waitFor(() =>
      expect(api.resendVerification).toHaveBeenCalledTimes(1),
    );
  });

  it("updates just the name without sending currentPassword", async () => {
    api.updateProfile.mockResolvedValue({
      id: 1,
      name: "Alicia",
      email: "alice@example.com",
      role: "USER",
      verified: false,
    });

    renderAccount();
    const nameInput = screen.getByLabelText(/full name/i);
    await userEvent.clear(nameInput);
    await userEvent.type(nameInput, "Alicia");
    await userEvent.click(
      screen.getByRole("button", { name: /save changes/i }),
    );

    await waitFor(() =>
      expect(api.updateProfile).toHaveBeenCalledWith({ name: "Alicia" }),
    );
  });

  it("includes currentPassword when the email is changed", async () => {
    api.updateProfile.mockResolvedValue({
      id: 1,
      name: "Alice",
      email: "new@example.com",
      role: "USER",
      verified: false,
    });

    renderAccount();
    const emailInput = screen.getByLabelText(/^email address$/i);
    await userEvent.clear(emailInput);
    await userEvent.type(emailInput, "new@example.com");
    await userEvent.type(
      screen.getByLabelText(/current password/i),
      "CorrectPass1",
    );
    await userEvent.click(
      screen.getByRole("button", { name: /save changes/i }),
    );

    await waitFor(() =>
      expect(api.updateProfile).toHaveBeenCalledWith({
        name: "Alice",
        email: "new@example.com",
        currentPassword: "CorrectPass1",
      }),
    );
  });
});
