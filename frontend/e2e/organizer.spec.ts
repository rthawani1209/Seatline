import { expect, test } from "@playwright/test";
import { account, auth, buy, onSaleEvent, signIn, tierId } from "./helpers";

test("an organizer builds a venue and event, publishes it, and fans can see it", async ({ page, request }) => {
  const org = await account(request, "ORGANIZER");
  await signIn(page, org.email);
  await page.getByRole("link", { name: "Organizer" }).click();
  await page.getByRole("link", { name: /New event|Create your first event/ }).first().click();

  const name = `Rooftop ${Date.now() % 100000}`;
  await page.getByLabel("Venue name").fill("The Roof");
  await page.getByLabel("City").fill("Oakland");
  await page.getByLabel("Section", { exact: true }).first().fill("Deck");
  await page.getByLabel("Rows").first().fill("3");
  await page.getByLabel("Seats a row").first().fill("5");
  await page.getByRole("button", { name: "+ Add a section" }).click();
  await page.getByLabel("Section", { exact: true }).nth(1).fill("Bar");
  await expect(page.getByText("95 seats in 2 sections")).toBeVisible(); // 3x5 + a new section of 8x10

  await page.getByLabel("Event name").fill(name);
  await page.getByLabel("Waiting room").check();
  await page.getByLabel("Fans let in per minute").fill("120");
  await page.getByLabel("Name", { exact: true }).fill("Deck GA");
  await page.getByLabel("Price $").fill("22.50");
  // the default ticket type was for "Floor", which this venue doesn't have
  await page.getByRole("button", { name: "Save as draft" }).click();
  await expect(page.locator(".alert")).toHaveText("Every ticket type needs at least one section to sell.");
  await page.getByRole("group", { name: "Sections sold" }).getByText("Deck").click();

  await page.getByRole("button", { name: "+ Add a ticket type" }).click();
  await page.getByLabel("Name", { exact: true }).nth(1).fill("Bar stools");
  await page.getByLabel("Kind").nth(1).selectOption("VIP");
  await page.getByLabel("Price $").nth(1).fill("60");
  await page.getByRole("group", { name: "Sections sold" }).nth(1).getByText("Bar").click();
  await page.getByRole("button", { name: "Save as draft" }).click();

  await expect(page.getByRole("heading", { name })).toBeVisible();
  await expect(page.getByText("Draft")).toBeVisible();
  // drafts aren't public
  expect((await (await request.get("/api/events")).json()).some((e: { name: string }) => e.name === name)).toBe(false);

  await page.getByRole("button", { name: "Publish and go on sale" }).click();
  await expect(page.getByText("On sale", { exact: false }).first()).toBeVisible();
  await expect(page.getByText("of 95")).toBeVisible();

  await page.getByLabel("Code", { exact: true }).fill("roof20");
  await page.getByLabel("% off").fill("20");
  await page.getByLabel("Uses").fill("50");
  await page.getByRole("button", { name: "Add code" }).click();
  await expect(page.getByText("ROOF20: 20% off, 0 of 50 used")).toBeVisible();
  await page.getByLabel("Code", { exact: true }).fill("roof20");
  await page.getByRole("button", { name: "Add code" }).click();
  await expect(page.locator(".alert")).toHaveText("That promo code already exists.");

  await page.goto("/");
  await expect(page.getByTestId("event-card").filter({ hasText: name })).toContainText("Waiting room");
});

test("live numbers update as fans buy", async ({ page, request }) => {
  const org = await account(request, "ORGANIZER");
  const venue = await (
    await request.post("/api/venues", {
      headers: auth(org.token),
      data: { name: "Small Room", city: "Davis", sections: [{ name: "Floor", rows: [{ label: "A", seats: 8 }] }] },
    })
  ).json();
  const ev = await (
    await request.post("/api/events", {
      headers: auth(org.token),
      data: {
        venueId: venue.id, name: "Numbers Night", startsAt: new Date(Date.now() + 9 * 864e5).toISOString(), perFanLimit: 4,
        feeBps: 0, feeFlatCents: 0, taxBps: 0, tiers: [{ name: "GA", kind: "STANDARD", priceCents: 1000, sections: ["Floor"] }],
      },
    })
  ).json();
  await request.post(`/api/events/${ev.id}/publish`, { headers: auth(org.token) });
  const tier = (await (await request.get(`/api/events/${ev.id}`)).json()).tiers[0].id;

  await signIn(page, org.email);
  await page.goto(`/organizer/events/${ev.id}`);
  await expect(page.getByText("of 8")).toBeVisible();
  await expect(page.getByTestId("double-sold")).toHaveText("0");

  const fan = await account(request);
  await buy(request, fan.token, ev.id, tier, ["FLOOR-A-1", "FLOOR-A-2", "FLOOR-A-3"]);
  await expect(page.locator("[class*=tileBig]")).toContainText("3", { timeout: 6000 });
  await expect(page.getByText("$30.00")).toBeVisible();
  await expect(page.getByTestId("double-sold")).toHaveText("0");

  // another organizer can't see it
  const other = await account(request, "ORGANIZER");
  expect((await request.get(`/api/events/${ev.id}/stats`, { headers: auth(other.token) })).status()).toBe(403);
});

