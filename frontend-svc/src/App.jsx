import React from "react";
import { BrowserRouter as Router, Routes, Route, Navigate, NavLink, useNavigate } from "react-router-dom";
import { AuthProvider, useAuth } from "./context/AuthContext";
import HomePage from "./components/HomePage";
import Users from "./components/Users";
import Products from "./components/Products";
import Orders from "./components/Orders";
import Coupons from "./components/Coupons";
import Login from "./components/Login";
import ForgotPassword from "./components/ForgotPassword";
import ResetPassword from "./components/ResetPassword";
import VerifyEmail from "./components/VerifyEmail";
import Account from "./components/Account";
import "./styles.css";

// Icons
const HomeIcon = () => (
  <svg viewBox="0 0 16 16" fill="currentColor" width="15" height="15">
    <path d="M8.354 1.146a.5.5 0 00-.708 0l-6 6A.5.5 0 002 8v6a.5.5 0 00.5.5h4a.5.5 0 00.5-.5v-3h2v3a.5.5 0 00.5.5h4a.5.5 0 00.5-.5V8a.5.5 0 00-.146-.354L13 6.793V3.5a.5.5 0 00-.5-.5h-1a.5.5 0 00-.5.5v1.293L8.354 1.146z"/>
  </svg>
);
const UsersIcon = () => (
  <svg viewBox="0 0 16 16" fill="currentColor" width="15" height="15">
    <path d="M8 8a3 3 0 100-6 3 3 0 000 6zm-5 6a5 5 0 0110 0H3z"/>
  </svg>
);
const ProductsIcon = () => (
  <svg viewBox="0 0 16 16" fill="currentColor" width="15" height="15">
    <path d="M0 1.5A.5.5 0 01.5 1H2a.5.5 0 01.485.379L2.89 3H14.5a.5.5 0 01.491.592l-1.5 8A.5.5 0 0113 12H4a.5.5 0 01-.491-.408L2.01 3.607 1.61 2H.5a.5.5 0 01-.5-.5zM5 12a2 2 0 100 4 2 2 0 000-4zm7 0a2 2 0 100 4 2 2 0 000-4z"/>
  </svg>
);
const OrdersIcon = () => (
  <svg viewBox="0 0 16 16" fill="currentColor" width="15" height="15">
    <path d="M1 2.5A.5.5 0 011.5 2H3a.5.5 0 01.485.379L3.89 4H14.5a.5.5 0 01.491.592l-1 5A.5.5 0 0113.5 10H5a.5.5 0 01-.491-.408L3.01 4.607 2.61 3H1.5a.5.5 0 01-.5-.5zM5 12a1.5 1.5 0 100 3 1.5 1.5 0 000-3zm7 0a1.5 1.5 0 100 3 1.5 1.5 0 000-3z"/>
  </svg>
);
const LogoutIcon = () => (
  <svg viewBox="0 0 16 16" fill="currentColor" width="14" height="14">
    <path d="M6 12.5a.5.5 0 00.5.5h2a.5.5 0 000-1h-2a.5.5 0 00-.5.5zM6 3a.5.5 0 01.5-.5h2a.5.5 0 010 1h-2A.5.5 0 016 3z"/>
    <path d="M11.854 8.354a.5.5 0 000-.708l-3-3a.5.5 0 10-.708.708L10.293 7.5H1.5a.5.5 0 000 1h8.793l-2.147 2.146a.5.5 0 00.708.708l3-3z"/>
    <path d="M4 2.5a1.5 1.5 0 00-1.5 1.5v8A1.5 1.5 0 004 13.5h1a.5.5 0 000-1H4a.5.5 0 01-.5-.5V4A.5.5 0 014 3.5h1a.5.5 0 000-1H4z"/>
  </svg>
);
const AccountIcon = () => (
  <svg viewBox="0 0 16 16" fill="currentColor" width="15" height="15">
    <path d="M8 8a3 3 0 100-6 3 3 0 000 6zM3 14s-1 0-1-1 1-4 6-4 6 3 6 4-1 1-1 1H3z"/>
  </svg>
);
const CouponsIcon = () => (
  <svg viewBox="0 0 16 16" fill="currentColor" width="15" height="15">
    <path d="M1.5 4.5A1.5 1.5 0 013 3h10a1.5 1.5 0 011.5 1.5v1a.5.5 0 01-.5.5 1.5 1.5 0 000 3 .5.5 0 01.5.5v1A1.5 1.5 0 0113 12.5H3A1.5 1.5 0 011.5 11v-1a.5.5 0 01.5-.5 1.5 1.5 0 000-3 .5.5 0 01-.5-.5v-1zM6 5v1h1V5H6zm0 2.5v1h1v-1H6zM6 10v1h1v-1H6z"/>
  </svg>
);

