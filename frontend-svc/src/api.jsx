import axios from "axios";
import { getStoredAccessToken, getStoredRefreshToken } from "./context/AuthContext";

// Base URL for backend APIs
// - In Docker/K8s behind the gateway (see gateway/nginx.conf), keep this as ""
//   (same-origin requests — the gateway proxies /users, /products, /orders).
// - For local dev pointing at a separate backend, set VITE_API_BASE_URL in a .env file.
//   Vite exposes env vars via import.meta.env, NOT process.env (that is Create React App syntax).
const API_BASE = "";

const USER_API_BASE = "/users";
const PRODUCT_API_BASE = "/products";
const ORDER_API_BASE = "/orders";

const http = axios.create({ baseURL: API_BASE });

// A separate plain instance for the refresh call itself — it must never go
// through the interceptors below, or a failed refresh would try to refresh
// itself and loop forever.
const refreshClient = axios.create({ baseURL: API_BASE });

http.interceptors.request.use((config) => {
  const token = getStoredAccessToken();
  if (token) {
    config.headers = config.headers || {};
    config.headers.Authorization = `Bearer ${token}`;
  }
  return config;
});

// Shared in-flight refresh promise: if several requests 401 at once (e.g. a
// page that fires 3 requests on load right as the access token expires), they
// all await the same refresh instead of each rotating the refresh token and
// invalidating one another.
let refreshPromise = null;

function performRefresh() {
  if (!refreshPromise) {
    refreshPromise = (async () => {
      const refreshToken = getStoredRefreshToken();
      if (!refreshToken) throw new Error("No refresh token available");
      const res = await refreshClient.post(`${USER_API_BASE}/refresh`, { refreshToken });
      window.dispatchEvent(new CustomEvent("catalogix:tokens-refreshed", { detail: res.data }));
      return res.data.accessToken;
    })().finally(() => {
      refreshPromise = null;
    });
  }
  return refreshPromise;
}

http.interceptors.response.use(
  (res) => res,
  async (err) => {
    const { config, response } = err;
    const isAuthEndpoint = config?.url?.startsWith(`${USER_API_BASE}/login`)
      || config?.url?.startsWith(`${USER_API_BASE}/register`)
      || config?.url?.startsWith(`${USER_API_BASE}/refresh`);

    if (response?.status === 401 && config && !config._retry && !isAuthEndpoint) {
      config._retry = true;
      try {
        const newAccessToken = await performRefresh();
        config.headers = config.headers || {};
        config.headers.Authorization = `Bearer ${newAccessToken}`;
        return http(config);
      } catch {
        window.dispatchEvent(new Event("catalogix:unauthorized"));
        throw err;
      }
    }

    if (response?.status === 401 && (isAuthEndpoint || config?._retry)) {
      window.dispatchEvent(new Event("catalogix:unauthorized"));
    }

    throw err;
  }
);

// -------- AUTH APIs --------

export const login = async (email, password) => {
  const res = await http.post(`${USER_API_BASE}/login`, { email, password });
  return res.data; // { accessToken, accessTokenExpiresInMs, refreshToken, user }
};

export const register = async (name, email, password) => {
  const res = await http.post(`${USER_API_BASE}/register`, { name, email, password });
  return res.data; // { accessToken, accessTokenExpiresInMs, refreshToken, user }
};

export const logout = async (refreshToken) => {
  await http.post(`${USER_API_BASE}/logout`, { refreshToken });
};

export const logoutEverywhere = async () => {
  await http.post(`${USER_API_BASE}/logout-all`);
};

export const forgotPassword = async (email) => {
  await http.post(`${USER_API_BASE}/forgot-password`, { email });
};

export const resetPassword = async (token, newPassword) => {
  await http.post(`${USER_API_BASE}/reset-password`, { token, newPassword });
};

export const verifyEmail = async (token) => {
  await http.get(`${USER_API_BASE}/verify-email`, { params: { token } });
};

export const resendVerification = async () => {
  await http.post(`${USER_API_BASE}/resend-verification`);
};

export const updateProfile = async (updates) => {
  const res = await http.patch(`${USER_API_BASE}/me`, updates);
  return res.data;
};

// -------- USER APIs (admin directory) --------

export const getUsers = async () => {
  const res = await http.get(USER_API_BASE);
  return res.data;
};

export const deleteUser = async (id) => {
  const res = await http.delete(`${USER_API_BASE}/${id}`);
  return res.data;
};

// -------- PRODUCT APIs --------

