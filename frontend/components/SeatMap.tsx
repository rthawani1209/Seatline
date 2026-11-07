"use client";

import { memo, useCallback, useEffect, useMemo, useRef, useState } from "react";
import type { Seat, SeatStatus, Venue } from "@/lib/types";
import s from "./SeatMap.module.css";

const UNIT = 22; // px per seat at 100%
const SEAT = 16;
const STAGE_H = 46;

export type SeatLook = "free" | "held" | "sold" | "off";

type Placed = Seat & { section: string; px: number; py: number };

type Props = {
  venue: Venue;
  status: Map<string, SeatStatus>;
  /** sections the chosen ticket type is sold in */
  sellable: Set<string>;
  selected: Set<string>;
  accessibleOnly: boolean;
  hideLimited: boolean;
  onToggle: (seat: Seat & { section: string }) => void;
};

/**
 * The venue drawn from the API's seat grid (x = column across the venue, y = row from the stage).
 * Each row is centred in its section and bent slightly towards the stage. Seats are buttons:
 * Tab reaches the map, arrow keys move between seats, Enter or Space picks one.
 */
export const SeatMap = memo(function SeatMap(props: Props) {
  const { venue, status, sellable, selected, accessibleOnly, hideLimited, onToggle } = props;
  const [zoom, setZoom] = useState(1);
  const [focusLabel, setFocusLabel] = useState<string | null>(null);
  const svgRef = useRef<SVGSVGElement>(null);
  const scrollRef = useRef<HTMLDivElement>(null);
  const [fit, setFit] = useState(1);

  const layout = useMemo(() => place(venue), [venue]);

  // 100% means "the whole venue fits the box"; zooming in scrolls
  useEffect(() => {
    const el = scrollRef.current;
    if (!el) return;
    // on a phone the whole venue would be too small to tap, so start larger and let it scroll
    const measure = () => setFit(Math.min(1.6, Math.max(0.7, (el.clientWidth - 28) / layout.width)));
    measure();
    // start looking at the middle of the stage when the venue is wider than the box
    requestAnimationFrame(() => (el.scrollLeft = (el.scrollWidth - el.clientWidth) / 2));
    const ro = new ResizeObserver(measure);
    ro.observe(el);
    return () => ro.disconnect();
  }, [layout.width]);

  const look = useCallback(
    (seat: Placed): SeatLook => {
      const st = status.get(seat.label);
      if (st === "SOLD") return "sold";
      if (st === "HELD") return "held";
      if (!st) return "off"; // not on sale (no tier covers its section)
      if (!sellable.has(seat.section)) return "off";
      if (accessibleOnly && !seat.accessible) return "off";
      if (hideLimited && seat.limitedView) return "off";
      return "free";
    },
    [status, sellable, accessibleOnly, hideLimited],
  );

  // the seat that holds the tab stop: the last focused one, else the first pickable one
  const tabStop = useMemo(() => {
    if (focusLabel && layout.byLabel.has(focusLabel)) return focusLabel;
    const first = layout.seats.find((p) => look(p) === "free");
    return first?.label ?? layout.seats[0]?.label ?? null;
  }, [focusLabel, layout, look]);

  const moveFocus = (from: Placed, dx: number, dy: number) => {
    const next = nearest(layout.seats, from, dx, dy);
    if (!next) return;
    setFocusLabel(next.label);
    const el = svgRef.current?.querySelector<SVGGElement>(`[data-seat="${CSS.escape(next.label)}"]`);
    el?.focus();
    el?.scrollIntoView?.({ block: "nearest", inline: "nearest" });
  };

  const onKey = (e: React.KeyboardEvent, seat: Placed) => {
    const moves: Record<string, [number, number]> = {
      ArrowLeft: [-1, 0],
      ArrowRight: [1, 0],
      ArrowUp: [0, -1],
      ArrowDown: [0, 1],
    };
    if (moves[e.key]) {
      e.preventDefault();
      moveFocus(seat, ...moves[e.key]);
    } else if (e.key === "Enter" || e.key === " ") {
      e.preventDefault();
      if (look(seat) === "free") onToggle(seat);
    } else if (e.key === "+" || e.key === "=") {
      setZoom((z) => Math.min(3, z + 0.25));
    } else if (e.key === "-") {
      setZoom((z) => Math.max(0.5, z - 0.25));
    }
  };

  // mouse drag pans the map (touch already scrolls); a drag doesn't count as a click on a seat
  const drag = useRef<{ x: number; y: number; left: number; top: number; moved: boolean } | null>(null);
  const onPointerDown = (e: React.PointerEvent) => {
    if (e.pointerType !== "mouse" || e.button !== 0 || !scrollRef.current) return;
    const el = scrollRef.current;
    drag.current = { x: e.clientX, y: e.clientY, left: el.scrollLeft, top: el.scrollTop, moved: false };
  };
  const onPointerMove = (e: React.PointerEvent) => {
    const d = drag.current;
    if (!d || !scrollRef.current) return;
    const dx = e.clientX - d.x;
    const dy = e.clientY - d.y;
    if (!d.moved && Math.abs(dx) + Math.abs(dy) < 6) return;
    d.moved = true;
    scrollRef.current.scrollLeft = d.left - dx;
    scrollRef.current.scrollTop = d.top - dy;
  };
  const endDrag = () => {
    // let the click that ends a drag see it was a drag, then forget it
    setTimeout(() => (drag.current = null), 0);
  };

  const seatOf = (e: React.SyntheticEvent): Placed | undefined => {
    const label = (e.target as Element).closest?.("[data-seat]")?.getAttribute("data-seat");
    return label ? layout.byLabel.get(label) : undefined;
  };

  const w = layout.width * fit * zoom;
  const h = layout.height * fit * zoom;

  return (
    <div className={s.frame}>
      <div className={s.bar}>
        <span>Tap a seat to pick it</span>
      <div className={s.tools} role="group" aria-label="Zoom">
        <button type="button" className={s.tool} onClick={() => setZoom((z) => Math.max(0.5, z - 0.25))} aria-label="Zoom out">
          −
        </button>
        <span className={`mono ${s.zoom}`} aria-live="polite">
          {Math.round(zoom * 100)}%
        </span>
        <button type="button" className={s.tool} onClick={() => setZoom((z) => Math.min(3, z + 0.25))} aria-label="Zoom in">
          +
        </button>
        <button type="button" className={s.tool} onClick={() => setZoom(1)} aria-label="Reset zoom" style={{ width: "auto", padding: "0 10px" }}>
          Fit
        </button>
      </div>
      </div>

      <div
        className={s.scroller}
        ref={scrollRef}
        onPointerDown={onPointerDown}
        onPointerMove={onPointerMove}
        onPointerUp={endDrag}
        onPointerLeave={endDrag}
      >
        <svg
          ref={svgRef}
          width={w}
          height={h}
          viewBox={`0 0 ${layout.width} ${layout.height}`}
          className={s.svg}
          role="group"
          aria-label={`Seat map for ${venue.name}. Use arrow keys to move between seats and Enter to pick one.`}
          onClick={(e) => {
            if (drag.current?.moved) return;
            const seat = seatOf(e);
            if (seat && look(seat) === "free") onToggle(seat);
          }}
          onKeyDown={(e) => {
            const seat = seatOf(e);
            if (seat) onKey(e, seat);
          }}
          onFocus={(e) => {
            const seat = seatOf(e);
            if (seat) setFocusLabel(seat.label);
          }}
        >
          <defs>
            <pattern id="held" width="4" height="4" patternUnits="userSpaceOnUse" patternTransform="rotate(45)">
              <rect width="2" height="4" className={s.heldStripe} />
            </pattern>
          </defs>

          <path
            d={`M ${layout.width * 0.18} 8 H ${layout.width * 0.82} Q ${layout.width * 0.82} ${STAGE_H} ${layout.width * 0.5} ${STAGE_H} Q ${layout.width * 0.18} ${STAGE_H} ${layout.width * 0.18} 8 Z`}
            className={s.stage}
          />
          <text x={layout.width / 2} y={STAGE_H / 2 + 8} textAnchor="middle" className={s.stageText}>
            STAGE
          </text>

          {layout.sections.map((sec) => (
            <text key={sec.name} x={sec.cx} y={STAGE_H + 26} textAnchor="middle" className={s.sectionText}>
              {sec.name.toUpperCase()}
            </text>
          ))}

          {layout.rowLabels.map((r) => (
            <text key={r.key} x={r.x} y={r.y + 4} textAnchor="middle" className={s.rowText} aria-hidden="true">
              {r.label}
            </text>
          ))}

          {layout.seats.map((seat) => {
            const kind = look(seat);
            const picked = selected.has(seat.label);
            return (
              <SeatDot key={seat.label} seat={seat} kind={kind} picked={picked} tabbable={seat.label === tabStop} />
            );
          })}
        </svg>
      </div>
    </div>
  );
});

