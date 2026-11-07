"use client";

import jsQR from "jsqr";
import { useCallback, useEffect, useRef, useState } from "react";
import { RoleGate } from "@/components/RoleGate";
import { api, explain } from "@/lib/api";
import { seatParts } from "@/lib/format";
import { useApi } from "@/lib/hooks";
import type { SeatlineEvent } from "@/lib/types";
import s from "./door.module.css";

type Result = "OK" | "ALREADY_SCANNED" | "WRONG_EVENT" | "OLD_CODE" | "EXPIRED" | "INVALID";
type Scan = { result: Result; seat: string | null; tier: string | null; eventName: string | null; scannedAt: string | null };

const WORDS: Record<Result, { head: string; tone: "go" | "stop" | "warn"; say: string }> = {
  OK: { head: "Let in", tone: "go", say: "Valid ticket." },
  ALREADY_SCANNED: { head: "Already used", tone: "stop", say: "This ticket was scanned before." },
  WRONG_EVENT: { head: "Wrong show", tone: "stop", say: "This ticket is for a different event." },
  OLD_CODE: { head: "Old code", tone: "stop", say: "This ticket was sent to someone else. Their phone has the live code." },
  EXPIRED: { head: "Screenshot?", tone: "warn", say: "The code is too old. Ask them to open their ticket in the app." },
  INVALID: { head: "Not a ticket", tone: "stop", say: "Not a Seatline ticket, or it was returned." },
};

export default function DoorPage() {
  return (
    <RoleGate roles={["DOOR_STAFF", "ADMIN"]}>
      <Door />
    </RoleGate>
  );
}

