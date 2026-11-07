"use client";

import Link from "next/link";
import { useParams, useRouter } from "next/navigation";
import { useEffect, useMemo, useRef, useState } from "react";
import { api, ApiError, explain } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { clock } from "@/lib/format";
import { useApi, useCountdown } from "@/lib/hooks";
import type { QueueStatus, SeatState, SeatlineEvent } from "@/lib/types";
import s from "./queue.module.css";

export default function QueuePage() {
  const { id } = useParams<{ id: string }>();
  const router = useRouter();
  const { me, ready } = useAuth();
  const { data: event } = useApi<SeatlineEvent>(`/events/${id}`);
  const [status, setStatus] = useState<QueueStatus | null>(null);
  const [seatsLeft, setSeatsLeft] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const joined = useRef(false);

  useEffect(() => {
    if (ready && !me) router.replace(`/login?next=${encodeURIComponent(`/events/${id}/queue`)}`);
  }, [ready, me, id, router]);

  // join (keeps your place if you're already in line), then poll
  useEffect(() => {
    if (!me) return;
    let stop = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      try {
        const st = joined.current
          ? await api<QueueStatus>(`/events/${id}/queue`)
          : await api<QueueStatus>(`/events/${id}/queue`, { method: "POST" });
        joined.current = true;
        if (!stop) {
          setStatus(st);
          setError(null);
        }
        const seats = await api<SeatState[]>(`/events/${id}/seats`);
        if (!stop) setSeatsLeft(seats.filter((x) => x.status === "FREE").length);
      } catch (e) {
        if (e instanceof ApiError && e.code === "not_in_queue") joined.current = false;
        if (!stop) setError(e instanceof ApiError && e.code === "no_queue" ? "This event has no waiting room." : explain(e));
      }
      if (!stop) timer = setTimeout(tick, 3000);
    };
    tick();
    return () => {
      stop = true;
      clearTimeout(timer);
    };
  }, [id, me]);

  const opensIn = useCountdown(status?.opensAt && Date.parse(status.opensAt) > Date.now() ? status.opensAt : null);
  const passLeft = useCountdown(status?.admitted ? status.passExpiresAt : null);
  const crowd = useMemo(() => dots(status?.position ?? 0), [status?.position]);

  const admitted = status?.admitted && (passLeft ?? 1) > 0;
  const passGone = status?.admitted && passLeft === 0;
  const early = !!status && !status.admitted && status.position === 0;

  return (
    <div className={s.shell}>
      <div className={s.top}>
        <span className="eyebrow" style={{ color: "var(--accent)" }}>
          {event?.name ?? " "}
        </span>
        <span className={`mono ${s.live}`}>
          <span className="live-dot" aria-hidden="true" /> LIVE
        </span>
      </div>

      {error && (
        <div className="alert" role="alert">
          {error}
        </div>
      )}

      <div aria-live="polite" className={s.headline}>
        {!status && !error && <p className="hint">Getting your place in line…</p>}
        {early && (
          <>
            <p className={s.kicker}>The sale opens in</p>
            <div className={`display ${s.big}`}>{opensIn != null ? clock(opensIn) : "soon"}</div>
            <p className={`mono ${s.sub}`}>you&apos;re in the early group</p>
          </>
        )}
        {status && !status.admitted && status.position > 0 && (
          <>
            <p className={s.kicker}>You&apos;re in line. Your place:</p>
            <div className={`display ${s.big}`} data-testid="queue-position">
              #{status.position.toLocaleString()}
            </div>
          </>
        )}
        {admitted && (
          <>
            <p className={s.kicker}>It&apos;s your turn.</p>
            <div className={`display ${s.big} ${s.go}`}>GO</div>
            <p className={`mono ${s.sub}`}>pass expires in {passLeft != null ? clock(passLeft) : "10:00"}</p>
          </>
        )}
        {passGone && (
          <>
            <p className={s.kicker}>Your turn ran out.</p>
            <p className={s.sub}>Passes last 10 minutes so the next fans get a go. Join again to get a new place.</p>
          </>
        )}
      </div>

      {status && !status.admitted && status.position > 0 && (
        <div className={s.crowd} role="img" aria-label={`About ${status.position.toLocaleString()} fans ahead of you`}>
          {crowd.map((d, i) => (
            <span key={i} className={s.dot} style={d} />
          ))}
          <span className={s.you} style={{ left: "62%", top: "50%" }} />
          <span className={`mono ${s.youTag}`} style={{ left: "62%", top: "50%" }}>
            YOU
          </span>
          <span className={s.doors} aria-hidden="true" />
          <span className={`mono ${s.doorsTag}`}>DOORS</span>
        </div>
      )}

      <dl className={s.stats}>
        <div>
          <dt>Est. wait</dt>
          <dd className="mono">
            {admitted ? "now" : status && status.position > 0 ? `~${Math.max(1, Math.ceil(status.estimatedWaitSeconds / 60))} min` : "–"}
          </dd>
        </div>
        <div>
          <dt>Letting in</dt>
          <dd className="mono">{event?.queuePerMinute ? `${event.queuePerMinute}/min` : "–"}</dd>
        </div>
        <div>
          <dt>Seats left</dt>
          <dd className="mono">{seatsLeft != null ? seatsLeft.toLocaleString() : "–"}</dd>
        </div>
      </dl>

      {admitted ? (
        <Link href={`/events/${id}`} className="btn btn-primary btn-block" style={{ minHeight: 56, fontSize: 17 }}>
          Pick your seats
        </Link>
      ) : passGone ? (
        <button
          type="button"
          className="btn btn-primary btn-block"
          onClick={() => {
            joined.current = false;
            setStatus(null);
          }}
        >
          Join again
        </button>
      ) : (
        <p className={s.note}>
          Keep this tab open. Refreshing won&apos;t move you forward or back, and when it&apos;s your turn you&apos;ll have 10
          minutes to pick seats.
        </p>
      )}

      <div className={s.foot}>
        <span>
          Got here before the sale opened?
          <br />
          Everyone early got a random spot, so there was no point refreshing at 9:59.
        </span>
        <Link href={`/events/${id}`} className="btn btn-sm">
          Event page
        </Link>
      </div>
    </div>
  );
}

// A loose crowd heading for the doors on the right: fewer dots as you get closer.
function dots(position: number) {
  const n = Math.min(150, Math.max(12, Math.round(Math.log10(position + 1) * 45)));
  let seed = 7;
  const rnd = () => {
    seed = (seed * 16807) % 2147483647;
    return (seed - 1) / 2147483646;
  };
  const out: React.CSSProperties[] = [];
  for (let i = 0; i < n; i++) {
    const t = Math.pow(rnd(), 0.75);
    const spread = (1 - t) * 44 + 4;
    out.push({
      left: `${4 + t * 88}%`,
      top: `${Math.max(4, Math.min(92, 50 + (rnd() - 0.5) * 2 * spread))}%`,
      opacity: 0.25 + 0.6 * t,
    });
  }
  return out;
}