// params: { search, category, minPrice, maxPrice, sortBy, page, size }
// sortBy: "PRICE_LOW_TO_HIGH" | "PRICE_HIGH_TO_LOW" | "NEWEST" | "NAME_A_TO_Z"
export const getProducts = async (params = {}) => {
  const res = await http.get(PRODUCT_API_BASE, { params });
  return res.data; // { content, page, size, totalElements, totalPages }
};

export const getProduct = async (id) => {
  const res = await http.get(`${PRODUCT_API_BASE}/${id}`);
  return res.data; // includes averageRating (nullable) + reviewCount
};

export const createProduct = async (product) => {
  const res = await http.post(PRODUCT_API_BASE, product);
  return res.data;
};

export const deleteProduct = async (id) => {
  const res = await http.delete(`${PRODUCT_API_BASE}/${id}`);
  return res.data;
};

export const adjustStock = async (id, delta) => {
  const res = await http.patch(`${PRODUCT_API_BASE}/${id}/stock`, { delta });
  return res.data;
};

// -------- WISHLIST APIs --------

const WISHLIST_API_BASE = "/wishlist";

export const getWishlist = async () => {
  const res = await http.get(WISHLIST_API_BASE);
  return res.data; // [{ productId, productName, price, stockQuantity, addedAt }]
};

export const addWishlistItem = async (productId) => {
  const res = await http.post(WISHLIST_API_BASE, { productId });
  return res.data;
};

export const removeWishlistItem = async (productId) => {
  await http.delete(`${WISHLIST_API_BASE}/${productId}`);
};

export const moveWishlistItemToCart = async (productId, quantity = 1) => {
  await http.post(`${WISHLIST_API_BASE}/${productId}/move-to-cart`, { quantity });
};

// -------- REVIEW APIs --------

const REVIEW_API_BASE = "/reviews";

export const getProductReviews = async (productId, params = {}) => {
  const res = await http.get(`${REVIEW_API_BASE}/product/${productId}`, { params });
  return res.data; // { content, page, size, totalElements, totalPages }
};

export const getProductRatingSummary = async (productId) => {
  const res = await http.get(`${REVIEW_API_BASE}/product/${productId}/summary`);
  return res.data; // { productId, averageRating, reviewCount }
};

export const getMyReviews = async () => {
  const res = await http.get(`${REVIEW_API_BASE}/mine`);
  return res.data;
};

// Create-or-update: submitting again for a product you already reviewed
// edits the existing review rather than erroring.
export const submitReview = async (productId, rating, title, body) => {
  const res = await http.post(`${REVIEW_API_BASE}/product/${productId}`, { rating, title, body });
  return res.data; // includes verifiedPurchase
};

export const deleteReview = async (id) => {
  await http.delete(`${REVIEW_API_BASE}/${id}`);
};

// -------- ADDRESS BOOK APIs --------

const ADDRESS_API_BASE = "/users/me/addresses";

export const getAddresses = async () => {
  const res = await http.get(ADDRESS_API_BASE);
  return res.data; // [{ id, label, line1, line2, city, state, pincode, phone, default }]
};

export const createAddress = async (address) => {
  const res = await http.post(ADDRESS_API_BASE, address);
  return res.data;
};

export const updateAddress = async (id, address) => {
  const res = await http.put(`${ADDRESS_API_BASE}/${id}`, address);
  return res.data;
};

export const setDefaultAddress = async (id) => {
  const res = await http.patch(`${ADDRESS_API_BASE}/${id}/default`);
  return res.data;
};

export const deleteAddress = async (id) => {
  await http.delete(`${ADDRESS_API_BASE}/${id}`);
};

// -------- ORDER APIs --------

// items: [{ productId, quantity }]
// idempotencyKey: optional client-generated UUID; passing the same key for a
// retried "place order" click returns the original order instead of creating
// a duplicate — see order-svc's Idempotency-Key header handling.
// addressId: optional — id of a saved address (see ADDRESS APIs above); if
// given, its fields are snapshotted onto the order for shipping/invoicing.
export const createOrder = async (items, idempotencyKey, couponCode, addressId) => {
  const headers = idempotencyKey ? { "Idempotency-Key": idempotencyKey } : undefined;
  const res = await http.post(ORDER_API_BASE, { items, couponCode, addressId }, { headers });
  return res.data;
};

export const getOrders = async (params = {}) => {
  const res = await http.get(ORDER_API_BASE, { params });
  return res.data; // { content, page, size, totalElements, totalPages }
};

export const getOrder = async (id) => {
  const res = await http.get(`${ORDER_API_BASE}/${id}`);
  return res.data; // includes shippingAddress (nullable)
};

