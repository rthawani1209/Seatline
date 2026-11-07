"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useMemo, useState } from "react";
import { RoleGate } from "@/components/RoleGate";
import { api, explain } from "@/lib/api";
import { tierWord } from "@/lib/format";
import type { SeatlineEvent, TierKind, Venue } from "@/lib/types";
import s from "./new.module.css";

type SectionDraft = { name: string; rows: number; seats: number; accessible: string; limited: string };
type TierDraft = { name: string; kind: TierKind; price: string; sections: string[] };

const LETTERS = "ABCDEFGHIJKLMNOPQRSTUVWXYZ";

export default function NewEventPage() {
  return (
    <RoleGate roles={["ORGANIZER", "ADMIN"]}>
      <NewEvent />
    </RoleGate>
  );
}

/** "1, 2, 7-9" to [1, 2, 7, 8, 9], ignoring anything outside 1..max. */
function numbers(text: string, max: number): number[] {
  const out = new Set<number>();
  for (const part of text.split(",")) {
    const [a, b] = part.split("-").map((x) => parseInt(x.trim(), 10));
    if (Number.isNaN(a)) continue;
    for (let n = a; n <= (Number.isNaN(b) ? a : b); n++) if (n >= 1 && n <= max) out.add(n);
  }
  return [...out].sort((x, y) => x - y);
}

function localInput(d: Date) {
  const pad = (n: number) => String(n).padStart(2, "0");
  return `${d.getFullYear()}-${pad(d.getMonth() + 1)}-${pad(d.getDate())}T${pad(d.getHours())}:${pad(d.getMinutes())}`;
}

