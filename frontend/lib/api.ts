// Small fetch wrapper for the API (proxied at /api by app/api/[...path]/route.ts).

const TOKEN_KEY = "seatline.token";

export class ApiError extends Error {
  constructor(readonly status: number, readonly code: string) {
    super(code);
  }
}

export function getToken(): string | null {
  try {
    const raw = localStorage.getItem(TOKEN_KEY);
    if (!raw) return null;
    const { token, expiresAt } = JSON.parse(raw) as { token: string; expiresAt: string };
    if (Date.parse(expiresAt) <= Date.now()) {
      localStorage.removeItem(TOKEN_KEY);
      return null;
    }
    return token;
  } catch {
    return null;
  }
}

export function setToken(token: string | null, expiresAt?: string) {
  try {
    if (token) localStorage.setItem(TOKEN_KEY, JSON.stringify({ token, expiresAt }));
    else localStorage.removeItem(TOKEN_KEY);
  } catch {
    // private mode: the session just won't survive a reload
  }
  window.dispatchEvent(new Event("seatline-auth"));
}

type Options = { method?: string; body?: unknown; signal?: AbortSignal };

export async function api<T>(path: string, opts: Options = {}): Promise<T> {
  const headers: Record<string, string> = { accept: "application/json" };
  const token = getToken();
  if (token) headers.authorization = `Bearer ${token}`;
  if (opts.body !== undefined) headers["content-type"] = "application/json";

  let res: Response;
  try {
    res = await fetch(`/api${path}`, {
      method: opts.method ?? (opts.body === undefined ? "GET" : "POST"),
      headers,
      body: opts.body === undefined ? undefined : JSON.stringify(opts.body),
      signal: opts.signal,
      cache: "no-store",
    });
  } catch (e) {
    if ((e as Error).name === "AbortError") throw e;
    throw new ApiError(0, "offline");
  }

  if (res.status === 401 && token) {
    // expired or revoked (password change, role change): sign out so the UI asks again
    setToken(null);
  }
  if (!res.ok) {
    let code = `http_${res.status}`;
    try {
      code = ((await res.json()) as { error?: string }).error ?? code;
    } catch {
      // empty body
    }
    throw new ApiError(res.status, code);
  }
  if (res.status === 204) return undefined as T;
  const text = await res.text();
  return (text ? JSON.parse(text) : undefined) as T;
}

/** Human wording for the API's error codes. */
export function explain(e: unknown): string {
  const code = e instanceof ApiError ? e.code : "unknown";
  const words: Record<string, string> = {
    offline: "Can't reach Seatline. Check your connection and try again.",
    api_unreachable: "Seatline's servers aren't answering right now. Try again in a moment.",
    try_again: "Seatline is very busy right now. Try again in a second.",
    invalid_request: "Something in that form isn't right.",
    bad_credentials: "That email and password don't match.",
    unknown_user: "That email and password don't match.",
    email_not_verified: "Verify your email first. We sent you a link.",
    invalid_or_expired_token: "That link has expired or was already used.",
    code_required: "Enter the 6-digit code from your authenticator app.",
    bad_code: "That code didn't work. Codes change every 30 seconds.",
    too_many_attempts: "Too many tries. Wait a few minutes and try again.",
    email_taken: "There's already an account with that email.",
    seats_taken: "Someone got one of those seats first. Pick again.",
    no_seats_together: "There aren't that many free seats together any more. Try fewer, or pick on the map.",
    no_such_seat: "One of those seats isn't on sale.",
    seat_not_in_tier: "That ticket type isn't sold in that section.",
    duplicate_seat: "You picked the same seat twice.",
    over_limit: "That's more tickets than this event allows per fan.",
    student_email_required: "Student tickets need a school email address (.edu).",
    wait_in_queue: "This sale has a waiting room. Join the line first.",
    not_in_queue: "You're not in the line for this event.",
    cooldown: "Your last hold ran out. You can pick seats again in a couple of minutes.",
    hold_expired: "Your hold ran out and the seats went back on sale.",
    no_such_hold: "That hold has ended.",
    already_paid: "That order is already paid.",
    payment_declined: "Your card was declined. Nothing was charged.",
    not_on_sale: "This event isn't on sale yet.",
    invalid_promo_code: "That promo code isn't valid for this event.",
    code_exists: "That promo code already exists.",
    already_published: "That event is already on sale.",
    no_such_event: "We couldn't find that event.",
    no_such_ticket: "We couldn't find that ticket.",
    no_such_user: "There's no Seatline account with that email.",
    not_your_event: "That event belongs to another organizer.",
    forbidden: "Your account can't do that.",
    not_found: "We couldn't find that.",
    wrong_password: "Your current password isn't right.",
    already_enabled: "Two-step sign-in is already on.",
    no_setup_in_progress: "Start the setup again to get a fresh QR code.",
    too_late_to_return: "This ticket can't be returned any more: it's been scanned or the show has started.",
    only_the_buyer_can_return: "Only the person who bought this ticket can return it. You can send it on instead.",
    no_queue: "This event doesn't have a waiting room.",
    no_such_venue: "We couldn't find that venue.",
    no_tiers: "Add at least one ticket type.",
    no_such_section: "One of the ticket types names a section this venue doesn't have.",
    duplicate_section_name: "Two sections have the same name.",
    no_such_tier: "That ticket type isn't on sale for this event.",
    chaos_off: "Fault drills are switched off on this server.",
    no_such_node: "There's no ledger node with that number.",
    node_unreachable: "That ledger node isn't answering.",
    duplicate_row_label: "Two rows in one section have the same label.",
    on_sale_after_start: "The sale has to open before the event starts.",
    event_started: "This event has already started, so tickets aren't on sale any more.",
    not_on_door_team: "You're not on the door team for this event. Ask the organizer to add you.",
    cant_change_own_role: "You can't change your own role. Ask another admin.",
    already_cancelled: "This event is already cancelled.",
    event_cancelled: "This event has been cancelled. Anything paid toward it has been refunded in full.",
    not_door_staff: "That account isn't a door staff account. An admin can make it one.",
  };
  if (words[code]) return words[code];
  if (e instanceof ApiError && e.status === 403) return words.forbidden;
  if (e instanceof ApiError && e.status === 404) return words.not_found;
  return "Something went wrong. Try again.";
}
