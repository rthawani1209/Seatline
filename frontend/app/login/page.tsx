"use client";

import Link from "next/link";
import { useRouter, useSearchParams } from "next/navigation";
import { Suspense, useState } from "react";
import { AuthCard, authStyles as s } from "@/components/AuthCard";
import { api, ApiError, explain } from "@/lib/api";
import { useAuth } from "@/lib/auth";

export default function LoginPage() {
  return (
    <Suspense>
      <Login />
    </Suspense>
  );
}

/** Only same-site paths, so a crafted ?next= can't send people elsewhere after signing in. */
function safeNext(next: string | null): string {
  return next && next.startsWith("/") && !next.startsWith("//") && !next.startsWith("/\\") ? next : "/";
}

function Login() {
  const router = useRouter();
  const params = useSearchParams();
  const { signIn } = useAuth();
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [code, setCode] = useState("");
  const [needCode, setNeedCode] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      const r = await api<{ token: string; expiresAt: string }>("/auth/login", {
        body: { email, password, code: needCode ? code : undefined },
      });
      await signIn(r.token, r.expiresAt);
      router.replace(safeNext(params.get("next")));
    } catch (err) {
      if (err instanceof ApiError && err.code === "code_required") {
        setNeedCode(true);
      } else {
        setError(explain(err));
      }
    } finally {
      setBusy(false);
    }
  }

  return (
    <AuthCard kicker="ADMIT ONE" title="Sign in">
      <form onSubmit={submit} className={s.form} style={{ padding: 0 }}>
        {params.get("verified") && <div className="notice">Email verified. Sign in to get your tickets.</div>}
        {params.get("reset") && <div className="notice">Password changed. Sign in with the new one.</div>}
        {error && (
          <div className="alert" role="alert">
            {error}
          </div>
        )}
        <label className="field">
          Email
          <input className="input" type="email" autoComplete="email" required value={email} onChange={(e) => setEmail(e.target.value)} />
        </label>
        <label className="field">
          Password
          <input
            className="input"
            type="password"
            autoComplete="current-password"
            required
            value={password}
            onChange={(e) => setPassword(e.target.value)}
          />
        </label>
        {needCode && (
          <label className="field">
            Authenticator code
            <input
              className="input mono"
              inputMode="numeric"
              autoComplete="one-time-code"
              pattern="[0-9]{6}"
              maxLength={6}
              required
              autoFocus
              value={code}
              onChange={(e) => setCode(e.target.value.replace(/\D/g, ""))}
            />
            <span className="hint">Two-step sign-in is on for this account.</span>
          </label>
        )}
        <button type="submit" className="btn btn-primary btn-block" disabled={busy}>
          {busy ? "Signing in…" : "Sign in"}
        </button>
        <p className={s.alt}>
          <Link href="/forgot">Forgot password?</Link>
        </p>
        <p className={s.alt}>
          New here? <Link href="/register">Create an account</Link>
        </p>
      </form>
    </AuthCard>
  );
}
