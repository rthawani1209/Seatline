"use client";

import QRCode from "qrcode";
import { useEffect, useState } from "react";
import { api, explain } from "@/lib/api";
import s from "@/app/tickets/tickets.module.css";

const STEP_MS = 15_000; // matches TicketCodes.STEP_SECONDS on the API

/**
 * The ticket's door code. It changes every 15 seconds, so a screenshot or a forwarded photo
 * stops scanning almost straight away.
 */
export function TicketQr({ ticketId }: { ticketId: string }) {
  const [svg, setSvg] = useState<string | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [now, setNow] = useState(() => Date.now());

  useEffect(() => {
    let stop = false;
    let timer: ReturnType<typeof setTimeout>;
    const load = async () => {
      try {
        const { code } = await api<{ code: string }>(`/me/tickets/${encodeURIComponent(ticketId)}/qr`);
        const out = await QRCode.toString(code, { type: "svg", margin: 0, errorCorrectionLevel: "M", color: { dark: "#141210", light: "#ffffff" } });
        if (!stop) {
          setSvg(out);
          setError(null);
        }
      } catch (e) {
        if (!stop) setError(explain(e));
      }
      // next code at the next step boundary
      if (!stop) timer = setTimeout(load, STEP_MS - (Date.now() % STEP_MS) + 150);
    };
    load();
    const tick = setInterval(() => setNow(Date.now()), 250);
    return () => {
      stop = true;
      clearTimeout(timer);
      clearInterval(tick);
    };
  }, [ticketId]);

  const left = STEP_MS - (now % STEP_MS);

  return (
    <div className={s.qrWrap}>
      <div className={s.qr} role="img" aria-label="Door code for this ticket. It changes every 15 seconds.">
        {svg ? <div className={s.qrSvg} dangerouslySetInnerHTML={{ __html: svg }} /> : <div className={s.qrPlaceholder} />}
      </div>
      {error ? (
        <p className={s.qrError} role="alert">
          {error}
        </p>
      ) : (
        <div className={`mono ${s.qrNote}`}>
          <span className={s.qrBar} aria-hidden="true">
            <span style={{ width: `${(left / STEP_MS) * 100}%` }} />
          </span>
          new code in {Math.ceil(left / 1000)}s · screenshots won&apos;t scan
        </div>
      )}
    </div>
  );
}