// handlers live on the <svg> (one set for every seat), so a poll only re-renders seats that changed
const SeatDot = memo(function SeatDot({
  seat,
  kind,
  picked,
  tabbable,
}: {
  seat: Placed;
  kind: SeatLook;
  picked: boolean;
  tabbable: boolean;
}) {
  const words = [
    `${seat.section}, row ${seat.row}, seat ${seat.number}`,
    seat.accessible ? "wheelchair accessible" : null,
    seat.limitedView ? "limited view" : null,
    picked ? "selected" : kind === "free" ? "available" : kind === "held" ? "held by someone" : kind === "sold" ? "sold" : "not available for this ticket type",
  ]
    .filter(Boolean)
    .join(", ");

  const cls = [s.seat, s[kind], picked ? s.picked : "", seat.accessible ? s.accessible : "", seat.limitedView ? s.limited : ""].join(" ");
  return (
    <g
      data-seat={seat.label}
      data-state={picked ? "picked" : kind}
      role="button"
      tabIndex={tabbable ? 0 : -1}
      aria-label={words}
      aria-pressed={picked}
      aria-disabled={kind !== "free"}
      className={cls}
    >
      <title>{words}</title>
      <rect
        x={seat.px - SEAT / 2}
        y={seat.py - SEAT / 2}
        width={SEAT}
        height={SEAT}
        rx={seat.accessible ? SEAT / 2 : 4}
        fill={kind === "held" ? "url(#held)" : undefined}
      />
      {seat.limitedView && kind !== "sold" && (
        <path d={`M ${seat.px - 5} ${seat.py + 5} L ${seat.px + 5} ${seat.py - 5}`} className={s.slash} />
      )}
    </g>
  );
});

