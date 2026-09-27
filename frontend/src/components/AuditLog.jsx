import React, { useEffect, useState } from "react";
import { getAuditLog } from "../api";
export default function AuditLog() {
  const [data, setData] = useState(null),
    [error, setError] = useState("");
  const load = () =>
    getAuditLog({ page: 0, size: 50, sort: "occurredAt,desc" })
      .then(setData)
      .catch(() => setError("Failed to load audit events."));
  useEffect(load, []);
  return (
    <div className="page-wrapper">
      <div className="topbar">
        <div>
          <h1 className="page-title">Audit log</h1>
          <p className="page-subtitle">
            Immutable business-event visibility for administrators
          </p>
        </div>
      </div>
      <div className="page-content">
        {error && <div className="toast toast-error">{error}</div>}
        <section className="panel">
          {!data ? (
            <div className="empty-state">Loading audit events…</div>
          ) : data.content.length === 0 ? (
            <div className="empty-state">No audit events yet.</div>
          ) : (
            <div className="table-like">
              {data.content.map((e) => (
                <div className="table-row" key={e.id}>
                  <div>
                    <strong>{e.action}</strong>
                    <div className="subtle-note">
                      {e.entityType} {e.entityId || ""}
                    </div>
                  </div>
                  <span>
                    {e.actorUserId ? `User #${e.actorUserId}` : "System"}
                  </span>
                  <span>{e.details || ""}</span>
                  <span>{new Date(e.occurredAt).toLocaleString()}</span>
                </div>
              ))}
            </div>
          )}
        </section>
      </div>
    </div>
  );
}
