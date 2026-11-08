"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, ApiError, explain } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { clock, money, seatParts } from "@/lib/format";
import { useCountdown } from "@/lib/hooks";
import { forgetHold, loadHold, type SavedHold } from "@/lib/holds";
import type { Order } from "@/lib/types";
import s from "./checkout.module.css";

const HOLD_SECONDS = 10 * 60; // OrderService.HOLD_TTL

// Stand-in cards until real Stripe keys are configured (the API's FakePayments uses these tokens).
const CARDS = [
  { token: "tok_visa", label: "Visa ending 4242", note: "Approves" },
  { token: "tok_declined", label: "Visa ending 0002", note: "Declines" },
];

export default function CheckoutPage() {
  const { holdId } = useParams<{ holdId: string }>();
  const router = useRouter();
  const { me, ready } = useAuth();
  const [hold, setHold] = useState<SavedHold | null>(null);
  const [missing, setMissing] = useState(false);
  const [card, setCard] = useState(CARDS[0].token);
  const [busy, setBusy] = useState<null | "pay" | "release">(null);
  const [error, setError] = useState<string | null>(null);
  const [order, setOrder] = useState<Order | null>(null);
  const [ended, setEnded] = useState(false);

  useEffect(() => {
    if (!ready) return;
    if (!me) {
      router.replace(`/login?next=${encodeURIComponent(`/checkout/${holdId}`)}`);
      return;
    }
    // show the copy from this tab straight away, then ask the API where the hold really stands
    // (it may have been paid, let go or run out in another tab)
    const saved = loadHold(holdId);
    if (saved) setHold(saved);
    api<SavedHold>(`/holds/${holdId}`)
      .then((h) => {
        setHold(h);
        if (h.state !== "OPEN") forgetHold(holdId);
      })
      .catch((e) => {
        if (!saved || (e instanceof ApiError && e.status === 404)) setMissing(true);
      });
  }, [holdId, me, ready, router]);

  const left = useCountdown(hold?.expiresAt);
  const expired = !order && (left === 0 || hold?.state === "EXPIRED");

  async function pay() {
    setBusy("pay");
    setError(null);
    try {
      const o = await api<Order>(`/holds/${holdId}/checkout`, { body: { cardToken: card } });
      forgetHold(holdId);
      setOrder(o);
    } catch (e) {
      if (e instanceof ApiError && (e.code === "hold_expired" || e.code === "no_such_hold")) {
        setEnded(true);
        forgetHold(holdId);
      }
      setError(explain(e));
    } finally {
      setBusy(null);
    }
  }

  async function release() {
    setBusy("release");
    try {
      await api(`/holds/${holdId}`, { method: "DELETE" });
    } catch {
      // already gone; the seats are free either way
    }
    forgetHold(holdId);
    router.push(hold ? `/events/${hold.eventId}` : "/");
  }

  if (missing) {
    return (
      <div className={s.shell}>
        <div className={`panel ${s.card}`} style={{ padding: 24 }}>
          <h1 className="display" style={{ fontSize: 40, margin: "0 0 8px" }}>
            Hold not found
          </h1>
          <p>This hold has ended or belongs to another account.</p>
          <Link href="/" className="btn">
            Back to events
          </Link>
        </div>
      </div>
    );
  }

  if (!hold) {
    return (
      <div className={s.shell} aria-busy="true">
        <p className="hint">Loading your hold…</p>
      </div>
    );
  }

  if (hold.state === "PAID" && !order) {
    return (
      <div className={s.shell}>
        <div className={`panel ${s.card}`} style={{ padding: 24 }}>
          <p className="eyebrow">Order {hold.orderId?.slice(0, 8).toUpperCase()}</p>
          <h1 className="display" style={{ fontSize: 40, margin: "0 0 8px" }}>
            Already paid
          </h1>
          <p>These seats are yours. Your tickets are waiting in My tickets.</p>
          <Link href="/tickets" className="btn btn-primary">
            See my tickets
          </Link>
        </div>
      </div>
    );
  }

  if (hold.state === "RELEASED") {
    return (
      <div className={s.shell}>
        <div className={`panel ${s.card}`} style={{ padding: 24 }}>
          <h1 className="display" style={{ fontSize: 40, margin: "0 0 8px" }}>
            Seats let go
          </h1>
          <p>You let these seats go, so they&apos;re back on sale. Nothing was charged.</p>
          <Link href={`/events/${hold.eventId}`} className="btn btn-primary">
            Pick seats again
          </Link>
        </div>
      </div>
    );
  }

  if (order) {
    return (
      <div className={s.shell}>
        <div className={s.done} role="status">
          <p className="eyebrow" style={{ color: "var(--accent)" }}>
            Order {order.id.slice(0, 8).toUpperCase()}
          </p>
          <h1 className={`display ${s.doneTitle}`}>You&apos;re in.</h1>
          <p>
            {order.tickets.length} {order.tickets.length === 1 ? "ticket" : "tickets"} for {order.tickets[0]?.eventName}, paid{" "}
            {money(order.totalCents)}. We&apos;ve emailed your receipt.
          </p>
          <ul className={s.stubs}>
            {order.tickets.map((t) => {
              const p = seatParts(t.seat);
              return (
                <li key={t.id} className={s.stub}>
                  <span className="mono">{p.section}</span>
                  <span className="display">
                    {p.row}
                    {p.number}
                  </span>
                  <span className="mono">{t.tier}</span>
                </li>
              );
            })}
          </ul>
          <div className={s.doneActions}>
            <Link href="/tickets" className="btn btn-primary">
              See my tickets
            </Link>
            <Link href="/" className="btn">
              Back to events
            </Link>
          </div>
        </div>
      </div>
    );
  }

  const pct = left == null ? 100 : Math.max(0, Math.min(100, (left / HOLD_SECONDS) * 100));
  const q = hold.quote;
  const sum = (k: "face" | "discount" | "fee" | "tax") => q.lines.reduce((a, l) => a + l.price[k], 0);

  return (
    <div className={s.shell}>
      <div className={s.grid}>
        <section className={s.card} aria-labelledby="your-seats">
          <div className={s.timer} data-urgent={left != null && left < 60}>
            <div>
              <span className="eyebrow">{expired || ended ? "Hold ended" : "Seats held for you"}</span>
              <div className={`mono ${s.clock}`} role="timer" aria-live="off">
                {left == null ? "–:––" : clock(left)}
              </div>
            </div>
            <div className={s.bar} aria-hidden="true">
              <span style={{ width: `${pct}%` }} />
            </div>
          </div>

          <h1 id="your-seats" className={`display ${s.title}`}>
            Checkout
          </h1>

          <ul className={s.lines}>
            {q.lines.map((l) => {
              const p = seatParts(l.seat);
              return (
                <li key={l.seat}>
                  <span>
                    <strong>
                      {p.section} · Row {p.row} · Seat {p.number}
                    </strong>
                    <small>{l.tier}</small>
                  </span>
                  <span className="mono">{money(l.price.total)}</span>
                </li>
              );
            })}
          </ul>

          <dl className={s.breakdown}>
            <div>
              <dt>Tickets</dt>
              <dd className="mono">{money(sum("face"))}</dd>
            </div>
            {sum("discount") > 0 && (
              <div>
                <dt>Promo {q.promoCode}</dt>
                <dd className="mono">−{money(sum("discount"))}</dd>
              </div>
            )}
            <div>
              <dt>Fees</dt>
              <dd className="mono">{money(sum("fee"))}</dd>
            </div>
            <div>
              <dt>Tax</dt>
              <dd className="mono">{money(sum("tax"))}</dd>
            </div>
            <div className={s.totalRow}>
              <dt>Total</dt>
              <dd className="mono">{money(q.totalCents)}</dd>
            </div>
          </dl>
        </section>

        <section className={`panel ${s.pay}`} aria-labelledby="pay-with">
          <h2 id="pay-with" className="eyebrow" style={{ margin: 0 }}>
            Pay with
          </h2>
          <div className={s.testMode}>Test cards only: no real card is charged.</div>
          <div className={s.cards} role="radiogroup" aria-label="Card">
            {CARDS.map((c) => (
              <label key={c.token} className={s.cardOpt} data-on={card === c.token}>
                <input type="radio" name="card" className="sr-only" checked={card === c.token} onChange={() => setCard(c.token)} />
                <span className={s.chip} aria-hidden="true" />
                <span>
                  <strong>{c.label}</strong>
                  <small>{c.note}</small>
                </span>
              </label>
            ))}
          </div>

          {error && (
            <div className="alert" role="alert">
              {error}
            </div>
          )}

          {expired || ended ? (
            <Link href={`/events/${hold.eventId}`} className="btn btn-primary btn-block">
              Pick seats again
            </Link>
          ) : (
            <button type="button" className="btn btn-primary btn-block" onClick={pay} disabled={busy !== null}>
              {busy === "pay" ? "Paying…" : `Pay ${money(q.totalCents)}`}
            </button>
          )}
          {!expired && !ended && (
            <button type="button" className="btn btn-block" onClick={release} disabled={busy !== null}>
              {busy === "release" ? "Letting go…" : "Let these seats go"}
            </button>
          )}
          <p className="hint" style={{ margin: 0 }}>
            If your connection drops after paying, you won&apos;t be charged twice: we finish the order or refund it automatically.
          </p>
        </section>
      </div>
    </div>
  );
}
