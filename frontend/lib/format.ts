export function money(cents: number | null | undefined): string {
  if (cents == null) return "";
  return new Intl.NumberFormat("en-US", { style: "currency", currency: "USD" }).format(cents / 100);
}

export function when(iso: string, opts: Intl.DateTimeFormatOptions = {}): string {
  return new Intl.DateTimeFormat("en-US", {
    weekday: "short",
    month: "short",
    day: "numeric",
    hour: "numeric",
    minute: "2-digit",
    ...opts,
  }).format(new Date(iso));
}

/** 4:38 style clock for countdowns. */
export function clock(seconds: number): string {
  const s = Math.max(0, Math.floor(seconds));
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = String(s % 60).padStart(2, "0");
  return h > 0 ? `${h}:${String(m).padStart(2, "0")}:${ss}` : `${m}:${ss}`;
}

/** "MAIN-F-7" (the ledger's seat label) as section, row and number. */
export function seatParts(label: string): { section: string; row: string; number: string } {
  const parts = label.split("-");
  const number = parts.pop() ?? "";
  const row = parts.pop() ?? "";
  return { section: parts.join("-").replace(/_/g, " "), row, number };
}

export function seatName(label: string): string {
  const p = seatParts(label);
  return `${p.section} · Row ${p.row} · Seat ${p.number}`;
}

export const tierWord: Record<string, string> = {
  STANDARD: "Standard",
  EARLY_BIRD: "Early bird",
  STUDENT: "Student",
  VIP: "VIP",
};