function Door() {
  const { data: events } = useApi<SeatlineEvent[]>("/events");
  const [eventId, setEventId] = useState("");
  const [scan, setScan] = useState<Scan | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [manual, setManual] = useState("");
  const [camera, setCamera] = useState<"off" | "starting" | "on" | "denied">("off");
  const [count, setCount] = useState(0);
  const videoRef = useRef<HTMLVideoElement>(null);
  const streamRef = useRef<MediaStream | null>(null);
  const lastCode = useRef<{ code: string; at: number } | null>(null);
  const busy = useRef(false);
  const clearTimer = useRef<ReturnType<typeof setTimeout>>(undefined);

  useEffect(() => {
    if (!eventId && events?.length) setEventId(events[0].id);
  }, [events, eventId]);

  const check = useCallback(
    async (code: string) => {
      const now = Date.now();
      // the camera sees the same code many times a second; send it once
      if (busy.current || (lastCode.current?.code === code && now - lastCode.current.at < 4000)) return;
      busy.current = true;
      lastCode.current = { code, at: now };
      setError(null);
      try {
        const r = await api<Scan>("/scan", { body: { code, eventId: eventId || undefined } });
        setScan(r);
        if (r.result === "OK") setCount((c) => c + 1);
        navigator.vibrate?.(r.result === "OK" ? 80 : [60, 60, 60]);
        clearTimeout(clearTimer.current);
        clearTimer.current = setTimeout(() => setScan(null), 3500);
      } catch (e) {
        setError(explain(e));
      } finally {
        busy.current = false;
      }
    },
    [eventId],
  );

  const stop = useCallback(() => {
    streamRef.current?.getTracks().forEach((t) => t.stop());
    streamRef.current = null;
    setCamera("off");
  }, []);

  async function start() {
    setCamera("starting");
    try {
      const stream = await navigator.mediaDevices.getUserMedia({ video: { facingMode: "environment" }, audio: false });
      streamRef.current = stream;
      if (videoRef.current) {
        videoRef.current.srcObject = stream;
        await videoRef.current.play();
      }
      setCamera("on");
    } catch {
      setCamera("denied");
    }
  }

  // read frames while the camera is on
  useEffect(() => {
    if (camera !== "on") return;
    const canvas = document.createElement("canvas");
    const ctx = canvas.getContext("2d", { willReadFrequently: true });
    let raf = 0;
    let lastRead = 0;
    const read = (t: number) => {
      raf = requestAnimationFrame(read);
      const v = videoRef.current;
      if (!v || !ctx || v.readyState < 2 || t - lastRead < 120) return;
      lastRead = t;
      const scale = Math.min(1, 640 / v.videoWidth);
      canvas.width = Math.round(v.videoWidth * scale);
      canvas.height = Math.round(v.videoHeight * scale);
      ctx.drawImage(v, 0, 0, canvas.width, canvas.height);
      const img = ctx.getImageData(0, 0, canvas.width, canvas.height);
      const found = jsQR(img.data, img.width, img.height, { inversionAttempts: "dontInvert" });
      if (found?.data) check(found.data);
    };
    raf = requestAnimationFrame(read);
    return () => cancelAnimationFrame(raf);
  }, [camera, check]);

  useEffect(() => stop, [stop]);

  const words = scan ? WORDS[scan.result] : null;
  const seat = scan?.seat ? seatParts(scan.seat) : null;

  return (
    <div className={s.shell}>
      <div className={s.top}>
        <h1 className="display" style={{ fontSize: 36, margin: 0 }}>
          Door
        </h1>
        <span className="mono hint" aria-live="polite">
          {count} let in
        </span>
      </div>

      <label className="field">
        Scanning for
        <select className="input" value={eventId} onChange={(e) => setEventId(e.target.value)}>
          {(events ?? []).map((e) => (
            <option key={e.id} value={e.id}>
              {e.name}
            </option>
          ))}
          <option value="">Any event</option>
        </select>
      </label>

      <div className={s.viewport} data-tone={words?.tone ?? "idle"}>
        <video ref={videoRef} className={s.video} playsInline muted aria-hidden="true" data-on={camera === "on"} />
        {camera !== "on" && !scan && (
          <div className={s.idle}>
            {camera === "denied" ? (
              <p>Camera blocked. Allow camera access for this site, or type the code below.</p>
            ) : (
              <button type="button" className="btn btn-primary" onClick={start} disabled={camera === "starting"}>
                {camera === "starting" ? "Starting camera…" : "Start camera"}
              </button>
            )}
          </div>
        )}
        {camera === "on" && !scan && <div className={s.frame} aria-hidden="true" />}
        {scan && words && (
          <div className={s.result} role="status" data-testid="scan-result" data-result={scan.result}>
            <div className={`display ${s.resultHead}`}>{words.head}</div>
            {seat && (
              <div className={`display ${s.resultSeat}`}>
                {seat.section} · {seat.row}
                {seat.number}
              </div>
            )}
            <p className={s.resultSay}>
              {words.say}
              {scan.result === "ALREADY_SCANNED" && scan.scannedAt
                ? ` First scanned at ${new Date(scan.scannedAt).toLocaleTimeString("en-US", { hour: "numeric", minute: "2-digit" })}.`
                : ""}
              {scan.result === "WRONG_EVENT" && scan.eventName ? ` It's for ${scan.eventName}.` : ""}
            </p>
            <button type="button" className="btn" onClick={() => setScan(null)}>
              Next
            </button>
          </div>
        )}
      </div>

      {camera === "on" && (
        <button type="button" className="btn btn-sm" onClick={stop} style={{ alignSelf: "flex-start" }}>
          Stop camera
        </button>
      )}

      {error && (
        <div className="alert" role="alert">
          {error}
        </div>
      )}

      <form
        className={s.manual}
        onSubmit={(e) => {
          e.preventDefault();
          lastCode.current = null;
          if (manual.trim()) check(manual.trim());
        }}
      >
        <label className="field" style={{ flex: 1 }}>
          Or type the code
          <input className="input mono" value={manual} onChange={(e) => setManual(e.target.value)} placeholder="SL1.…" maxLength={300} />
        </label>
        <button type="submit" className="btn btn-solid">
          Check
        </button>
      </form>
    </div>
  );
}
