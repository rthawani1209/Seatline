"use client";

import QRCode from "qrcode";
import { useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import { api, explain, setToken } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import s from "./account.module.css";

const ROLE_NAMES: Record<string, string> = {
  FAN: "Fan",
  ORGANIZER: "Organizer",
  DOOR_STAFF: "Door staff",
  ADMIN: "Admin",
};

export default function AccountPage() {
  const router = useRouter();
  const { me, ready } = useAuth();

  useEffect(() => {
    if (ready && !me) router.replace("/login?next=/account");
  }, [ready, me, router]);

  if (!me) {
    return (
      <div className={s.shell}>
        <p className="hint">Loading your account…</p>
      </div>
    );
  }

  return (
    <div className={s.shell}>
      <div>
        <div className="eyebrow">Account</div>
        <h1 className="display">{me.displayName || me.email}</h1>
      </div>

      <section className={`panel ${s.section}`} aria-labelledby="profile-h">
        <h2 id="profile-h" className="display">
          Profile
        </h2>
        <dl className={s.facts}>
          <dt>Email</dt>
          <dd>{me.email}</dd>
          <dt>Name</dt>
          <dd>{me.displayName || "Not set"}</dd>
          <dt>Role</dt>
          <dd>{ROLE_NAMES[me.role] ?? me.role}</dd>
        </dl>
      </section>

      <TwoStep enabled={me.twoFactor} />
      <ChangePassword />
      <Sessions />
    </div>
  );
}

/** Two-step sign-in: start setup (QR + secret), confirm with a code, or turn it off with a code. */
function TwoStep({ enabled }: { enabled: boolean }) {
  const { refresh } = useAuth();
  const [setup, setSetup] = useState<{ secret: string; svg: string } | null>(null);
  const [disabling, setDisabling] = useState(false);
  const [code, setCode] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);

  async function start() {
    setBusy(true);
    setError(null);
    setFlash(null);
    try {
      const r = await api<{ secret: string; uri: string }>("/me/2fa/setup", { method: "POST" });
      const svg = await QRCode.toString(r.uri, { type: "svg", margin: 0, errorCorrectionLevel: "M" });
      setSetup({ secret: r.secret, svg });
      setCode("");
    } catch (e) {
      setError(explain(e));
    } finally {
      setBusy(false);
    }
  }

  async function confirm(e: React.FormEvent) {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (enabled) {
        // turning it off signs out other devices; this tab gets a new token
        const r = await api<{ token: string; expiresAt: string }>("/me/2fa/disable", { body: { code } });
        setToken(r.token, r.expiresAt);
      } else {
        await api("/me/2fa/enable", { body: { code, password } });
      }
      setFlash(enabled ? "Two-step sign-in is off. You've been signed out on your other devices." : "Two-step sign-in is on. You'll need a code next time you sign in.");
      setSetup(null);
      setDisabling(false);
      setCode("");
      setPassword("");
      await refresh();
    } catch (err) {
      setError(explain(err));
    } finally {
      setBusy(false);
    }
  }

  function cancel() {
    setSetup(null);
    setDisabling(false);
    setCode("");
    setPassword("");
    setError(null);
  }

  const asking = setup !== null || disabling;

  return (
    <section className={`panel ${s.section}`} aria-labelledby="twostep-h">
      <div className={s.row}>
        <h2 id="twostep-h" className="display">
          Two-step sign-in
        </h2>
        <span className={`mono ${s.status} ${enabled ? s.on : ""}`}>{enabled ? "ON" : "OFF"}</span>
      </div>
      <p className="hint" style={{ margin: 0 }}>
        Asks for a 6-digit code from an authenticator app (Google Authenticator, 1Password, Authy) when you sign in.
      </p>
      {flash && (
        <div className="notice" role="status">
          {flash}
        </div>
      )}
      {error && (
        <div className="alert" role="alert">
          {error}
        </div>
      )}

      {setup && (
        <div className={s.setup}>
          <div className={s.qr} role="img" aria-label="QR code for your authenticator app" dangerouslySetInnerHTML={{ __html: setup.svg }} />
          <div style={{ display: "flex", flexDirection: "column", gap: 8 }}>
            <p style={{ margin: 0 }}>Scan this with your authenticator app. Can't scan it? Type this key in instead:</p>
            <code className={`mono ${s.secret}`} aria-label="Setup key">
              {setup.secret}
            </code>
          </div>
        </div>
      )}

      {asking ? (
        <form onSubmit={confirm} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
          <label className="field">
            {enabled ? "Code to turn it off" : "Code from the app"}
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
          </label>
          {!enabled && (
            <label className="field">
              Your password
              <input
                className="input"
                type="password"
                autoComplete="current-password"
                required
                value={password}
                onChange={(e) => setPassword(e.target.value)}
              />
            </label>
          )}
          <div className={s.actions}>
            <button type="submit" className="btn btn-primary" disabled={busy || code.length !== 6 || (!enabled && !password)}>
              {busy ? "Checking…" : enabled ? "Turn off" : "Turn on"}
            </button>
            <button type="button" className="btn" onClick={cancel} disabled={busy}>
              Cancel
            </button>
          </div>
        </form>
      ) : (
        <div className={s.actions}>
          {enabled ? (
            <button type="button" className="btn" onClick={() => (setDisabling(true), setFlash(null))}>
              Turn off two-step sign-in
            </button>
          ) : (
            <button type="button" className="btn btn-primary" onClick={start} disabled={busy}>
              {busy ? "Starting…" : "Set up two-step sign-in"}
            </button>
          )}
        </div>
      )}
    </section>
  );
}

