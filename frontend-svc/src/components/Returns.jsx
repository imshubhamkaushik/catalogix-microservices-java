import React, { useEffect, useState, useCallback } from "react";
import PropTypes from "prop-types";
import {
  getMyReturns,
  getAllReturns,
  approveReturn,
  rejectReturn,
} from "../api";
import { useAuth } from "../context/AuthContext";

function formatPrice(value) {
  return new Intl.NumberFormat("en-IN", {
    style: "currency",
    currency: "INR",
    minimumFractionDigits: 0,
    maximumFractionDigits: 2,
  }).format(value);
}

function formatDate(iso) {
  return new Date(iso).toLocaleDateString(undefined, {
    year: "numeric",
    month: "short",
    day: "numeric",
  });
}

function ReturnStatusBadge({ status }) {
  let cls = "badge-low-stock";

  if (status === "REFUNDED") {
    cls = "badge-in-stock";
  } else if (status === "REJECTED") {
    cls = "badge-out-of-stock";
  }

  return <span className={`badge ${cls}`}>{status}</span>;
}
ReturnStatusBadge.propTypes = { status: PropTypes.string.isRequired };

function ReturnCard({ children, id, status }) {
  return (
    <div
      className="item-row"
      style={{ alignItems: "flex-start", flexWrap: "wrap" }}
      data-testid={`return-${id}`}
    >
      <div className="item-meta">
        <div className="item-name">
          Return #{id} <ReturnStatusBadge status={status} />
        </div>
        {children}
      </div>
    </div>
  );
}
ReturnCard.propTypes = {
  children: PropTypes.node,
  id: PropTypes.number.isRequired,
  status: PropTypes.string.isRequired,
};

const ReturnsIcon = () => (
  <svg viewBox="0 0 16 16" width="15" height="15" fill="currentColor">
    <path d="M8 1a.5.5 0 01.5.5v1.55A5.5 5.5 0 1113.95 8.5.5.5 0 1113 8.4 4.5 4.5 0 108.5 3.55V5a.5.5 0 01-1 0V1.5A.5.5 0 018 1zM3.5 9.5a.5.5 0 01.5.5v1a1 1 0 001 1h6a1 1 0 001-1v-1a.5.5 0 011 0v1a2 2 0 01-2 2H5a2 2 0 01-2-2v-1a.5.5 0 01.5-.5z" />
  </svg>
);

function ItemsLine({ items }) {
  return (
    <div className="item-sub">
      {items.map((i) => `${i.quantity} × ${i.productName}`).join(", ")}
    </div>
  );
}
ItemsLine.propTypes = { items: PropTypes.array.isRequired };

