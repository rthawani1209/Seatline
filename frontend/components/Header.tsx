"use client";

import Link from "next/link";
import { usePathname } from "next/navigation";
import { useEffect, useState } from "react";
import { useAuth } from "@/lib/auth";
import { ThemeToggle } from "./ThemeToggle";
import s from "./Header.module.css";

export function Header() {
  const { me, ready, signOut } = useAuth();
  const path = usePathname();
  const [open, setOpen] = useState(false);

  useEffect(() => setOpen(false), [path]);

  const links: { href: string; label: string }[] = [{ href: "/", label: "Events" }];
  if (me) links.push({ href: "/tickets", label: "My tickets" });
  if (me && (me.role === "ORGANIZER" || me.role === "ADMIN")) links.push({ href: "/organizer", label: "Organizer" });
  if (me && (me.role === "DOOR_STAFF" || me.role === "ADMIN")) links.push({ href: "/door", label: "Door" });
  if (me?.role === "ADMIN") links.push({ href: "/admin", label: "Admin" });
  if (me) links.push({ href: "/account", label: "Account" });

  const current = (href: string) => (href === "/" ? path === "/" : path.startsWith(href));

  return (
    <header className={s.bar}>
      <div className={`wrap ${s.inner}`}>
        <Link href="/" className={`display ${s.logo}`} aria-label="Seatline home">
          Seatline
        </Link>

        <button
          type="button"
          className={s.menuBtn}
          aria-expanded={open}
          aria-controls="site-nav"
          onClick={() => setOpen((o) => !o)}
        >
          <span className="sr-only">Menu</span>
          <svg width="22" height="22" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" aria-hidden="true">
            {open ? <path d="M6 6l12 12M18 6L6 18" /> : <path d="M4 7h16M4 12h16M4 17h16" />}
          </svg>
        </button>

        <nav id="site-nav" className={`${s.nav} ${open ? s.open : ""}`} aria-label="Main">
          {links.map((l) => (
            <Link key={l.href} href={l.href} className={s.link} aria-current={current(l.href) ? "page" : undefined}>
              {l.label}
            </Link>
          ))}
          <div className={s.right}>
            <ThemeToggle />
            {ready && !me && (
              <>
                <Link href={`/login?next=${encodeURIComponent(path)}`} className="btn btn-sm">
                  Sign in
                </Link>
                <Link href="/register" className="btn btn-sm btn-primary">
                  Create account
                </Link>
              </>
            )}
            {me && (
              <>
                <span className={s.who} title={me.email}>
                  {me.displayName || me.email}
                </span>
                <button type="button" className="btn btn-sm" onClick={signOut}>
                  Sign out
                </button>
              </>
            )}
          </div>
        </nav>
      </div>
    </header>
  );
}
