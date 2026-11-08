"use client";

import { createContext, useCallback, useContext, useEffect, useState } from "react";
import { api, getToken, setToken } from "./api";
import type { Me } from "./types";

type Auth = {
  me: Me | null;
  ready: boolean;
  signIn: (token: string, expiresAt: string) => Promise<void>;
  signOut: () => void;
  /** Reload who we are, after something changes the account (say, turning on two-step sign-in). */
  refresh: () => Promise<void>;
};

const AuthContext = createContext<Auth>({ me: null, ready: false, signIn: async () => {}, signOut: () => {}, refresh: async () => {} });

export function AuthProvider({ children }: { children: React.ReactNode }) {
  const [me, setMe] = useState<Me | null>(null);
  const [ready, setReady] = useState(false);

  const load = useCallback(async () => {
    if (!getToken()) {
      setMe(null);
      setReady(true);
      return;
    }
    try {
      setMe(await api<Me>("/me"));
    } catch {
      setMe(null);
    } finally {
      setReady(true);
    }
  }, []);

  useEffect(() => {
    load();
    // another tab signed in or out, or a request found the token expired
    const onChange = () => load();
    window.addEventListener("seatline-auth", onChange);
    window.addEventListener("storage", onChange);
    return () => {
      window.removeEventListener("seatline-auth", onChange);
      window.removeEventListener("storage", onChange);
    };
  }, [load]);

  const signIn = useCallback(async (token: string, expiresAt: string) => {
    setToken(token, expiresAt);
    setMe(await api<Me>("/me"));
  }, []);

  const signOut = useCallback(() => setToken(null), []);

  return <AuthContext.Provider value={{ me, ready, signIn, signOut, refresh: load }}>{children}</AuthContext.Provider>;
}

export const useAuth = () => useContext(AuthContext);
