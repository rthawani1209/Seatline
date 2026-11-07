"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { RoleGate } from "@/components/RoleGate";
import { api, explain } from "@/lib/api";
import { when } from "@/lib/format";
import { useApi } from "@/lib/hooks";
import type { SeatlineEvent, Stats } from "@/lib/types";
import s from "./organizer.module.css";

export default function OrganizerPage() {
  return (
    <RoleGate roles={["ORGANIZER", "ADMIN"]}>
      <Dashboard />
    </RoleGate>
  );
}

function Dashboard() {
  const { data: events, error, reload } = useApi<SeatlineEvent[]>("/organizer/events");
  const [stats, setStats] = useState<Record<string, Stats>>({});
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  useEffect(() => {
    if (!events) return;
    let live = true;
    Promise.all(events.map((e) => api<Stats>(`/events/${e.id}/stats`).then((st) => [e.id, st] as const).catch(() => null))).then(
      (list) => {
        if (!live) return;
        const map: Record<string, Stats> = {};
        for (const x of list) if (x) map[x[0]] = x[1];
        setStats(map);
      },
    );
    return () => {
      live = false;
    };
  }, [events]);

  async function publish(id: string) {
    setBusy(id);
    setActionError(null);
    try {
      await api(`/events/${id}/publish`, { method: "POST" });
      reload();
    } catch (e) {
      setActionError(explain(e));
    } finally {
      setBusy(null);
    }
  }

  return (
    <div className={`wrap ${s.page}`}>
      <div className={s.head}>
        <div>
          <p className="eyebrow" style={{ margin: 0 }}>
            Organizer
          </p>
          <h1 className={`display ${s.title}`}>Your events</h1>
        </div>
        <Link href="/organizer/new" className="btn btn-primary">
          New event
        </Link>
      </div>

      {(error != null || actionError) && (
        <div className="alert" role="alert" style={{ marginBottom: 16 }}>
          {actionError ?? explain(error)}
        </div>
      )}

      {events && events.length === 0 && (
        <div className={`panel ${s.empty}`}>
          <p className="display" style={{ fontSize: 32, margin: 0 }}>
            No events yet
          </p>
          <p className="hint" style={{ margin: 0 }}>
            Set up a venue and your ticket types, check it over as a draft, then publish to go on sale.
          </p>
          <Link href="/organizer/new" className="btn btn-primary">
            Create your first event
          </Link>
        </div>
      )}

      {events && events.length > 0 && (
        <ul className={s.list}>
          {events.map((e) => {
            const st = stats[e.id];
            const pct = st && st.seats ? Math.round((st.sold / st.seats) * 100) : 0;
            const draft = e.status === "DRAFT";
            return (
              <li key={e.id} className={s.row} data-testid="organizer-event">
                <div>
                  <span className={`${s.status} ${draft ? "" : s.statusLive}`}>{draft ? "DRAFT" : "ON SALE"}</span>
                  <span className={s.meta}>{when(e.startsAt)}</span>
                  <h2 className={`display ${s.name}`}>{e.name}</h2>
                </div>
                <div className={s.meter}>
                  {draft ? (
                    <span className="hint">Not on sale yet</span>
                  ) : (
                    <>
                      <span className="mono">
                        {st ? `${st.sold.toLocaleString()} / ${st.seats.toLocaleString()} sold` : "…"}
                      </span>
                      <span className={s.track} aria-hidden="true">
                        <span style={{ width: `${pct}%` }} />
                      </span>
                    </>
                  )}
                </div>
                <div className={s.actions}>
                  {draft && (
                    <button type="button" className="btn btn-sm btn-primary" onClick={() => publish(e.id)} disabled={busy === e.id}>
                      {busy === e.id ? "Publishing…" : "Publish"}
                    </button>
                  )}
                  <Link href={`/organizer/events/${e.id}`} className="btn btn-sm">
                    {draft ? "Manage" : "Live sales"}
                  </Link>
                  <Link href={`/events/${e.id}`} className="btn btn-sm">
                    Fan page
                  </Link>
                </div>
              </li>
            );
          })}
        </ul>
      )}
    </div>
  );
}