function ProtectedRoute({ children }) {
  const { isAuthenticated } = useAuth();
  if (!isAuthenticated) return <Navigate to="/login" replace />;
  return children;
}

function Layout() {
  const { user, isAdmin, logout } = useAuth();
  const navigate = useNavigate();

  const handleLogout = () => {
    logout();
    navigate("/login", { replace: true });
  };

  return (
    <div className="app-shell">
      {/* Sidebar */}
      <aside className="sidebar">
        <div className="sidebar-logo">
          <div className="logo-mark">
            <div className="logo-icon">
              <svg viewBox="0 0 16 16" width="14" height="14" fill="#fff">
                <path d="M2 3h5v5H2zm7 0h5v5H9zM2 10h5v4H2zm7 0h5v4H9z"/>
              </svg>
            </div>
            <span className="logo-text">Catalogix</span>
          </div>
        </div>

        <nav className="sidebar-nav">
          <span className="nav-section-label">Pages</span>
          <NavLink to="/" end className={({ isActive }) => `nav-item${isActive ? " active" : ""}`}>
            <HomeIcon /> Home
          </NavLink>

          <span className="nav-section-label" style={{ marginTop: 10 }}>Services</span>
          <NavLink to="/products" className={({ isActive }) => `nav-item${isActive ? " active" : ""}`}>
            <ProductsIcon /> Products
          </NavLink>
          <NavLink to="/orders" className={({ isActive }) => `nav-item${isActive ? " active" : ""}`}>
            <OrdersIcon /> Orders
          </NavLink>
          {isAdmin && (
            <NavLink to="/users" className={({ isActive }) => `nav-item${isActive ? " active" : ""}`}>
              <UsersIcon /> Users
            </NavLink>
          )}
          {isAdmin && (
            <NavLink to="/coupons" className={({ isActive }) => `nav-item${isActive ? " active" : ""}`}>
              <CouponsIcon /> Coupons
            </NavLink>
          )}

          <span className="nav-section-label" style={{ marginTop: 10 }}>Account</span>
          <NavLink to="/account" className={({ isActive }) => `nav-item${isActive ? " active" : ""}`}>
            <AccountIcon /> Account
          </NavLink>
        </nav>

        <div className="sidebar-footer">
          <div className="active-user-pill">
            <div className={`active-dot ${user?.verified === false ? "dot-amber" : "dot-green"}`} />
            <div className="active-user-info">
              <div className="active-user-label">
                {user?.verified === false ? "Email not verified" : `Signed in${isAdmin ? " · admin" : ""}`}
              </div>
              <div className="active-user-name">{user?.name}</div>
            </div>
            <button className="icon-btn logout-btn" onClick={handleLogout} title="Log out" type="button">
              <LogoutIcon />
            </button>
          </div>
        </div>
      </aside>

      {/* Main content */}
      <div className="main-area">
        <Routes>
          <Route index element={<HomePage />} />
          <Route path="products" element={<Products />} />
          <Route path="orders" element={<Orders />} />
          <Route path="account" element={<Account />} />
          {isAdmin && <Route path="users" element={<Users />} />}
          {isAdmin && <Route path="coupons" element={<Coupons />} />}
          <Route path="*" element={<Navigate to="/" replace />} />
        </Routes>
      </div>
    </div>
  );
}

function AppRoutes() {
  const { isAuthenticated } = useAuth();

  return (
    <Routes>
      <Route
        path="/login"
        element={isAuthenticated ? <Navigate to="/" replace /> : <Login />}
      />
      <Route path="/forgot-password" element={<ForgotPassword />} />
      <Route path="/reset-password" element={<ResetPassword />} />
      {/* Reachable whether or not you're logged in — e.g. after changing your
          email in Account settings while still signed in. */}
      <Route path="/verify-email" element={<VerifyEmail />} />
      <Route
        path="/*"
        element={
          <ProtectedRoute>
            <Layout />
          </ProtectedRoute>
        }
      />
    </Routes>
  );
}

export default function App() {
  return (
    <Router future={{ v7_startTransition: true, v7_relativeSplatPath: true }}>
      <AuthProvider>
        <AppRoutes />
      </AuthProvider>
    </Router>
  );
}