export const getOrderTracking = async (id) => {
  const res = await http.get(`${ORDER_API_BASE}/${id}/tracking`);
  return res.data; // { orderId, currentStatus, events: [{ status, note, createdAt }] }
};

export const getOrderInvoice = async (id) => {
  const res = await http.get(`${ORDER_API_BASE}/${id}/invoice`);
  return res.data; // { invoiceNumber, items, taxableValue, taxAmount, totalAmount, ... }
};

export const cancelOrder = async (id) => {
  const res = await http.patch(`${ORDER_API_BASE}/${id}/cancel`);
  return res.data;
};

// method: "CARD" | "UPI" | "COD". cardLast4 "0000" always declines (mock);
// upiId starting with "fail@" always declines (mock). Neither is needed for COD.
export const payOrder = async (id, method, cardLast4, upiId) => {
  const res = await http.post(`${ORDER_API_BASE}/${id}/pay`, { method, cardLast4, upiId });
  return res.data; // { order, payment }
};

// Admin-only: CONFIRMED -> SHIPPED -> DELIVERED.
export const updateOrderStatus = async (id, status) => {
  const res = await http.patch(`${ORDER_API_BASE}/${id}/status`, { status });
  return res.data;
};

// -------- RETURN / REFUND APIs --------

const RETURN_API_BASE = "/returns";

// items: [{ productId, quantity }] — only DELIVERED orders, within the return window.
export const requestReturn = async (orderId, reason, items) => {
  const res = await http.post(`${ORDER_API_BASE}/${orderId}/returns`, { reason, items });
  return res.data;
};

export const getMyReturns = async () => {
  const res = await http.get(`${RETURN_API_BASE}/mine`);
  return res.data;
};

export const getReturn = async (id) => {
  const res = await http.get(`${RETURN_API_BASE}/${id}`);
  return res.data;
};

// Admin-only.
export const getAllReturns = async (params = {}) => {
  const res = await http.get(RETURN_API_BASE, { params });
  return res.data; // { content, page, size, totalElements, totalPages }
};

export const approveReturn = async (id) => {
  const res = await http.post(`${RETURN_API_BASE}/${id}/approve`);
  return res.data;
};

export const rejectReturn = async (id, reason) => {
  const res = await http.post(`${RETURN_API_BASE}/${id}/reject`, { reason });
  return res.data;
};

// -------- CART APIs (server-side, persists across refresh/devices) --------

const CART_API_BASE = "/cart";

export const getCart = async () => {
  const res = await http.get(CART_API_BASE);
  return res.data; // { items, couponCode, subtotal, discountAmount, total }
};

export const addCartItem = async (productId, quantity) => {
  const res = await http.post(`${CART_API_BASE}/items`, { productId, quantity });
  return res.data;
};

export const updateCartItemQuantity = async (productId, quantity) => {
  const res = await http.patch(`${CART_API_BASE}/items/${productId}`, { quantity });
  return res.data;
};

export const removeCartItem = async (productId) => {
  const res = await http.delete(`${CART_API_BASE}/items/${productId}`);
  return res.data;
};

export const applyCartCoupon = async (code) => {
  const res = await http.post(`${CART_API_BASE}/coupon`, { code });
  return res.data;
};

export const removeCartCoupon = async () => {
  const res = await http.delete(`${CART_API_BASE}/coupon`);
  return res.data;
};

// Converts the cart into an order (PENDING_PAYMENT, stock reserved) and
// clears the cart on success — the order still needs payOrder() to complete.
// Lives at /orders/checkout, not /cart/checkout: checkout-svc is the
// orchestrator that actually places the order (talking to catalog-svc,
// inventory-svc, promotions-svc), cart-svc just supplies the contents.
// addressId: optional, same as createOrder's.
export const checkoutCart = async (idempotencyKey, addressId) => {
  const headers = idempotencyKey ? { "Idempotency-Key": idempotencyKey } : undefined;
  const res = await http.post(`${ORDER_API_BASE}/checkout`, { addressId }, { headers });
  return res.data;
};

// -------- COUPON APIs (admin-only management) --------

const COUPON_API_BASE = "/coupons";

export const getCoupons = async () => {
  const res = await http.get(COUPON_API_BASE);
  return res.data;
};

export const createCoupon = async (coupon) => {
  const res = await http.post(COUPON_API_BASE, coupon);
  return res.data;
};

export const deactivateCoupon = async (id) => {
  const res = await http.patch(`${COUPON_API_BASE}/${id}/deactivate`);
  return res.data;
};
