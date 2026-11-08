"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { Dialog } from "@/components/Dialog";
import { TicketQr } from "@/components/TicketQr";
import { api, explain } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { money, seatParts, when } from "@/lib/format";
import { useApi } from "@/lib/hooks";
import type { SeatlineEvent, Ticket } from "@/lib/types";
import s from "./tickets.module.css";

export default function TicketsPage() {
  const router = useRouter();
  const { me, ready } = useAuth();
  const { data: tickets, error, loading, reload } = useApi<Ticket[]>(me ? "/me/tickets" : null);
  const events = useEvents(tickets);
  const [index, setIndex] = useState(0);
  const [dialog, setDialog] = useState<null | "send" | "return">(null);
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);
  const [actionError, setActionError] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);

  useEffect(() => {
    if (ready && !me) router.replace("/login?next=/tickets");
  }, [ready, me, router]);

  useEffect(() => {
    if (tickets && index >= tickets.length) setIndex(Math.max(0, tickets.length - 1));
  }, [tickets, index]);

  const t = tickets?.[index];
  const ev = t ? events[t.eventId] : undefined;

  async function act(kind: "send" | "return") {
    if (!t) return;
    setBusy(true);
    setActionError(null);
    try {
      if (kind === "send") {
        await api(`/tickets/${encodeURIComponent(t.id)}/transfer`, { body: { toEmail: email.trim() } });
        setFlash(`Sent ${seatLabel(t.seat)} to ${email.trim()}. Your code for it has stopped working.`);
      } else {
        await api(`/tickets/${encodeURIComponent(t.id)}/return`, { method: "POST" });
        setFlash(`Returned ${seatLabel(t.seat)}. ${money(t.priceCents)} is on its way back to your card.`);
      }
      setDialog(null);
      setEmail("");
      reload();
    } catch (e) {
      setActionError(explain(e));
    } finally {
      setBusy(false);
    }
  }

  if (!me || (loading && !tickets)) {
    return (
      <div className={s.shell} aria-busy="true">
        <p className="hint">Loading your tickets…</p>
      </div>
    );
  }

  return (
    <div className={s.shell}>
      <div className={s.head}>
        <h1 className="display" style={{ fontSize: 40, margin: 0 }}>
          My tickets
        </h1>
        {tickets && tickets.length > 0 && (
          <span className="mono hint">
            {index + 1} of {tickets.length}
          </span>
        )}
      </div>

      {flash && (
        <div className="notice" role="status">
          {flash}
        </div>
      )}
      {error != null && (
        <div className="alert" role="alert">
          {explain(error)}
        </div>
      )}

      {tickets && tickets.length === 0 && (
        <div className={`panel ${s.empty}`}>
          <p className="display" style={{ fontSize: 32, margin: 0 }}>
            No tickets yet
          </p>
          <p className="hint">Tickets you buy, or that friends send you, show up here.</p>
          <Link href="/" className="btn btn-primary">
            Find a show
          </Link>
        </div>
      )}

      {t && (
        <>
          <article className={s.ticket} aria-label={`Ticket for ${t.eventName}, ${seatLabel(t.seat)}`}>
            <div className={s.band}>
              <div className="mono" style={{ fontSize: 11, letterSpacing: "0.12em" }}>
                ADMIT ONE · {t.tier.toUpperCase()}
              </div>
              <div className={`display ${s.event}`}>{t.eventName}</div>
              <div className={s.when}>{ev ? when(ev.startsAt) : " "}</div>
            </div>
            <Seat seat={t.seat} />
            <div className={s.tear} aria-hidden="true">
              <span />
            </div>
            <TicketQr ticketId={t.id} />
            <div className={s.holder}>
              {me.displayName} · #{ticketNumber(t.id)}
            </div>
          </article>

          {tickets!.length > 1 && (
            <div className={s.pager}>
              <button type="button" className="btn btn-sm" onClick={() => setIndex((i) => Math.max(0, i - 1))} disabled={index === 0}>
                ← Previous
              </button>
              <div className={s.pips} aria-hidden="true">
                {tickets!.map((x, i) => (
                  <span key={x.id} data-on={i === index} />
                ))}
              </div>
              <button
                type="button"
                className="btn btn-sm"
                onClick={() => setIndex((i) => Math.min(tickets!.length - 1, i + 1))}
                disabled={index === tickets!.length - 1}
              >
                Next →
              </button>
            </div>
          )}

          <div className={s.actions}>
            <button
              type="button"
              className="btn"
              onClick={() => {
                setActionError(null);
                setDialog("send");
              }}
            >
              Send to a friend
            </button>
            <button
              type="button"
              className="btn"
              onClick={() => {
                setActionError(null);
                setDialog("return");
              }}
            >
              Return at face value
            </button>
          </div>
          <p className="hint" style={{ margin: 0 }}>
            Returned seats go straight back on sale, and you get the full {money(t.priceCents)} back. Sending a ticket
            cancels this code right away.
          </p>

          <Dialog open={dialog === "send"} title="Send ticket" onClose={() => setDialog(null)}>
            <form
              onSubmit={(e) => {
                e.preventDefault();
                act("send");
              }}
              style={{ display: "flex", flexDirection: "column", gap: 14 }}
            >
              <p style={{ margin: 0 }}>
                {seatLabel(t.seat)} goes to their Seatline account, with a new code. Yours stops working straight away.
              </p>
              <label className="field">
                Their email
                <input className="input" type="email" required autoFocus value={email} onChange={(e) => setEmail(e.target.value)} />
                <span className="hint">They need a Seatline account with this email.</span>
              </label>
              {actionError && (
                <div className="alert" role="alert">
                  {actionError}
                </div>
              )}
              <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
                {busy ? "Sending…" : "Send ticket"}
              </button>
            </form>
          </Dialog>

          <Dialog open={dialog === "return"} title="Return ticket" onClose={() => setDialog(null)}>
            <p style={{ margin: 0 }}>
              Give back {seatLabel(t.seat)} for {t.eventName}? You get {money(t.priceCents)} back and the seat goes on sale
              again. This can&apos;t be undone.
            </p>
            {actionError && (
              <div className="alert" role="alert">
                {actionError}
              </div>
            )}
            <div style={{ display: "flex", gap: 10 }}>
              <button type="button" className="btn" style={{ flex: 1 }} onClick={() => setDialog(null)}>
                Keep it
              </button>
              <button type="button" className="btn btn-primary" style={{ flex: 1 }} onClick={() => act("return")} disabled={busy}>
                {busy ? "Returning…" : "Return ticket"}
              </button>
            </div>
          </Dialog>
        </>
      )}
    </div>
  );
}

