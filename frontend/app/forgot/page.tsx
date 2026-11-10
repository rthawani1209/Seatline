"use client";

import Link from "next/link";
import { useState } from "react";
import { AuthCard, authStyles as s } from "@/components/AuthCard";
import { api, explain } from "@/lib/api";
import { useHydrated } from "@/lib/hooks";

export default function ForgotPage() {
  const [email, setEmail] = useState("");
  const [sent, setSent] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const hydrated = useHydrated();

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api("/auth/forgot", { body: { email: email.trim() } });
      setSent(true);
    } catch (err) {
      setError(explain(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard kicker="LOST STUB" title="Reset password">
      {sent ? (
        <div className={s.form} style={{ padding: 0 }}>
          {/* same answer whether or not the account exists, so this can't be used to find accounts */}
          <div className="notice" role="status">
            If there's a Seatline account for {email.trim()}, we've emailed it a link to choose a new password. The link
            works for an hour.
          </div>
          <p className={s.alt}>
            <Link href="/login">Back to sign in</Link>
          </p>
        </div>
      ) : (
        <form onSubmit={submit} className={s.form} style={{ padding: 0 }}>
          {/* nothing can be typed until the page is live, so nothing typed is lost */}
          <fieldset disabled={!hydrated} className="contents">
          {error && (
            <div className="alert" role="alert">
              {error}
            </div>
          )}
          <label className="field">
            Email
            <input className="input" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
          </label>
          <button type="submit" className="btn btn-primary btn-block" disabled={!hydrated || busy}>
            {busy ? "Sending…" : "Email me a reset link"}
          </button>
          <p className={s.alt}>
            Remembered it? <Link href="/login">Sign in</Link>
          </p>
          </fieldset>
        </form>
      )}
    </AuthCard>
  );
}
