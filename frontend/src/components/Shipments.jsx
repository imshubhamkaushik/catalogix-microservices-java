import React, { useEffect, useState } from "react";
import {
  getAllShipments,
  getMyShipments,
  getSellerShipments,
  updateShipmentStatus,
} from "../api";
import { useAuth } from "../context/AuthContext";

const NEXT = {
  CREATED: ["PACKED", "CANCELLED"],
  PACKED: ["SHIPPED", "CANCELLED"],
  SHIPPED: ["OUT_FOR_DELIVERY"],
  OUT_FOR_DELIVERY: ["DELIVERED"],
};

export default function Shipments() {
  const { isSeller, isAdmin } = useAuth();
  const [items, setItems] = useState([]);
  const [error, setError] = useState("");
  const [loading, setLoading] = useState(true);

  const load = async () => {
    setLoading(true);
    setError("");
    try {
      const data = isAdmin
        ? await getAllShipments()
        : isSeller
          ? await getSellerShipments()
          : await getMyShipments();
      setItems(data || []);
    } catch (err) {
      setError(err?.response?.data?.message || "Failed to load shipments.");
    } finally {
      setLoading(false);
    }
  };

  useEffect(() => {
    load();
    // auth state is stable while this page is mounted.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const canUpdate = isSeller || isAdmin;

  const changeStatus = async (shipmentId, nextStatus) => {
    if (!nextStatus) return;
    setError("");
    try {
      await updateShipmentStatus(shipmentId, nextStatus);
      await load();
    } catch (err) {
      setError(err?.response?.data?.message || "Status update failed.");
    }
  };

  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Shipments</h1>
          <p className="page-subtitle">
            Track marketplace fulfillment from packing through delivery
          </p>
        </div>
      </div>
      <div className="page-content">
        {error && <div className="toast toast-error">{error}</div>}
        <section className="panel">
          {loading ? (
            <div className="empty-state">Loading shipments…</div>
          ) : items.length === 0 ? (
            <div className="empty-state">No shipments found yet.</div>
          ) : (
            <div className="table-like shipment-list">
              {items.map((shipment) => (
                <div className="table-row shipment-row" key={shipment.id}>
                  <div>
                    <strong>Order #{shipment.orderId}</strong>
                    <div className="subtle-note">
                      {shipment.trackingNumber} · Seller #{shipment.sellerId}
                    </div>
                  </div>
                  <span className="status-badge">{shipment.status}</span>
                  <span>{new Date(shipment.updatedAt).toLocaleString()}</span>
                  {canUpdate ? (
                    <select
                      className="compact-select"
                      value=""
                      onChange={(e) =>
                        changeStatus(shipment.id, e.target.value)
                      }
                    >
                      <option value="">Change status…</option>
                      {(NEXT[shipment.status] || []).map((next) => (
                        <option key={next} value={next}>
                          {next}
                        </option>
                      ))}
                    </select>
                  ) : (
                    <span className="subtle-note">Fulfillment status</span>
                  )}
                </div>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
