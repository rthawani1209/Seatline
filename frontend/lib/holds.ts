import type { Hold } from "./types";

// The hold the API just made, kept for the checkout page in this tab.
export type SavedHold = Hold & { eventId: string };

const key = (holdId: string) => `seatline.hold.${holdId}`;

export function saveHold(eventId: string, hold: Hold) {
  try {
    sessionStorage.setItem(key(hold.holdId), JSON.stringify({ ...hold, eventId }));
  } catch {
    // checkout falls back to asking the API
  }
}

export function loadHold(holdId: string): SavedHold | null {
  try {
    const raw = sessionStorage.getItem(key(holdId));
    return raw ? (JSON.parse(raw) as SavedHold) : null;
  } catch {
    return null;
  }
}

export function forgetHold(holdId: string) {
  try {
    sessionStorage.removeItem(key(holdId));
  } catch {
    // nothing to clean up
  }
}