function ChangePassword() {
  const [current, setCurrent] = useState("");
  const [next, setNext] = useState("");
  const [again, setAgain] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);

  async function submit(e: React.FormEvent) {
    e.preventDefault();
    setError(null);
    setFlash(null);
    if (next !== again) {
      setError("The new passwords don't match.");
      return;
    }
    setBusy(true);
    try {
      const r = await api<{ token: string; expiresAt: string }>("/me/password", {
        body: { currentPassword: current, newPassword: next },
      });
      // changing the password ends every other session; this one carries on with a new token
      setToken(r.token, r.expiresAt);
      setCurrent("");
      setNext("");
      setAgain("");
      setFlash("Password changed. You've been signed out on your other devices.");
    } catch (err) {
      setError(explain(err));
    } finally {
      setBusy(false);
    }
  }

  return (
    <section className={`panel ${s.section}`} aria-labelledby="password-h">
      <h2 id="password-h" className="display">
        Password
      </h2>
      {flash && (
        <div className="notice" role="status">
          {flash}
        </div>
      )}
      {error && (
        <div className="alert" role="alert">
          {error}
        </div>
      )}
      <form onSubmit={submit} style={{ display: "flex", flexDirection: "column", gap: 12 }}>
        <label className="field">
          Current password
          <input className="input" type="password" autoComplete="current-password" required value={current} onChange={(e) => setCurrent(e.target.value)} />
        </label>
        <label className="field">
          New password
          <input
            className="input"
            type="password"
            autoComplete="new-password"
            required
            minLength={12}
            maxLength={128}
            value={next}
            onChange={(e) => setNext(e.target.value)}
          />
          <span className="hint">At least 12 characters.</span>
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
        <div className={s.actions}>
          <button type="submit" className="btn btn-primary" disabled={busy}>
            {busy ? "Changing…" : "Change password"}
          </button>
        </div>
      </form>
    </section>
  );
}

function Sessions() {
  const router = useRouter();
  const { signOut } = useAuth();
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  async function everywhere() {
    setBusy(true);
    setError(null);
    try {
      await api("/me/sign-out-everywhere", { method: "POST" });
      signOut();
      router.replace("/login");
    } catch (e) {
      setError(explain(e));
      setBusy(false);
    }
  }

  return (
    <section className={`panel ${s.section}`} aria-labelledby="sessions-h">
      <h2 id="sessions-h" className="display">
        Signed-in devices
      </h2>
      <p className="hint" style={{ margin: 0 }}>
        Lost a phone or signed in on a shared computer? Signing out everywhere ends every session, this one included.
      </p>
      {error && (
        <div className="alert" role="alert">
          {error}
        </div>
      )}
      <div className={s.actions}>
        <button
          type="button"
          className="btn"
          onClick={() => {
            signOut();
            router.replace("/");
          }}
        >
          Sign out
        </button>
        <button type="button" className="btn" onClick={everywhere} disabled={busy}>
          {busy ? "Signing out…" : "Sign out everywhere"}
        </button>
      </div>
    </section>
  );
}