function NewEvent() {
  const router = useRouter();
  const [venueMode, setVenueMode] = useState<"new" | string>("new");
  const [venues, setVenues] = useState<Venue[]>([]);
  const [venueName, setVenueName] = useState("");
  const [city, setCity] = useState("");
  const [sections, setSections] = useState<SectionDraft[]>([
    { name: "Floor", rows: 10, seats: 16, accessible: "1-2, 15-16", limited: "" },
  ]);

  const [name, setName] = useState("");
  const [startsAt, setStartsAt] = useState(() => localInput(new Date(Date.now() + 30 * 864e5)));
  const [onSaleAt, setOnSaleAt] = useState("");
  const [perFan, setPerFan] = useState(6);
  const [feePct, setFeePct] = useState("10");
  const [feeFlat, setFeeFlat] = useState("1.50");
  const [taxPct, setTaxPct] = useState("8.25");
  const [queue, setQueue] = useState(false);
  const [perMinute, setPerMinute] = useState(300);
  const [tiers, setTiers] = useState<TierDraft[]>([{ name: "General", kind: "STANDARD", price: "35.00", sections: ["Floor"] }]);

  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  // venues from this organizer's earlier events, so a second show doesn't redraw the room
  useEffect(() => {
    api<SeatlineEvent[]>("/organizer/events")
      .then((evs) => Promise.all([...new Set(evs.map((e) => e.venueId))].map((id) => api<Venue>(`/venues/${id}`).catch(() => null))))
      .then((vs) => setVenues(vs.filter((v): v is Venue => !!v)))
      .catch(() => setVenues([]));
  }, []);

  const sectionNames = useMemo(() => {
    if (venueMode === "new") return sections.map((x) => x.name.trim()).filter(Boolean);
    return venues.find((v) => v.id === venueMode)?.sections.map((x) => x.name) ?? [];
  }, [venueMode, sections, venues]);

  const seatCount = venueMode === "new" ? sections.reduce((n, x) => n + x.rows * x.seats, 0) : venues.find((v) => v.id === venueMode)?.sections.reduce((n, x) => n + x.seats.length, 0) ?? 0;

  const setSection = (i: number, patch: Partial<SectionDraft>) =>
    setSections((cur) => cur.map((x, j) => (j === i ? { ...x, ...patch } : x)));
  const setTier = (i: number, patch: Partial<TierDraft>) => setTiers((cur) => cur.map((x, j) => (j === i ? { ...x, ...patch } : x)));

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    // a section renamed or removed after it was ticked no longer counts
    const sold = tiers.map((t) => ({ ...t, sections: t.sections.filter((x) => sectionNames.includes(x)) }));
    if (sold.some((t) => t.sections.length === 0)) {
      setError("Every ticket type needs at least one section to sell.");
      return;
    }
    setBusy(true);
    try {
      let venueId = venueMode;
      if (venueMode === "new") {
        const body = {
          name: venueName.trim(),
          city: city.trim(),
          sections: sections.map((x) => ({
            name: x.name.trim(),
            rows: Array.from({ length: x.rows }, (_, r) => ({
              label: r < 26 ? LETTERS[r] : LETTERS[Math.floor(r / 26) - 1] + LETTERS[r % 26],
              seats: x.seats,
              accessible: r === x.rows - 1 ? numbers(x.accessible, x.seats) : [],
              limitedView: numbers(x.limited, x.seats),
            })),
          })),
        };
        venueId = (await api<{ id: string }>("/venues", { body })).id;
      }
      const ev = await api<{ id: string }>("/events", {
        body: {
          venueId,
          name: name.trim(),
          startsAt: new Date(startsAt).toISOString(),
          onSaleAt: onSaleAt ? new Date(onSaleAt).toISOString() : undefined,
          perFanLimit: perFan,
          feeBps: Math.round(parseFloat(feePct || "0") * 100),
          feeFlatCents: Math.round(parseFloat(feeFlat || "0") * 100),
          taxBps: Math.round(parseFloat(taxPct || "0") * 100),
          queuePerMinute: queue ? perMinute : undefined,
          tiers: sold.map((t) => ({
            name: t.name.trim(),
            kind: t.kind,
            priceCents: Math.round(parseFloat(t.price || "0") * 100),
            sections: t.sections,
          })),
        },
      });
      router.push(`/organizer/events/${ev.id}`);
    } catch (err) {
      setError(explain(err));
      setBusy(false);
    }
  }

  return (
    <form className={`wrap ${s.page}`} onSubmit={submit}>
      <div className={s.head}>
        <div>
          <Link href="/organizer" className="hint">
            ← Your events
          </Link>
          <h1 className={`display ${s.title}`}>New event</h1>
        </div>
        <p className="hint" style={{ maxWidth: 360, margin: 0 }}>
          It starts as a draft. Nothing goes on sale until you publish it.
        </p>
      </div>

      <section className={s.card} aria-labelledby="venue-h">
        <h2 id="venue-h" className={s.h2}>
          <span className="mono">01</span> Venue
        </h2>
        {venues.length > 0 && (
          <label className="field">
            Venue
            <select className="input" value={venueMode} onChange={(e) => setVenueMode(e.target.value)}>
              <option value="new">A new venue</option>
              {venues.map((v) => (
                <option key={v.id} value={v.id}>
                  {v.name}, {v.city}
                </option>
              ))}
            </select>
          </label>
        )}
        {venueMode === "new" && (
          <>
            <div className={s.two}>
              <label className="field">
                Venue name
                <input className="input" required maxLength={100} value={venueName} onChange={(e) => setVenueName(e.target.value)} />
              </label>
              <label className="field">
                City
                <input className="input" required maxLength={60} value={city} onChange={(e) => setCity(e.target.value)} />
              </label>
            </div>
            <div className={s.sections}>
              {sections.map((x, i) => (
                <fieldset key={i} className={s.section}>
                  <legend className="sr-only">Section {i + 1}</legend>
                  <label className="field">
                    Section
                    <input className="input" required maxLength={30} value={x.name} onChange={(e) => setSection(i, { name: e.target.value })} />
                  </label>
                  <label className="field">
                    Rows
                    <input
                      className="input mono"
                      type="number"
                      min={1}
                      max={60}
                      required
                      value={x.rows}
                      onChange={(e) => setSection(i, { rows: Math.max(1, Math.min(60, Number(e.target.value) || 1)) })}
                    />
                  </label>
                  <label className="field">
                    Seats a row
                    <input
                      className="input mono"
                      type="number"
                      min={1}
                      max={80}
                      required
                      value={x.seats}
                      onChange={(e) => setSection(i, { seats: Math.max(1, Math.min(80, Number(e.target.value) || 1)) })}
                    />
                  </label>
                  <label className="field">
                    Wheelchair spaces (back row)
                    <input className="input mono" placeholder="e.g. 1-2, 15" value={x.accessible} onChange={(e) => setSection(i, { accessible: e.target.value })} />
                  </label>
                  <label className="field">
                    Limited-view seats (every row)
                    <input className="input mono" placeholder="e.g. 1, 16" value={x.limited} onChange={(e) => setSection(i, { limited: e.target.value })} />
                  </label>
                  <button
                    type="button"
                    className="btn btn-sm"
                    onClick={() => setSections((cur) => cur.filter((_, j) => j !== i))}
                    disabled={sections.length === 1}
                    aria-label={`Remove section ${x.name || i + 1}`}
                  >
                    Remove
                  </button>
                </fieldset>
              ))}
            </div>
            <button
              type="button"
              className="btn btn-sm"
              style={{ alignSelf: "flex-start" }}
              onClick={() => setSections((cur) => [...cur, { name: `Section ${cur.length + 1}`, rows: 8, seats: 10, accessible: "", limited: "" }])}
              disabled={sections.length >= 30}
            >
              + Add a section
            </button>
          </>
        )}
        <p className="hint" style={{ margin: 0 }}>
          {seatCount.toLocaleString()} seats in {sectionNames.length} {sectionNames.length === 1 ? "section" : "sections"}.
        </p>
      </section>

      <section className={s.card} aria-labelledby="event-h">
        <h2 id="event-h" className={s.h2}>
          <span className="mono">02</span> The show
        </h2>
        <label className="field">
          Event name
          <input className="input" required maxLength={120} value={name} onChange={(e) => setName(e.target.value)} />
        </label>
        <div className={s.two}>
          <label className="field">
            Starts
            <input className="input" type="datetime-local" required value={startsAt} onChange={(e) => setStartsAt(e.target.value)} />
          </label>
          <label className="field">
            Sales open <span className="hint">(empty: when you publish)</span>
            <input className="input" type="datetime-local" value={onSaleAt} onChange={(e) => setOnSaleAt(e.target.value)} />
          </label>
        </div>
        <div className={s.four}>
          <label className="field">
            Tickets per fan
            <input className="input mono" type="number" min={1} max={20} required value={perFan} onChange={(e) => setPerFan(Number(e.target.value))} />
          </label>
          <label className="field">
            Fee %
            <input className="input mono" type="number" step="0.01" min={0} max={30} required value={feePct} onChange={(e) => setFeePct(e.target.value)} />
          </label>
          <label className="field">
            Fee per ticket $
            <input className="input mono" type="number" step="0.01" min={0} max={20} required value={feeFlat} onChange={(e) => setFeeFlat(e.target.value)} />
          </label>
          <label className="field">
            Tax %
            <input className="input mono" type="number" step="0.01" min={0} max={25} required value={taxPct} onChange={(e) => setTaxPct(e.target.value)} />
          </label>
        </div>
        <label className={s.toggle}>
          <input type="checkbox" checked={queue} onChange={(e) => setQueue(e.target.checked)} />
          <span>
            <strong>Waiting room</strong>
            <small>For big on-sales: fans queue fairly and are let in at a steady rate.</small>
          </span>
        </label>
        {queue && (
          <label className="field" style={{ maxWidth: 240 }}>
            Fans let in per minute
            <input className="input mono" type="number" min={1} max={100000} required value={perMinute} onChange={(e) => setPerMinute(Number(e.target.value))} />
          </label>
        )}
      </section>

      <section className={s.card} aria-labelledby="tiers-h">
        <h2 id="tiers-h" className={s.h2}>
          <span className="mono">03</span> Ticket types
        </h2>
        {tiers.map((t, i) => (
          <fieldset key={i} className={s.tier}>
            <legend className="sr-only">Ticket type {i + 1}</legend>
            <div className={s.three}>
              <label className="field">
                Name
                <input className="input" required maxLength={40} value={t.name} onChange={(e) => setTier(i, { name: e.target.value })} />
              </label>
              <label className="field">
                Kind
                <select className="input" value={t.kind} onChange={(e) => setTier(i, { kind: e.target.value as TierKind })}>
                  {(Object.keys(tierWord) as TierKind[]).map((k) => (
                    <option key={k} value={k}>
                      {tierWord[k]}
                    </option>
                  ))}
                </select>
              </label>
              <label className="field">
                Price $ <span className="hint">(before fees)</span>
                <input className="input mono" type="number" min={0} max={10000} step="0.01" required value={t.price} onChange={(e) => setTier(i, { price: e.target.value })} />
              </label>
            </div>
            <div className={s.checks} role="group" aria-label="Sections sold">
              {sectionNames.map((sec) => (
                <label key={sec} className="chip" style={{ cursor: "pointer" }}>
                  <input
                    type="checkbox"
                    checked={t.sections.includes(sec)}
                    onChange={(e) =>
                      setTier(i, { sections: e.target.checked ? [...t.sections, sec] : t.sections.filter((x) => x !== sec) })
                    }
                  />
                  {sec}
                </label>
              ))}
            </div>
            {t.kind === "STUDENT" && <p className="hint" style={{ margin: 0 }}>Only fans with a .edu email can buy these.</p>}
            <button
              type="button"
              className="btn btn-sm"
              style={{ alignSelf: "flex-start" }}
              onClick={() => setTiers((cur) => cur.filter((_, j) => j !== i))}
              disabled={tiers.length === 1}
            >
              Remove ticket type
            </button>
          </fieldset>
        ))}
        <button
          type="button"
          className="btn btn-sm"
          style={{ alignSelf: "flex-start" }}
          onClick={() => setTiers((cur) => [...cur, { name: "", kind: "STANDARD", price: "", sections: [] }])}
          disabled={tiers.length >= 10}
        >
          + Add a ticket type
        </button>
      </section>

      {error && (
        <div className="alert" role="alert">
          {error}
        </div>
      )}
      <div className={s.submit}>
        <Link href="/organizer" className="btn">
          Cancel
        </Link>
        <button type="submit" className="btn btn-primary" disabled={busy}>
          {busy ? "Saving…" : "Save as draft"}
        </button>
      </div>
    </form>
  );
}
