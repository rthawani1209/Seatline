"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useCallback, useEffect, useRef, useState } from "react";
import { AuthCard, authStyles as s } from "@/components/AuthCard";
import { api, explain } from "@/lib/api";

export default function VerifyPage() {
  return (
    <Suspense>
      <Verify />
    </Suspense>
  );
}

// The email has both a link (?token=) and the code itself, for people reading it on another device.
function Verify() {
  const linkToken = useSearchParams().get("token");
  const router = useRouter();
  const [code, setCode] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const sent = useRef(false);

  const verify = useCallback(
    async (token: string) => {
      setBusy(true);
      setError(null);
      try {
        await api("/auth/verify", { body: { token } });
        router.replace("/login?verified=1");
      } catch (e) {
        setError(explain(e));
        setBusy(false);
      }
    },
    [router],
  );

  useEffect(() => {
    // codes are single-use, so don't send it twice (React runs effects twice in development)
    if (!linkToken || sent.current) return;
    sent.current = true;
    verify(linkToken);
  }, [linkToken, verify]);

  return (
    <AuthCard kicker="EMAIL" title="Verify email">
      <form
        className={s.form}
        style={{ padding: 0 }}
        onSubmit={(e) => {
          e.preventDefault();
          verify(code.trim());
        }}
      >
        {error && (
          <div className="alert" role="alert">
            {error}
          </div>
        )}
        {linkToken && !error ? (
          <p style={{ margin: 0 }} aria-live="polite">
            Checking your link…
          </p>
        ) : (
          <>
            <label className="field">
              Confirmation code
              <input
                className="input mono"
                required
                maxLength={100}
                autoComplete="one-time-code"
                value={code}
                onChange={(e) => setCode(e.target.value)}
              />
              <span className="hint">It&apos;s in the email we sent when you signed up, and works for 24 hours.</span>
            </label>
            <button type="submit" className="btn btn-primary btn-block" disabled={busy || !code.trim()}>
              {busy ? "Checking…" : "Verify"}
            </button>
          </>
        )}
        <p className={s.alt}>
          No email? <Link href="/register">Sign up again</Link>
        </p>
      </form>
    </AuthCard>
  );
}
