"use client";

import { useEffect, useState } from "react";
import { RoleGate } from "@/components/RoleGate";
import { api, explain } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import type { Role } from "@/lib/types";
import s from "./admin.module.css";

type UserRow = { id: string; email: string; displayName: string | null; role: Role; emailVerified: boolean };

const ROLES: { value: Role; label: string }[] = [
  { value: "FAN", label: "Fan" },
  { value: "ORGANIZER", label: "Organizer" },
  { value: "DOOR_STAFF", label: "Door staff" },
  { value: "ADMIN", label: "Admin" },
];

export default function AdminPage() {
  return (
    <RoleGate roles={["ADMIN"]}>
      <Admin />
    </RoleGate>
  );
}

function Admin() {
  const { me } = useAuth();
  const [query, setQuery] = useState("");
  const [users, setUsers] = useState<UserRow[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [flash, setFlash] = useState<string | null>(null);

  // search as you type, a moment after the last key
  useEffect(() => {
    const q = query.trim();
    if (q.length < 2) {
      setUsers(null);
      return;
    }
    const ctrl = new AbortController();
    const t = setTimeout(() => {
      api<UserRow[]>(`/admin/users?email=${encodeURIComponent(q)}`, { signal: ctrl.signal })
        .then((r) => {
          setUsers(r);
          setError(null);
        })
        .catch((e) => {
          if ((e as Error).name !== "AbortError") setError(explain(e));
        });
    }, 250);
    return () => {
      clearTimeout(t);
      ctrl.abort();
    };
  }, [query]);

  async function setRole(u: UserRow, role: Role) {
    setError(null);
    setFlash(null);
    try {
      await api(`/admin/users/${u.id}/role`, { method: "PUT", body: { role } });
      setUsers((list) => list?.map((x) => (x.id === u.id ? { ...x, role } : x)) ?? null);
      setFlash(`${u.email} is now ${ROLES.find((r) => r.value === role)?.label.toLowerCase()}. They'll need to sign in again.`);
    } catch (e) {
      setError(explain(e));
    }
  }

  return (
    <div className={s.shell}>
      <div>
        <div className="eyebrow">Admin</div>
        <h1 className="display" style={{ margin: 0 }}>
          Accounts
        </h1>
        <p className="hint">
          Find an account by email and change what it can do. Door staff also need adding to an event&apos;s door team by its
          organizer.
        </p>
      </div>

      <label className="field">
        Search by email
        <input className="input" type="search" autoComplete="off" value={query} onChange={(e) => setQuery(e.target.value)} placeholder="name@example.com" />
      </label>

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

      {users && users.length === 0 && <p className="hint">No accounts match.</p>}
      {users && users.length > 0 && (
        <ul className={`panel ${s.list}`} aria-label="Accounts">
          {users.map((u) => (
            <li key={u.id} className={s.row}>
              <div className={s.who}>
                <strong>{u.displayName || u.email}</strong>
                <span className="hint">
                  {u.email}
                  {!u.emailVerified && " · not verified"}
                </span>
              </div>
              <label className={s.role}>
                <span className="sr-only">Role for {u.email}</span>
                <select className="input" value={u.role} disabled={u.id === me?.id} onChange={(e) => setRole(u, e.target.value as Role)}>
                  {ROLES.map((r) => (
                    <option key={r.value} value={r.value}>
                      {r.label}
                    </option>
                  ))}
                </select>
              </label>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