export default function Returns() {
  const { isAdmin } = useAuth();

  const [myReturns, setMyReturns] = useState([]);
  const [myLoading, setMyLoading] = useState(false);

  const [queue, setQueue] = useState([]);
  const [queueLoading, setQueueLoading] = useState(false);
  const [statusFilter, setStatusFilter] = useState("REQUESTED");
  const [busyId, setBusyId] = useState(null);

  const [error, setError] = useState("");
  const [toast, setToast] = useState(null);

  const fetchMine = useCallback(async () => {
    setMyLoading(true);
    try {
      setMyReturns(await getMyReturns());
    } catch {
      setError("Failed to load your returns.");
    } finally {
      setMyLoading(false);
    }
  }, []);

  const fetchQueue = useCallback(async () => {
    if (!isAdmin) return;
    setQueueLoading(true);
    try {
      const data = await getAllReturns({
        status: statusFilter || undefined,
        size: 50,
      });
      setQueue(data.content || []);
    } catch {
      setError("Failed to load the return queue.");
    } finally {
      setQueueLoading(false);
    }
  }, [isAdmin, statusFilter]);

  useEffect(() => {
    fetchMine();
  }, [fetchMine]);
  useEffect(() => {
    fetchQueue();
  }, [fetchQueue]);

  const handleApprove = async (id) => {
    setBusyId(id);
    try {
      await approveReturn(id);
      setToast({
        message: `Return #${id} approved — refund issued.`,
        type: "success",
      });
      await fetchQueue();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to approve return.");
    } finally {
      setBusyId(null);
    }
  };

  const handleReject = async (id) => {
    const reason = globalThis.prompt("Reason for rejecting this return?");
    if (!reason) return;
    setBusyId(id);
    try {
      await rejectReturn(id, reason);
      setToast({ message: `Return #${id} rejected.`, type: "success" });
      await fetchQueue();
    } catch (err) {
      setError(err.response?.data?.message || "Failed to reject return.");
    } finally {
      setBusyId(null);
    }
  };

  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Returns</h1>
          <p className="page-subtitle">
            {isAdmin
              ? "Review and manage return requests"
              : "Track your return requests"}
          </p>
        </div>
      </div>

      <div className="page-content">
        {toast && <div className="toast toast-success">{toast.message}</div>}
        {error && <div className="toast toast-error">{error}</div>}

        {isAdmin && (
          <>
            <div className="section-header">
              <div className="section-header-left">
                <span className="section-title">Review queue</span>
                {!queueLoading && (
                  <span className="section-count">{queue.length} requests</span>
                )}
              </div>
              <select
                className="sort-select"
                value={statusFilter}
                onChange={(e) => setStatusFilter(e.target.value)}
              >
                <option value="REQUESTED">Pending review</option>
                <option value="REFUNDED">Refunded</option>
                <option value="REJECTED">Rejected</option>
                <option value="">All</option>
              </select>
            </div>

            {queueLoading && <p className="auth-help-text">Loading…</p>}
            {!queueLoading && queue.length === 0 && (
              <div className="empty-state">
                <div className="empty-icon">
                  <ReturnsIcon />
                </div>
                <p className="empty-title">Nothing here</p>
                <p className="empty-sub">
                  No return requests match this filter.
                </p>
              </div>
            )}
            {!queueLoading && queue.length > 0 && (
              <div className="item-list" style={{ marginBottom: 24 }}>
                {queue.map((r) => (
                  <ReturnCard key={r.id} id={r.id} status={r.status}>
                    <div className="item-sub">
                      Order #{r.orderId} · user #{r.userId} · {r.reason}
                    </div>
                    <ItemsLine items={r.items} />
                    <div className="tracking-step-date">
                      Requested {formatDate(r.createdAt)} · Refund amount:{" "}
                      {formatPrice(r.refundAmount)}
                    </div>
                    {r.decisionNote && (
                      <div className="tracking-step-date">{r.decisionNote}</div>
                    )}
                    {r.status === "REQUESTED" && (
                      <div className="order-detail-actions">
                        <button
                          className="btn-small btn-primary-small"
                          type="button"
                          disabled={busyId === r.id}
                          onClick={() => handleApprove(r.id)}
                        >
                          {busyId === r.id ? "…" : "Approve"}
                        </button>
                        <button
                          className="btn-small"
                          type="button"
                          disabled={busyId === r.id}
                          onClick={() => handleReject(r.id)}
                        >
                          Reject
                        </button>
                      </div>
                    )}
                  </ReturnCard>
                ))}
              </div>
            )}
          </>
        )}

        <div className="section-header">
          <div className="section-header-left">
            <span className="section-title">My return requests</span>
            {!myLoading && (
              <span className="section-count">{myReturns.length}</span>
            )}
          </div>
        </div>

        {myLoading && <p className="auth-help-text">Loading…</p>}
        {!myLoading && myReturns.length === 0 && (
          <div className="empty-state">
            <div className="empty-icon">
              <ReturnsIcon />
            </div>
            <p className="empty-title">No return requests yet</p>
            <p className="empty-sub">
              Request a return from any delivered order on the Orders page.
            </p>
          </div>
        )}
        {!myLoading && myReturns.length > 0 && (
          <div className="item-list">
            {myReturns.map((r) => (
              <ReturnCard key={r.id} id={r.id} status={r.status}>
                <div className="item-sub">
                  Order #{r.orderId} · {r.reason}
                </div>
                <ItemsLine items={r.items} />
                <div className="tracking-step-date">
                  Requested {formatDate(r.createdAt)} · Refund amount:{" "}
                  {formatPrice(r.refundAmount)}
                </div>
                {r.decisionNote && (
                  <div className="tracking-step-date">{r.decisionNote}</div>
                )}
              </ReturnCard>
            ))}
          </div>
        )}
      </div>
    </div>
  );
}
