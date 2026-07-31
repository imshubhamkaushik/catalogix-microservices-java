import axios from "axios";
import {
  getStoredAccessToken,
  getStoredRefreshToken,
} from "./context/AuthContext";

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
      const res = await refreshClient.post(`${USER_API_BASE}/refresh`, {
        refreshToken,
      });
      window.dispatchEvent(
        new CustomEvent("catalogix:tokens-refreshed", { detail: res.data }),
      );
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
    const isAuthEndpoint =
      config?.url?.startsWith(`${USER_API_BASE}/login`) ||
      config?.url?.startsWith(`${USER_API_BASE}/register`) ||
      config?.url?.startsWith(`${USER_API_BASE}/refresh`);

    if (
      response?.status === 401 &&
      config &&
      !config._retry &&
      !isAuthEndpoint
    ) {
      config._retry = true;
      try {
        const newAccessToken = await performRefresh();
        config.headers = config.headers || {};
        config.headers.Authorization = `Bearer ${newAccessToken}`;
        return http(config);
      } catch {
        window.dispatchEvent(new Event("catalogix:unauthorized"));
        return Promise.reject(err);
      }
    }

    if (response?.status === 401 && (isAuthEndpoint || config?._retry)) {
      window.dispatchEvent(new Event("catalogix:unauthorized"));
    }

    return Promise.reject(err);
  },
);

// -------- AUTH APIs --------

export const login = async (email, password) => {
  const res = await http.post(`${USER_API_BASE}/login`, { email, password });
  return res.data; // { accessToken, accessTokenExpiresInMs, refreshToken, user }
};

export const register = async (name, email, password) => {
  const res = await http.post(`${USER_API_BASE}/register`, {
    name,
    email,
    password,
  });
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

// params: { search, category, page, size, sort }
export const getProducts = async (params = {}) => {
  const res = await http.get(PRODUCT_API_BASE, { params });
  return res.data; // { content, page, size, totalElements, totalPages }
};

export const getProduct = async (id) => {
  const res = await http.get(`${PRODUCT_API_BASE}/${id}`);
  return res.data;
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

// -------- ORDER APIs --------

// items: [{ productId, quantity }]
// idempotencyKey: optional client-generated UUID; passing the same key for a
// retried "place order" click returns the original order instead of creating
// a duplicate — see order-svc's Idempotency-Key header handling.
export const createOrder = async (items, idempotencyKey) => {
  const headers = idempotencyKey
    ? { "Idempotency-Key": idempotencyKey }
    : undefined;
  const res = await http.post(ORDER_API_BASE, { items }, { headers });
  return res.data;
};

export const getOrders = async (params = {}) => {
  const res = await http.get(ORDER_API_BASE, { params });
  return res.data; // { content, page, size, totalElements, totalPages }
};

export const cancelOrder = async (id) => {
  const res = await http.patch(`${ORDER_API_BASE}/${id}/cancel`);
  return res.data;
};
