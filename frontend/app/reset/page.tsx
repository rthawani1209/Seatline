"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { AuthCard, authStyles as s } from "@/components/AuthCard";
import { api, explain } from "@/lib/api";
import { useHydrated } from "@/lib/hooks";

export default function ResetPage() {
  return (
    <Suspense>
      <Reset />
    </Suspense>
  );
}

function Reset() {
  const router = useRouter();
  const token = useSearchParams().get("token") ?? "";
  const [password, setPassword] = useState("");
  const [again, setAgain] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const hydrated = useHydrated();

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    if (password !== again) {
      setError("The passwords don't match.");
      return;
    }
    setBusy(true);
    try {
      await api("/auth/reset", { body: { token, password } });
      router.replace("/login?reset=1");
    } catch (err) {
      setError(explain(err));
      setBusy(false);
    }
  }

  if (!token) {
    return (
      <AuthCard kicker="LOST STUB" title="Reset password">
        <div className="alert" role="alert">
          This reset link is missing its code. Open the link from the email again, or ask for a new one.
        </div>
        <p className={s.alt}>
          <Link href="/forgot">Get a new link</Link>
        </p>
      </AuthCard>
    );
  }

  return (
    <AuthCard kicker="LOST STUB" title="Choose a new password">
      <form onSubmit={submit} className={s.form} style={{ padding: 0 }}>
        {/* nothing can be typed until the page is live, so nothing typed is lost */}
        <fieldset disabled={!hydrated} className="contents">
        {error && (
          <div className="alert" role="alert">
            {error}
          </div>
        )}
        <label className="field">
          New password
          <input
            className="input"
            type="password"
            autoComplete="new-password"
            required
            minLength={12}
            maxLength={128}
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
          <span className="hint">At least 12 characters. This signs you out everywhere else.</span>
        </label>
        <label className="field">
          New password again
          <input
            className="input"
            type="password"
            autoComplete="new-password"
            required
            minLength={12}
            maxLength={128}
            value={again}
            onChange={(e) => setAgain(e.target.value)}
          />
        </label>
        <button type="submit" className="btn btn-primary btn-block" disabled={!hydrated || busy}>
          {busy ? "Saving…" : "Save new password"}
        </button>
        <p className={s.alt}>
          Link expired? <Link href="/forgot">Get a new one</Link>
        </p>
        </fieldset>
      </form>
    </AuthCard>
  );
}
