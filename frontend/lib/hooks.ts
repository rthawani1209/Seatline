"use client";

import { useEffect, useRef, useState } from "react";
import { api } from "./api";

/** Loads a GET once (and again when the path changes). */
export function useApi<T>(path: string | null) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [loading, setLoading] = useState(!!path);
  const [n, setN] = useState(0);

  useEffect(() => {
    if (!path) return;
    const ctl = new AbortController();
    setLoading(true);
    api<T>(path, { signal: ctl.signal })
      .then((d) => {
        setData(d);
        setError(null);
      })
      .catch((e) => {
        if ((e as Error).name !== "AbortError") setError(e);
      })
      .finally(() => {
        if (!ctl.signal.aborted) setLoading(false);
      });
    return () => ctl.abort();
  }, [path, n]);

  return { data, error, loading, reload: () => setN((x) => x + 1), setData };
}

/** Polls a GET every `ms` while the tab is visible. */
export function usePoll<T>(path: string | null, ms: number) {
  const [data, setData] = useState<T | null>(null);
  const [error, setError] = useState<unknown>(null);
  const [kick, setKick] = useState(0);

  useEffect(() => {
    if (!path) return;
    let stop = false;
    let timer: ReturnType<typeof setTimeout>;
    const tick = async () => {
      if (stop) return;
      if (document.visibilityState === "visible") {
        try {
          const d = await api<T>(path);
          if (!stop) {
            setData(d);
            setError(null);
          }
        } catch (e) {
          if (!stop) setError(e);
        }
      }
      if (!stop) timer = setTimeout(tick, ms);
    };
    tick();
    const onVisible = () => {
      if (document.visibilityState === "visible") {
        clearTimeout(timer);
        tick();
      }
    };
    document.addEventListener("visibilitychange", onVisible);
    return () => {
      stop = true;
      clearTimeout(timer);
      document.removeEventListener("visibilitychange", onVisible);
    };
  }, [path, ms, kick]);

  return { data, error, refresh: () => setKick((k) => k + 1) };
}

/** Seconds left until `iso`, ticking once a second. */
export function useCountdown(iso: string | null | undefined): number | null {
  const [left, setLeft] = useState<number | null>(null);
  const target = useRef<number | null>(null);
  useEffect(() => {
    target.current = iso ? Date.parse(iso) : null;
    if (target.current == null) {
      setLeft(null);
      return;
    }
    const tick = () => setLeft(Math.max(0, Math.round((target.current! - Date.now()) / 1000)));
    tick();
    const t = setInterval(tick, 1000);
    return () => clearInterval(t);
  }, [iso]);
  return left;
}

/**
 * False until React has taken over the page. A form submitted before that does a plain browser
 * submit (a reload that loses what was typed), so submit buttons wait for this.
 */
export function useHydrated() {
  const [hydrated, setHydrated] = useState(false);
  useEffect(() => setHydrated(true), []);
  return hydrated;
}