test("fans can't open the organizer pages", async ({ page, request }) => {
  const fan = await account(request);
  await signIn(page, fan.email);
  await expect(page.getByRole("link", { name: "Organizer" })).toHaveCount(0);
  await page.goto("/organizer");
  await expect(page.getByRole("heading", { name: "Not for this account" })).toBeVisible();
  await page.goto("/door");
  await expect(page.getByRole("heading", { name: "Not for this account" })).toBeVisible();
});

test("promo codes stay listed after a reload, and the door team decides who can scan", async ({ page, request }) => {
  const ev = await onSaleEvent(request);
  const door = await account(request, "DOOR_STAFF");
  const fan = await account(request);
  await signIn(page, ev.organizer.email);
  await page.goto(`/organizer/events/${ev.id}`);

  await page.getByLabel("Code", { exact: true }).fill("early20");
  await page.getByLabel("% off").fill("20");
  await page.getByLabel("Uses").fill("50");
  await page.getByRole("button", { name: "Add code" }).click();
  await expect(page.getByText("EARLY20: 20% off, 0 of 50 used")).toBeVisible();

  // a fan account can't join the door team
  await page.getByLabel("Door staff email").fill(fan.email);
  await page.getByRole("button", { name: "Add to door team" }).click();
  await expect(page.locator(".alert")).toContainText("isn't a door staff account");
  await page.getByLabel("Door staff email").fill(door.email);
  await page.getByRole("button", { name: "Add to door team" }).click();
  await expect(page.getByRole("list", { name: "Door team" })).toContainText(door.email);

  await page.reload();
  await expect(page.getByText("EARLY20: 20% off, 0 of 50 used")).toBeVisible();
  await expect(page.getByRole("list", { name: "Door team" })).toContainText(door.email);

  const mine = await (await request.get("/api/door/events", { headers: auth(door.token) })).json();
  expect(mine.map((e: { id: string }) => e.id)).toEqual([ev.id]);

  await page.getByRole("button", { name: `Remove ${door.email} from the door team` }).click();
  await expect(page.getByRole("list", { name: "Door team" })).toHaveCount(0);
  expect(await (await request.get("/api/door/events", { headers: auth(door.token) })).json()).toEqual([]);
});

test("door staff on no door team are told who to ask", async ({ page, request }) => {
  const door = await account(request, "DOOR_STAFF");
  await signIn(page, door.email);
  await page.goto("/door");
  await expect(page.getByRole("status")).toContainText("not on the door team for any event");
  await expect(page.getByLabel("Scanning for")).toHaveCount(0);
});

test("an organizer cancels an event: fans are refunded and the page says so", async ({ page, request }) => {
  const ev = await onSaleEvent(request);
  const fan = await account(request);
  await buy(request, fan.token, ev.id, tierId(ev.event, "Main floor"), ["MAIN-A-1"]);

  await signIn(page, ev.organizer.email);
  await page.goto(`/organizer/events/${ev.id}`);
  await page.getByRole("button", { name: "Cancel event" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Keep it on sale" }).click();
  await expect(dialog).toBeHidden();
  await page.getByRole("button", { name: "Cancel event" }).click();
  await dialog.getByRole("button", { name: "Cancel event and refund everyone" }).click();
  await expect(page.getByText("This event is cancelled.")).toBeVisible();
  await expect(page.getByRole("button", { name: "Cancel event" })).toHaveCount(0);

  expect(await (await request.get("/api/me/tickets", { headers: auth(fan.token) })).json()).toEqual([]);
  await page.goto(`/events/${ev.id}`);
  await expect(page.getByText("This event has been cancelled.")).toBeVisible();
});