function place(venue: Venue) {
  const seats: Placed[] = [];
  const sections: { name: string; cx: number }[] = [];
  const rowLabels: { key: string; label: string; x: number; y: number }[] = [];
  const top = STAGE_H + 48;
  let maxX = 0;
  let maxY = 0;

  const totalCols = Math.max(1, ...venue.sections.flatMap((sec) => sec.seats.map((x) => x.x + 1)));
  const mid = (totalCols - 1) / 2;

  for (const sec of venue.sections) {
    if (sec.seats.length === 0) continue;
    const minX = Math.min(...sec.seats.map((x) => x.x));
    const width = Math.max(...sec.seats.map((x) => x.x)) - minX + 1;
    const rows = new Map<number, Seat[]>();
    for (const seat of sec.seats) {
      if (!rows.has(seat.y)) rows.set(seat.y, []);
      rows.get(seat.y)!.push(seat);
    }
    for (const [y, row] of rows) {
      const shift = (width - row.length) / 2; // centre short rows
      row.sort((a, b) => a.x - b.x);
      row.forEach((seat, i) => {
        const col = minX + shift + i;
        const bend = Math.pow((col - mid) / Math.max(mid, 1), 2) * 10; // rows curve towards the stage
        const px = UNIT + col * UNIT + UNIT / 2;
        const py = top + y * UNIT + UNIT / 2 - bend;
        seats.push({ ...seat, section: sec.name, px, py });
        maxX = Math.max(maxX, px);
        maxY = Math.max(maxY, py);
        if (i === 0) rowLabels.push({ key: `${sec.name}-${y}`, label: seat.row, x: px - UNIT, y: py });
      });
    }
    sections.push({ name: sec.name, cx: UNIT + (minX + width / 2) * UNIT });
  }

  return {
    seats,
    sections,
    rowLabels,
    byLabel: new Map(seats.map((p) => [p.label, p])),
    width: Math.max(maxX + UNIT * 1.5, 320),
    height: maxY + UNIT * 1.5,
  };
}

/** Next seat in a direction: mostly along that axis, preferring ones in line with this one. */
function nearest(all: Placed[], from: Placed, dx: number, dy: number): Placed | null {
  let best: Placed | null = null;
  let bestScore = Infinity;
  for (const p of all) {
    if (p === from) continue;
    const ax = p.px - from.px;
    const ay = p.py - from.py;
    const along = ax * dx + ay * dy;
    if (along <= UNIT * 0.3) continue;
    const across = Math.abs(ax * dy) + Math.abs(ay * dx);
    const score = along + across * 3;
    if (score < bestScore) {
      bestScore = score;
      best = p;
    }
  }
  return best;
}
