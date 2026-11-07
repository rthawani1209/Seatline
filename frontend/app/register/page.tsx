"use client";

import Link from "next/link";
import { useState } from "react";
import { AuthCard, authStyles as s } from "@/components/AuthCard";
import { api, explain } from "@/lib/api";

export default function RegisterPage() {
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [name, setName] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [done, setDone] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await api("/auth/register", { body: { email, password, displayName: name.trim() } });
      setDone(true);
    } catch (err) {
      setError(explain(err));
    } finally {
      setBusy(false);
    }
  }

  if (done) {
    return (
      <AuthCard kicker="ALMOST THERE" title="Check your email">
        <p style={{ margin: 0 }}>
          We sent a confirmation link to <strong>{email}</strong>. Open it, or paste the code from it below.
        </p>
        <Link href="/verify" className="btn btn-primary btn-block">
          Enter the code
        </Link>
      </AuthCard>
    );
  }

  return (
    <AuthCard kicker="NEW FAN" title="Create account">
      <form onSubmit={submit} className={s.form} style={{ padding: 0 }}>
        {error && (
          <div className="alert" role="alert">
            {error}
          </div>
        )}
        <label className="field">
          Name
          <input className="input" autoComplete="name" required maxLength={60} value={name} onChange={(e) => setName(e.target.value)} />
          <span className="hint">Shown on your tickets.</span>
        </label>
        <label className="field">
          Email
          <input className="input" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">
          Password
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
          <span className="hint">At least 12 characters.</span>
        </label>
        <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
          {busy ? "Creating…" : "Create account"}
        </button>
        <p className={s.alt}>
          Already have one? <Link href="/login">Sign in</Link>
        </p>
      </form>
    </AuthCard>
  );
}