function Seat({ seat }: { seat: string }) {
  const p = seatParts(seat);
  return (
    <div className={s.seat}>
      <div>
        <span>SECTION</span>
        <strong className="display">{p.section}</strong>
      </div>
      <div style={{ alignItems: "center" }}>
        <span>ROW</span>
        <strong className="display">{p.row}</strong>
      </div>
      <div style={{ alignItems: "flex-end" }}>
        <span>SEAT</span>
        <strong className="display">{p.number}</strong>
      </div>
    </div>
  );
}

function seatLabel(seat: string) {
  const p = seatParts(seat);
  return `${p.section} row ${p.row} seat ${p.number}`;
}

function useEvents(tickets: Ticket[] | null) {
  const [events, setEvents] = useState<Record<string, SeatlineEvent>>({});
  const ids = useMemo(() => [...new Set((tickets ?? []).map((t) => t.eventId))].sort().join(","), [tickets]);
  useEffect(() => {
    if (!ids) return;
    let live = true;
    Promise.all(ids.split(",").map((id) => api<SeatlineEvent>(`/events/${id}`).catch(() => null))).then((list) => {
      if (!live) return;
      const map: Record<string, SeatlineEvent> = {};
      for (const e of list) if (e) map[e.id] = e;
      setEvents(map);
    });
    return () => {
      live = false;
    };
  }, [ids]);
  return events;
}

/** A short number for the stub. Ticket ids start with the hold id, which two tickets can share. */
function ticketNumber(id: string): string {
  let h = 2166136261; // FNV-1a
  for (let i = 0; i < id.length; i++) h = Math.imul(h ^ id.charCodeAt(i), 16777619);
  return (h >>> 0).toString(36).toUpperCase().padStart(7, "0");
}
