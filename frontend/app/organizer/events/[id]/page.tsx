"use client";

import Link from "next/link";
import { useParams } from "next/navigation";
import { useEffect, useRef, useState } from "react";
import { RoleGate } from "@/components/RoleGate";
import { api, ApiError, explain } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { money, when } from "@/lib/format";
import { useApi, usePoll } from "@/lib/hooks";
import type { LedgerNode, SeatlineEvent, Stats } from "@/lib/types";
import s from "./live.module.css";

export default function LivePage() {
  return (
    <RoleGate roles={["ORGANIZER", "ADMIN"]}>
      <Live />
    </RoleGate>
  );
}

const WINDOW = 60; // seconds of history in the chart

function Live() {
  const { id } = useParams<{ id: string }>();
  const { me } = useAuth();
  const { data: event, error: eventError, reload } = useApi<SeatlineEvent>(`/events/${id}`);
  const { data: stats, error: statsError } = usePoll<Stats>(`/events/${id}/stats`, 2000);
  const [rates, setRates] = useState<number[]>(() => Array(WINDOW / 2).fill(0));
  const last = useRef<{ sold: number; at: number } | null>(null);
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // seats sold per second, from the change between polls
  useEffect(() => {
    if (!stats) return;
    const now = Date.now();
    if (last.current) {
      const secs = Math.max(0.5, (now - last.current.at) / 1000);
      const rate = Math.max(0, stats.sold - last.current.sold) / secs;
      setRates((r) => [...r.slice(1), rate]);
    }
    last.current = { sold: stats.sold, at: now };
  }, [stats]);

  async function publish() {
    setBusy(true);
    setError(null);
    try {
      await api(`/events/${id}/publish`, { method: "POST" });
      reload();
    } catch (e) {
      setError(explain(e));
    } finally {
      setBusy(false);
    }
  }

  if (eventError) {
    return (
      <div className="wrap" style={{ padding: "60px 20px" }}>
        <div className="alert" role="alert">
          {explain(eventError)}
        </div>
      </div>
    );
  }
  if (!event) {
    return (
      <div className="wrap" style={{ padding: "60px 20px" }} aria-busy="true">
        <p className="hint">Loading…</p>
      </div>
    );
  }

  const draft = event.status === "DRAFT";
  const peak = Math.max(1, ...rates);
  const doubleSold = stats ? Math.max(0, stats.tickets - stats.sold) : 0;

  return (
    <div className={`wrap ${s.page}`}>
      <div className={s.head}>
        <div>
          <Link href="/organizer" className="hint">
            ← Your events
          </Link>
          <h1 className={`display ${s.title}`}>{event.name}</h1>
          <p className="hint" style={{ margin: 0 }}>
            {when(event.startsAt, { year: "numeric" })} · {draft ? "Draft" : "On sale"}
            {event.queuePerMinute ? ` · waiting room at ${event.queuePerMinute}/min` : ""}
          </p>
        </div>
        <div className={s.headActions}>
          {draft && (
            <button type="button" className="btn btn-primary" onClick={publish} disabled={busy}>
              {busy ? "Publishing…" : "Publish and go on sale"}
            </button>
          )}
          <Link href={`/events/${id}`} className="btn">
            Fan page
          </Link>
        </div>
      </div>

      {(error || statsError != null) && (
        <div className="alert" role="alert">
          {error ?? explain(statsError)}
        </div>
      )}

      <div className={s.tiles}>
        <Tile label="Seats sold" value={stats ? stats.sold.toLocaleString() : "…"} sub={stats ? `of ${stats.seats.toLocaleString()}` : ""} big />
        <Tile label="Held right now" value={stats ? stats.held.toLocaleString() : "…"} />
        <Tile label="In the waiting room" value={stats ? stats.waiting.toLocaleString() : "…"} />
        <Tile label="Revenue" value={stats ? money(stats.revenueCents) : "…"} />
        <Tile label="Orders" value={stats ? stats.orders.toLocaleString() : "…"} />
        <Tile label="Scanned in" value={stats ? stats.scanned.toLocaleString() : "…"} />
        <Tile label="Returned" value={stats ? stats.returned.toLocaleString() : "…"} />
        <div className={s.proof}>
          <span className="eyebrow">Double-sold seats</span>
          <span className={`display ${s.proofValue}`} data-testid="double-sold">
            {stats ? doubleSold : "…"}
          </span>
          <span className="hint">Tickets issued vs seats the ledger has sold, checked every 2 s.</span>
        </div>
      </div>

      <section className={`panel ${s.chart}`} aria-labelledby="rate-h">
        <div className={s.chartHead}>
          <h2 id="rate-h" className={s.h2}>
            Seats sold per second
          </h2>
          <span className="hint">last {WINDOW} s</span>
        </div>
        <div
          className={s.bars}
          role="img"
          aria-label={`Seats sold per second over the last minute. Latest ${rates[rates.length - 1].toFixed(1)}, peak ${peak.toFixed(1)}.`}
        >
          {rates.map((r, i) => (
            <span key={i} style={{ height: `${(r / peak) * 100}%` }} />
          ))}
        </div>
      </section>

      {!draft && <PromoCodes eventId={id} />}

      {me?.role === "ADMIN" && <ChaosPanel />}
    </div>
  );
}

