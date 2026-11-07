"use client";

import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect } from "react";
import { useAuth } from "@/lib/auth";
import type { Role } from "@/lib/types";

/** Shows children only to the given roles; sends signed-out visitors to sign in. */
export function RoleGate({ roles, children }: { roles: Role[]; children: React.ReactNode }) {
  const { me, ready } = useAuth();
  const router = useRouter();

  useEffect(() => {
    if (ready && !me) router.replace(`/login?next=${encodeURIComponent(location.pathname)}`);
  }, [ready, me, router]);

  if (!ready || !me) {
    return (
      <div className="wrap" style={{ padding: "60px 20px" }} aria-busy="true">
        <p className="hint">Checking your account…</p>
      </div>
    );
  }
  if (!roles.includes(me.role)) {
    return (
      <div className="wrap" style={{ padding: "60px 20px", display: "flex", flexDirection: "column", gap: 12, alignItems: "flex-start" }}>
        <h1 className="display" style={{ fontSize: 44, margin: 0 }}>
          Not for this account
        </h1>
        <p style={{ margin: 0 }}>This page is for {roles.map((r) => r.toLowerCase().replace("_", " ")).join(" or ")} accounts.</p>
        <Link href="/" className="btn">
          Back to events
        </Link>
      </div>
    );
  }
  return <>{children}</>;
}
