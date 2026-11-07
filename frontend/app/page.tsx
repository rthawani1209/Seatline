"use client";

import Link from "next/link";
import { useEffect, useState } from "react";
import { api, explain } from "@/lib/api";
import { money, when } from "@/lib/format";
import { useApi } from "@/lib/hooks";
import type { SeatlineEvent, Venue } from "@/lib/types";
import s from "./page.module.css";

export default function Home() {
  const { data: events, error, loading, reload } = useApi<SeatlineEvent[]>("/events");
  const venues = useVenues(events);

  return (
    <div className={s.page}>
      <section className={`wrap ${s.hero}`}>
        <p className="eyebrow">
          <span className="live-dot" aria-hidden="true" /> Live ticketing
        </p>
        <h1 className={`display ${s.title}`}>
          Every seat
          <br />
          sold <span className={s.accent}>once.</span>
        </h1>
        <p className={s.lede}>
          A fair waiting room when the rush hits, seats held for you while you pay, and a ledger that five servers have
          to agree on before a sale counts. No bots jumping the line, no seat sold twice.
        </p>
      </section>

      <section className="wrap" aria-labelledby="on-sale">
        <div className={s.listHead}>
          <h2 id="on-sale" className={`display ${s.h2}`}>
            On sale
          </h2>
          {events && <span className="mono hint">{events.length} events</span>}
        </div>

        {loading && !events && (
          <div className={s.grid} aria-busy="true" aria-label="Loading events">
            {[0, 1, 2].map((i) => (
              <div key={i} className={`${s.card} ${s.skeleton}`} />
            ))}
          </div>
        )}

        {error != null && (
          <div className="alert" role="alert">
            {explain(error)}{" "}
            <button type="button" className="btn btn-sm" onClick={reload}>
              Try again
            </button>
          </div>
        )}

        {events && events.length === 0 && (
          <div className={`panel ${s.empty}`}>
            <p className="display" style={{ fontSize: 32, margin: 0 }}>
              Nothing on sale right now
            </p>
            <p className="hint">New shows go on sale here as soon as organizers publish them.</p>
          </div>
        )}

        {events && events.length > 0 && (
          <ul className={s.grid}>
            {events.map((e) => (
              <li key={e.id}>
                <EventCard e={e} venue={venues[e.venueId]} />
              </li>
            ))}
          </ul>
        )}
      </section>
    </div>
  );
}

function EventCard({ e, venue }: { e: SeatlineEvent; venue?: Venue }) {
  const opens = e.onSaleAt && Date.parse(e.onSaleAt) > Date.now() ? e.onSaleAt : null;
  const d = new Date(e.startsAt);
  return (
    <Link href={`/events/${e.id}`} className={s.card} data-testid="event-card">
      <div className={s.stub}>
        <span className="mono">{d.toLocaleString("en-US", { month: "short" }).toUpperCase()}</span>
        <span className={`display ${s.day}`}>{d.getDate()}</span>
        <span className="mono">{d.toLocaleString("en-US", { weekday: "short" }).toUpperCase()}</span>
      </div>
      <div className={s.body}>
        <h3 className={`display ${s.name}`}>{e.name}</h3>
        <p className={s.meta}>
          {venue ? `${venue.name} · ${venue.city}` : " "}
          <br />
          {when(e.startsAt)}
        </p>
        <div className={s.foot}>
          <span className="mono">{e.fromCents != null ? `from ${money(e.fromCents)}` : ""}</span>
          {opens ? (
            <span className={s.badge}>Opens {when(opens, { weekday: undefined })}</span>
          ) : (
            <span className={`${s.badge} ${s.badgeLive}`}>
              {e.queuePerMinute ? "Waiting room" : "On sale"}
            </span>
          )}
        </div>
      </div>
    </Link>
  );
}

function useVenues(events: SeatlineEvent[] | null) {
  const [venues, setVenues] = useState<Record<string, Venue>>({});
  useEffect(() => {
    if (!events) return;
    const ids = [...new Set(events.map((e) => e.venueId))];
    let live = true;
    Promise.all(ids.map((id) => api<Venue>(`/venues/${id}`).catch(() => null))).then((list) => {
      if (!live) return;
      const map: Record<string, Venue> = {};
      for (const v of list) if (v) map[v.id] = v;
      setVenues(map);
    });
    return () => {
      live = false;
    };
  }, [events]);
  return venues;
}