function Tile({ label, value, sub, big }: { label: string; value: string; sub?: string; big?: boolean }) {
  return (
    <div className={`${s.tile} ${big ? s.tileBig : ""}`}>
      <span className="eyebrow">{label}</span>
      <span className={`mono ${s.tileValue}`}>
        {value}
        {sub && <small> {sub}</small>}
      </span>
    </div>
  );
}

function PromoCodes({ eventId }: { eventId: string }) {
  const [code, setCode] = useState("");
  const [pct, setPct] = useState(10);
  const [uses, setUses] = useState(100);
  const [added, setAdded] = useState<string[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function add(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api(`/events/${eventId}/promo-codes`, { body: { code, percentOff: pct, maxUses: uses } });
      setAdded((a) => [`${code}: ${pct}% off, ${uses} uses`, ...a]);
      setCode("");
    } catch (err) {
      setError(explain(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={`panel ${s.promo}`} aria-labelledby="promo-h">
      <h2 id="promo-h" className={s.h2}>
        Promo codes
      </h2>
      <form className={s.promoForm} onSubmit={add}>
        <label className="field">
          Code
          <input
            className="input mono"
            required
            pattern="[A-Za-z0-9]{3,20}"
            title="3 to 20 letters or numbers"
            value={code}
            onChange={(e) => setCode(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ""))}
          />
        </label>
        <label className="field">
          % off
          <input className="input mono" type="number" min={1} max={100} required value={pct} onChange={(e) => setPct(Number(e.target.value))} />
        </label>
        <label className="field">
          Uses
          <input className="input mono" type="number" min={1} required value={uses} onChange={(e) => setUses(Number(e.target.value))} />
        </label>
        <button type="submit" className="btn btn-solid" disabled={busy}>
          {busy ? "Adding…" : "Add code"}
        </button>
      </form>
      {error && (
        <div className="alert" role="alert">
          {error}
        </div>
      )}
      {added.length > 0 && (
        <ul className={s.added}>
          {added.map((a) => (
            <li key={a} className="mono">
              {a}
            </li>
          ))}
        </ul>
      )}
    </section>
  );
}

type LogLine = { t: string; text: string };

function ChaosPanel() {
  const { data: nodes, error } = usePoll<LedgerNode[]>("/admin/ledger", 1000);
  const [log, setLog] = useState<LogLine[]>([]);
  const prev = useRef<LedgerNode[] | null>(null);
  const [busy, setBusy] = useState<string | null>(null);
  const [actionError, setActionError] = useState<string | null>(null);

  const note = (text: string) =>
    setLog((l) => [{ t: new Date().toLocaleTimeString("en-US", { hour12: false }), text }, ...l].slice(0, 12));

  // turn status changes into plain sentences
  useEffect(() => {
    if (!nodes) return;
    const before = prev.current;
    prev.current = nodes;
    if (!before) return;
    const leaderOf = (list: LedgerNode[]) => list.find((n) => n.up && n.role === "LEADER");
    const was = leaderOf(before);
    const now = leaderOf(nodes);
    for (const n of nodes) {
      const old = before.find((b) => b.id === n.id);
      if (!old) continue;
      if (old.up && !n.up) note(`N${n.id} went down${old.role === "LEADER" ? ". It was the leader, so the others will call a vote" : ""}`);
      if (!old.up && n.up) note(`N${n.id} is back and catching up from its log on disk`);
    }
    if (now && now.id !== was?.id) {
      const voters = nodes.filter((n) => n.up && n.term === now.term && !now.cutOff.includes(n.id)).map((n) => `N${n.id}`);
      note(`N${now.id} won the vote and leads term ${now.term}${voters.length ? ` (${voters.join(", ")} on that term)` : ""}`);
    }
    if (was && !now) note("No leader: fewer than 3 of 5 can talk, so the ledger stops taking sales instead of guessing");
  }, [nodes]);

  async function act(path: string, label: string) {
    setBusy(path);
    setActionError(null);
    try {
      await api(`/admin/ledger/${path}`, { method: "POST" });
      note(label);
    } catch (e) {
      setActionError(explain(e));
    } finally {
      setBusy(null);
    }
  }

  if (error instanceof ApiError && error.code === "chaos_off") {
    return (
      <section className={`panel ${s.chaos}`}>
        <h2 className={s.h2}>Seat ledger</h2>
        <p className="hint" style={{ margin: 0 }}>
          The chaos controls are switched off on this deployment (set SEATLINE_LEDGER_ADMIN on the API to turn them on).
        </p>
      </section>
    );
  }

  const up = nodes?.filter((n) => n.up).length ?? 0;
  const total = nodes?.length ?? 0;
  const needed = Math.floor(total / 2) + 1;
  const maxCommit = Math.max(1, ...(nodes ?? []).map((n) => n.commitIndex));

  return (
    <section className={s.chaos} aria-labelledby="chaos-h">
      <div className={s.chartHead}>
        <h2 id="chaos-h" className={s.h2}>
          Seat ledger · {total} servers, {needed} must agree
        </h2>
        <div style={{ display: "flex", gap: 10, alignItems: "center", flexWrap: "wrap" }}>
          <span className="hint">
            {up} of {total} up. Kill up to {total - needed} and sales keep going.
          </span>
          <button type="button" className="btn btn-sm" onClick={() => act("heal", "You reconnected every server")} disabled={busy !== null}>
            Reconnect all
          </button>
        </div>
      </div>
      {actionError && (
        <div className="alert" role="alert">
          {actionError}
        </div>
      )}
      <div className={s.nodes}>
        {(nodes ?? []).map((n) => {
          const leader = n.up && n.role === "LEADER";
          return (
            <div key={n.id} className={s.node} data-state={!n.reachable ? "gone" : !n.up ? "down" : leader ? "leader" : "up"} data-testid={`node-${n.id}`}>
              <div className={s.nodeHead}>
                <span className="display" style={{ fontSize: 28 }}>
                  N{n.id}
                </span>
                <span className={`mono ${s.badge}`}>{n.reachable ? n.role : "UNREACHABLE"}</span>
              </div>
              <dl className={s.nodeStats}>
                <dt>Term</dt>
                <dd className="mono">{n.up ? n.term : "–"}</dd>
                <dt>Log</dt>
                <dd className="mono">{n.up ? n.lastIndex.toLocaleString() : "–"}</dd>
                <dt>Committed</dt>
                <dd className="mono">{n.up ? n.commitIndex.toLocaleString() : "–"}</dd>
              </dl>
              <div className={s.nodeBar} aria-hidden="true">
                <span style={{ width: `${n.up ? (n.commitIndex / maxCommit) * 100 : 0}%` }} />
              </div>
              <p className={s.nodeNote}>
                {!n.reachable
                  ? "Process not answering"
                  : !n.up
                    ? "Down. Will catch up on revive"
                    : n.cutOff.length
                      ? `Cut off from ${n.cutOff.map((c) => `N${c}`).join(", ")}`
                      : maxCommit - n.commitIndex > 0
                        ? `${(maxCommit - n.commitIndex).toLocaleString()} entries behind`
                        : "Up to date"}
              </p>
              <div className={s.nodeActions}>
                {n.up ? (
                  <button
                    type="button"
                    className="btn btn-sm btn-solid"
                    onClick={() => act(`${n.id}/crash`, `You killed N${n.id}`)}
                    disabled={busy !== null || !n.reachable}
                  >
                    Kill
                  </button>
                ) : (
                  <button
                    type="button"
                    className="btn btn-sm btn-primary"
                    onClick={() => act(`${n.id}/revive`, `You revived N${n.id}`)}
                    disabled={busy !== null || !n.reachable}
                  >
                    Revive
                  </button>
                )}
                <button
                  type="button"
                  className="btn btn-sm"
                  onClick={() => act(`${n.id}/cut-off`, `You cut N${n.id} off from the others`)}
                  disabled={busy !== null || !n.up}
                >
                  Cut off
                </button>
              </div>
            </div>
          );
        })}
      </div>
      <div className={`panel ${s.log}`}>
        <h3 className={s.h3}>What the ledger just did</h3>
        {log.length === 0 ? (
          <p className="hint" style={{ margin: 0 }}>
            Kill the leader and watch the others elect a new one.
          </p>
        ) : (
          <ol className={s.logList} aria-live="polite">
            {log.map((l, i) => (
              <li key={i}>
                <span className="mono">{l.t}</span>
                <span>{l.text}</span>
              </li>
            ))}
          </ol>
        )}
      </div>
    </section>
  );
}
