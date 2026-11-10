"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { SeatMap } from "@/components/SeatMap";
import { api, ApiError, explain } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { clock, money, seatName, tierWord, when } from "@/lib/format";
import { useApi, useCountdown, usePoll } from "@/lib/hooks";
import { saveHold } from "@/lib/holds";
import type { Hold, QueueStatus, Seat, SeatState, SeatStatus, SeatlineEvent, Tier, Venue } from "@/lib/types";
import s from "./event.module.css";

type Picked = { label: string; section: string; tierId: string };

export default function EventPage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { me, ready } = useAuth();

  const { data: event, error: eventError } = useApi<SeatlineEvent>(`/events/${id}`);
  const { data: venue } = useApi<Venue>(event ? `/venues/${event.venueId}` : null);
  const { data: states, error: seatsError, refresh } = usePoll<SeatState[]>(event ? `/events/${id}/seats` : null, 2000);

  const [tierId, setTierId] = useState<string | null>(null);
  const [picked, setPicked] = useState<Picked[]>([]);
  const [accessibleOnly, setAccessibleOnly] = useState(false);
  const [hideLimited, setHideLimited] = useState(false);
  const [count, setCount] = useState(2);
  const [promo, setPromo] = useState("");
  const [busy, setBusy] = useState<null | "best" | "hold">(null);
  const [error, setError] = useState<string | null>(null);
  const [announce, setAnnounce] = useState("");

  const tier = event?.tiers.find((t) => t.id === tierId) ?? null;

  // default to the cheapest ticket type that isn't restricted
  useEffect(() => {
    if (!event || tierId) return;
    const open = event.tiers.filter((t) => t.kind !== "STUDENT");
    const pick = [...(open.length ? open : event.tiers)].sort((a, b) => a.allInCents - b.allInCents)[0];
    if (pick) setTierId(pick.id);
  }, [event, tierId]);

  const status = useMemo(() => new Map<string, SeatStatus>((states ?? []).map((x) => [x.seat, x.status])), [states]);
  const sellable = useMemo(() => new Set(tier?.sections ?? []), [tier]);
  const selectedSet = useMemo(() => new Set(picked.map((p) => p.label)), [picked]);

  // someone else got a seat we'd picked: drop it and say so
  useEffect(() => {
    if (!states) return;
    const gone = picked.filter((p) => status.get(p.label) !== "FREE");
    if (gone.length) {
      setPicked((cur) => cur.filter((p) => status.get(p.label) === "FREE"));
      setAnnounce(`${gone.map((g) => seatName(g.label)).join(", ")} just went to someone else.`);
    }
  }, [states]); // eslint-disable-line react-hooks/exhaustive-deps

  const limit = event?.perFanLimit ?? 8;
  const free = useMemo(() => [...status.values()].filter((v) => v === "FREE").length, [status]);

  const toggle = useCallback(
    (seat: Seat & { section: string }) => {
      if (!tier) return;
      setError(null);
      setPicked((cur) => {
        if (cur.some((p) => p.label === seat.label)) {
          setAnnounce(`Removed ${seatName(seat.label)}.`);
          return cur.filter((p) => p.label !== seat.label);
        }
        if (cur.length >= limit) {
          setAnnounce(`You can pick up to ${limit} seats.`);
          setError(`You can pick up to ${limit} seats for this event.`);
          return cur;
        }
        setAnnounce(`Picked ${seatName(seat.label)}.`);
        return [...cur, { label: seat.label, section: seat.section, tierId: tier.id }];
      });
    },
    [tier, limit],
  );

  // switching ticket type keeps only seats the new type is sold in
  const chooseTier = (t: Tier) => {
    setTierId(t.id);
    setPicked((cur) => cur.filter((p) => t.sections.includes(p.section)).map((p) => ({ ...p, tierId: t.id })));
  };

  const opensIn = useCountdown(event?.onSaleAt && Date.parse(event.onSaleAt) > Date.now() ? event.onSaleAt : null);
  const notYet = opensIn != null && opensIn > 0;

  // waiting room: only fans who came through it may hold seats
  const hasQueue = !!event?.queuePerMinute;
  const { data: queue, error: queueError } = usePoll<QueueStatus>(hasQueue && me ? `/events/${id}/queue` : null, 10000);
  const passLeft = useCountdown(queue?.admitted ? queue.passExpiresAt : null);
  const inLine = !!queue && !queue.admitted;
  const notJoined = queueError instanceof ApiError && queueError.code === "not_in_queue";
  const mayBuy = !hasQueue || (queue?.admitted && (passLeft ?? 1) > 0);

  async function best() {
    if (!tier) return;
    setBusy("best");
    setError(null);
    try {
      const r = await api<{ seats: string[] }>(
        `/events/${id}/best-seats?tierId=${tier.id}&count=${count}&accessible=${accessibleOnly}`,
      );
      const bySeat = new Map(venue?.sections.flatMap((sec) => sec.seats.map((x) => [x.label, sec.name] as const)) ?? []);
      setPicked(r.seats.map((label) => ({ label, section: bySeat.get(label) ?? "", tierId: tier.id })));
      setAnnounce(`Picked ${r.seats.length} seats together: ${r.seats.map(seatName).join(", ")}.`);
    } catch (e) {
      setError(explain(e));
    } finally {
      setBusy(null);
    }
  }

  async function hold() {
    if (!me) {
      router.push(`/login?next=${encodeURIComponent(`/events/${id}`)}`);
      return;
    }
    setBusy("hold");
    setError(null);
    try {
      const h = await api<Hold>(`/events/${id}/holds`, {
        body: { items: picked.map((p) => ({ seat: p.label, tierId: p.tierId })), promoCode: promo.trim() || undefined },
      });
      saveHold(id, h);
      router.push(`/checkout/${h.holdId}`);
    } catch (e) {
      setError(explain(e));
      if (e instanceof ApiError && e.code === "seats_taken") refresh();
      setBusy(null);
    }
  }

  async function joinLine() {
    if (!me) {
      router.push(`/login?next=${encodeURIComponent(`/events/${id}`)}`);
      return;
    }
    try {
      await api(`/events/${id}/queue`, { method: "POST" });
      router.push(`/events/${id}/queue`);
    } catch (e) {
      setError(explain(e));
    }
  }

  if (eventError) {
    return (
      <div className="wrap" style={{ padding: "60px 20px" }}>
        <div className="alert" role="alert">
          {/* a link that isn't an event id at all (400) is just a missing event to a fan */}
          {eventError instanceof ApiError && (eventError.status === 404 || eventError.status === 400)
            ? "We couldn't find that event. It may have been taken down, or the link is wrong."
            : explain(eventError)}
        </div>
        <p>
          <Link href="/">Back to events</Link>
        </p>
      </div>
    );
  }

  if (!event) {
    return (
      <div className="wrap" style={{ padding: "60px 20px" }} aria-busy="true">
        <p className="hint">Loading event…</p>
      </div>
    );
  }

  const total = picked.reduce((sum, p) => sum + (event.tiers.find((t) => t.id === p.tierId)?.allInCents ?? 0), 0);
  const draft = event.status === "DRAFT";

  return (
    <div className={s.page}>
      <div className="sr-only" aria-live="polite">
        {announce}
      </div>

      <header className={`wrap ${s.head}`}>
        <div>
          <p className="eyebrow" style={{ color: "var(--accent)", margin: 0 }}>
            {venue ? `${venue.name} · ${venue.city}` : " "}
          </p>
          <h1 className={`display ${s.title}`}>{event.name}</h1>
          <p className={s.meta}>{when(event.startsAt, { year: "numeric" })}</p>
        </div>
        <div className={s.stats}>
          <Stat label="Seats left" value={states ? free.toLocaleString() : "…"} />
          <Stat label="Per fan" value={String(limit)} />
          {queue?.admitted && passLeft != null && <Stat label="Pass expires" value={clock(passLeft)} warn={passLeft < 120} />}
          {notYet && <Stat label="On sale in" value={clock(opensIn!)} />}
        </div>
      </header>

      {event.status === "CANCELLED" ? (
        <div className="wrap" style={{ paddingBottom: 60 }}>
          <div className="alert" role="status">
            This event has been cancelled. Everyone who bought tickets has been refunded in full, fees included.
          </div>
        </div>
      ) : (
      <div className={`wrap ${s.layout}`}>
        <section className={s.mapCol} aria-label="Seats">
          {draft && <div className="notice">This event is a draft. Publish it to put seats on sale.</div>}
          {seatsError != null && (
            <div className="alert" role="alert">
              Live seat status paused: {explain(seatsError)} Retrying…
            </div>
          )}
          {venue ? (
            <SeatMap
              venue={venue}
              status={status}
              sellable={sellable}
              selected={selectedSet}
              accessibleOnly={accessibleOnly}
              hideLimited={hideLimited}
              onToggle={toggle}
            />
          ) : (
            <div className={s.mapSkeleton} aria-busy="true" />
          )}
          <ul className={s.legend} aria-label="Legend">
            <li>
              <i className={s.lFree} /> Available
            </li>
            <li>
              <i className={s.lAccess} /> Wheelchair space
            </li>
            <li>
              <i className={s.lPicked} /> Your pick
            </li>
            <li>
              <i className={s.lHeld} /> Held by someone
            </li>
            <li>
              <i className={s.lSold} /> Sold
            </li>
            <li>
              <i className={s.lLimited} /> Limited view
            </li>
            <li>
              <i className={s.lOff} /> Other ticket type
            </li>
          </ul>
        </section>

        <aside className={s.side} aria-label="Your order">
          <fieldset className={s.block}>
            <legend className="eyebrow">Ticket type</legend>
            <div className={s.tiers}>
              {event.tiers.map((t) => (
                <label key={t.id} className={s.tier} data-on={t.id === tierId}>
                  <input type="radio" name="tier" checked={t.id === tierId} onChange={() => chooseTier(t)} className="sr-only" />
                  <span className={s.tierName}>
                    {t.name}
                    <small>
                      {[tierWord[t.kind] !== t.name ? tierWord[t.kind] : null, t.sections.join(", ")].filter(Boolean).join(" · ")}
                    </small>
                  </span>
                  <span className="mono">{money(t.allInCents)}</span>
                </label>
              ))}
            </div>
            <p className="hint" style={{ margin: 0 }}>
              Prices include fees and tax.
            </p>
          </fieldset>

          <div className={s.block}>
            <div className={s.chips}>
              <button type="button" className="chip" aria-pressed={accessibleOnly} onClick={() => setAccessibleOnly((v) => !v)}>
                Accessible only
              </button>
              <button type="button" className="chip" aria-pressed={hideLimited} onClick={() => setHideLimited((v) => !v)}>
                Hide limited view
              </button>
            </div>
            <div className={s.best}>
              <div className={s.stepper} role="group" aria-label="How many seats">
                <button type="button" onClick={() => setCount((c) => Math.max(1, c - 1))} aria-label="Fewer seats" disabled={count <= 1}>
                  −
                </button>
                <span className="mono" aria-live="polite">
                  {count}
                </span>
                <button type="button" onClick={() => setCount((c) => Math.min(limit, c + 1))} aria-label="More seats" disabled={count >= limit}>
                  +
                </button>
              </div>
              <button type="button" className="btn btn-solid" onClick={best} disabled={!tier || busy !== null || draft || !states?.length}>
                {busy === "best" ? "Finding…" : "Best available"}
              </button>
            </div>
          </div>

          <div className={`${s.block} ${s.cart}`}>
            <div className={s.cartHead}>
              <span className="eyebrow">Your seats</span>
              {picked.length > 0 && (
                <button type="button" className={s.clear} onClick={() => setPicked([])}>
                  Clear
                </button>
              )}
            </div>
            {picked.length === 0 ? (
              <p className="hint" style={{ margin: 0 }}>
                Pick seats on the map, or let us find the best ones together.
              </p>
            ) : (
              <ul className={s.picks}>
                {picked.map((p) => {
                  const t = event.tiers.find((x) => x.id === p.tierId);
                  return (
                    <li key={p.label}>
                      <span>{seatName(p.label)}</span>
                      <span className="mono">{money(t?.allInCents)}</span>
                      <button
                        type="button"
                        className={s.remove}
                        aria-label={`Remove ${seatName(p.label)}`}
                        onClick={() => setPicked((cur) => cur.filter((x) => x.label !== p.label))}
                      >
                        ×
                      </button>
                    </li>
                  );
                })}
              </ul>
            )}

            <label className="field">
              Promo code
              <input
                className="input mono"
                value={promo}
                maxLength={20}
                onChange={(e) => setPromo(e.target.value.toUpperCase().replace(/[^A-Z0-9]/g, ""))}
                placeholder="Optional"
              />
            </label>

            <div className={s.total}>
              <span>
                {picked.length} {picked.length === 1 ? "seat" : "seats"}
              </span>
              <span className="mono">{money(total)}</span>
            </div>

            {error && (
              <div className="alert" role="alert">
                {error}
              </div>
            )}

            {ready && hasQueue && me && (inLine || notJoined) && !queue?.admitted ? (
              inLine ? (
                <Link href={`/events/${id}/queue`} className="btn btn-primary btn-block">
                  Back to your place in line
                </Link>
              ) : (
                <button type="button" className="btn btn-primary btn-block" onClick={joinLine}>
                  Join the waiting room
                </button>
              )
            ) : (
              <button
                type="button"
                className="btn btn-primary btn-block"
                onClick={hold}
                disabled={!!me && (busy !== null || picked.length === 0 || draft || notYet || !mayBuy)}
              >
                {!me ? "Sign in to hold seats" : busy === "hold" ? "Holding…" : picked.length ? `Hold ${picked.length} for 10 minutes` : "Hold seats"}
              </button>
            )}
            <p className="hint" style={{ margin: 0 }}>
              Seats are yours for 10 minutes while you pay. Nobody else can take them in that time.
            </p>
          </div>
        </aside>
      </div>
      )}
    </div>
  );
}

function Stat({ label, value, warn }: { label: string; value: string; warn?: boolean }) {
  return (
    <div className={s.stat}>
      <span className="eyebrow">{label}</span>
      <span className={`mono ${s.statValue}`} style={warn ? { color: "var(--accent)" } : undefined}>
        {value}
      </span>
    </div>
  );
}
