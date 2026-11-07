import { expect, test } from "@playwright/test";
import { account, auth, buy, onSaleEvent, signIn, tierId } from "./helpers";

test("send a ticket to a friend: it moves to their account and the old code stops working", async ({ page, request }) => {
  const { id, event } = await onSaleEvent(request);
  const fan = await account(request);
  const friend = await account(request);
  const main = tierId(event, "Main floor");
  const [ticket] = await buy(request, fan.token, id, main, ["MAIN-D-1"]);
  const oldCode = (await (await request.get(`/api/me/tickets/${ticket}/qr`, { headers: auth(fan.token) })).json()).code;

  await signIn(page, fan.email);
  await page.goto("/tickets");
  await expect(page.getByText("ROW", { exact: true })).toBeVisible();
  await page.getByRole("button", { name: "Send to a friend" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByLabel("Their email").fill("nobody-" + Date.now() + "@e2e.test");
  await dialog.getByRole("button", { name: "Send ticket" }).click();
  await expect(dialog.locator(".alert")).toHaveText("There's no Seatline account with that email.");

  await dialog.getByLabel("Their email").fill(friend.email);
  await dialog.getByRole("button", { name: "Send ticket" }).click();
  await expect(page.getByRole("status")).toContainText(`Sent MAIN row D seat 1 to ${friend.email}`);
  await expect(page.getByText("No tickets yet")).toBeVisible();

  const theirs = await (await request.get("/api/me/tickets", { headers: auth(friend.token) })).json();
  expect(theirs.map((t: { seat: string }) => t.seat)).toEqual(["MAIN-D-1"]);

  const door = await account(request, "DOOR_STAFF");
  const scan = await request.post("/api/scan", { headers: auth(door.token), data: { code: oldCode, eventId: id } });
  expect((await scan.json()).result).toBe("OLD_CODE");
});

test("return a ticket at face value: the seat goes back on sale", async ({ page, request }) => {
  const { id, event } = await onSaleEvent(request);
  const fan = await account(request);
  const side = tierId(event, "Side");
  await buy(request, fan.token, id, side, ["RIGHT-A-2"]);

  await signIn(page, fan.email);
  await page.goto("/tickets");
  await page.getByRole("button", { name: "Return at face value" }).click();
  const dialog = page.getByRole("dialog");
  await dialog.getByRole("button", { name: "Keep it" }).click();
  await expect(dialog).toBeHidden();

  await page.getByRole("button", { name: "Return at face value" }).click();
  await dialog.getByRole("button", { name: "Return ticket" }).click();
  await expect(page.getByRole("status")).toContainText("Returned RIGHT row A seat 2");
  const seats = await (await request.get(`/api/events/${id}/seats`)).json();
  expect(seats.find((x: { seat: string }) => x.seat === "RIGHT-A-2").status).toBe("FREE");
});

test("the ticket code changes every 15 seconds", async ({ page, request }) => {
  test.setTimeout(60_000);
  const { id, event } = await onSaleEvent(request);
  const fan = await account(request);
  const side = tierId(event, "Side");
  await buy(request, fan.token, id, side, ["LEFT-B-1"]);
  await signIn(page, fan.email);

  const codes: string[] = [];
  page.on("response", async (r) => {
    if (r.url().includes("/qr") && r.ok()) codes.push((await r.json()).code);
  });
  await page.goto("/tickets");
  await expect(page.locator("[class*=qrSvg] svg")).toBeVisible();
  await expect.poll(() => new Set(codes).size, { timeout: 20_000 }).toBeGreaterThanOrEqual(2);
});
