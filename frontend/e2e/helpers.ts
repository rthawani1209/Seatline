import { expect, type APIRequestContext, type Page } from "@playwright/test";
import { Client } from "pg";

// Talks to the API the same way the app does (through /api), and reads the email outbox and
// sets roles straight in Postgres, like the backend tests do.

export const PASSWORD = "correct-horse-battery-staple";

const db = () =>
  new Client({ connectionString: process.env.E2E_DATABASE_URL ?? "postgres://seatline:seatline@localhost:5432/seatline" });

export async function sql<T = Record<string, unknown>>(text: string, params: unknown[] = []): Promise<T[]> {
  const c = db();
  await c.connect();
  try {
    return (await c.query(text, params)).rows as T[];
  } finally {
    await c.end();
  }
}

export function email(tag: string) {
  return `${tag}-${Date.now()}-${Math.floor(Math.random() * 1e6)}@e2e.test`;
}

export async function confirmationCode(address: string): Promise<string> {
  const rows = await sql<{ body: string }>("select body from outbox_emails where to_address = $1 order by id desc limit 1", [address]);
  const m = rows[0]?.body.match(/code: (\S+)/);
  if (!m) throw new Error(`no confirmation email for ${address}`);
  return m[1];
}

/** A verified account with the given role, made through the API. Returns its token. */
export async function account(request: APIRequestContext, role = "FAN", address = email(role.toLowerCase()), name = "E2E " + role) {
  const reg = await request.post("/api/auth/register", { data: { email: address, password: PASSWORD, displayName: name } });
  expect(reg.status(), await reg.text()).toBe(201);
  const ver = await request.post("/api/auth/verify", { data: { token: await confirmationCode(address) } });
  expect(ver.status()).toBe(204);
  if (role !== "FAN") await sql("update users set role = $1 where email = $2", [role, address]);
  const login = await request.post("/api/auth/login", { data: { email: address, password: PASSWORD } });
  expect(login.ok()).toBeTruthy();
  return { email: address, token: (await login.json()).token as string };
}

export async function signIn(page: Page, address: string) {
  await page.goto("/login");
  await page.getByLabel("Email").fill(address);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  // the phone header always shows "Menu", so wait for the redirect off the sign-in page instead
  await expect(page).not.toHaveURL(/\/login/);
}

const auth = (token: string) => ({ authorization: `Bearer ${token}` });

/** A published event with three sections and two ticket types, made by a fresh organizer. */
export async function onSaleEvent(request: APIRequestContext, opts: { queuePerMinute?: number; name?: string } = {}) {
  const org = await account(request, "ORGANIZER");
  const rows = (n: number, seats: number, accessible: number[] = []) =>
    Array.from({ length: n }, (_, i) => ({
      label: "ABCDEFGHIJ"[i],
      seats,
      accessible: i === n - 1 ? accessible : [],
      limitedView: [],
    }));
  const venue = await request.post("/api/venues", {
    headers: auth(org.token),
    data: {
      name: "E2E Hall",
      city: "Testville",
      sections: [
        { name: "Left", rows: rows(4, 6) },
        { name: "Main", rows: rows(6, 10, [5, 6]) },
        { name: "Right", rows: rows(4, 6) },
      ],
    },
  });
  expect(venue.status(), await venue.text()).toBe(201);
  const ev = await request.post("/api/events", {
    headers: auth(org.token),
    data: {
      venueId: (await venue.json()).id,
      name: opts.name ?? `E2E Show ${Date.now() % 100000}`,
      startsAt: new Date(Date.now() + 20 * 864e5).toISOString(),
      perFanLimit: 4,
      feeBps: 1000,
      feeFlatCents: 150,
      taxBps: 800,
      queuePerMinute: opts.queuePerMinute,
      tiers: [
        { name: "Main floor", kind: "STANDARD", priceCents: 4000, sections: ["Main"] },
        { name: "Side", kind: "STANDARD", priceCents: 2500, sections: ["Left", "Right"] },
      ],
    },
  });
  expect(ev.status(), await ev.text()).toBe(201);
  const id = (await ev.json()).id as string;
  expect((await request.post(`/api/events/${id}/publish`, { headers: auth(org.token) })).status()).toBe(204);
  const event = await (await request.get(`/api/events/${id}`)).json();
  return { id, organizer: org, event };
}

/** Buys seats through the API; returns the ticket ids. */
export async function buy(request: APIRequestContext, token: string, eventId: string, tierId: string, seats: string[]) {
  const hold = await request.post(`/api/events/${eventId}/holds`, {
    headers: auth(token),
    data: { items: seats.map((seat) => ({ seat, tierId })) },
  });
  expect(hold.status(), await hold.text()).toBe(201);
  const order = await request.post(`/api/holds/${(await hold.json()).holdId}/checkout`, {
    headers: auth(token),
    data: { cardToken: "tok_visa" },
  });
  expect(order.ok(), await order.text()).toBeTruthy();
  return ((await order.json()).tickets as { id: string }[]).map((t) => t.id);
}

export function tierId(event: { tiers: { id: string; name: string }[] }, name: string): string {
  const t = event.tiers.find((x) => x.name === name);
  if (!t) throw new Error(`no ticket type ${name}`);
  return t.id;
}

export { auth };
