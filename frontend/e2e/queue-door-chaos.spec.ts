import AxeBuilder from "@axe-core/playwright";
import { expect, test } from "@playwright/test";
import { account, auth, buy, onSaleEvent, signIn, tierId } from "./helpers";

test("a big sale goes through the waiting room before seats can be held", async ({ page, request }) => {
  const { id } = await onSaleEvent(request, { queuePerMinute: 600 });
  const fan = await account(request);
  await signIn(page, fan.email);
  await page.goto(`/events/${id}`);

  // straight to the API without queueing: refused
  const skip = await request.post(`/api/events/${id}/holds`, {
    headers: auth(fan.token),
    data: { items: [{ seat: "MAIN-A-1", tierId: tierId(await (await request.get(`/api/events/${id}`)).json(), "Main floor") }] },
  });
  expect((await skip.json()).error).toBe("wait_in_queue");

  await page.getByRole("button", { name: "Join the waiting room" }).click();
  await expect(page).toHaveURL(new RegExp(`/events/${id}/queue$`));
  // 600 a minute and nobody else in line, so the turn comes almost at once
  await expect(page.getByText("It's your turn.")).toBeVisible({ timeout: 15_000 });
  await expect(page.getByText(/pass expires in \d:\d\d/)).toBeVisible();
  await page.getByRole("link", { name: "Pick your seats" }).click();
  await expect(page.getByText("Pass expires")).toBeVisible();
  await page.getByText("Main floor").click();
  await page.locator('[data-seat="MAIN-A-1"]').click();
  await page.getByRole("button", { name: /Hold 1/ }).click();
  await expect(page).toHaveURL(/\/checkout\//);
});

test("door staff let a ticket in once, and turn away junk", async ({ page, request }) => {
  const { id, event } = await onSaleEvent(request);
  const fan = await account(request);
  const side = event.tiers.find((t: { name: string }) => t.name === "Side").id;
  const [ticket] = await buy(request, fan.token, id, side, ["LEFT-C-3"]);
  const code = (await (await request.get(`/api/me/tickets/${ticket}/qr`, { headers: auth(fan.token) })).json()).code;

  const door = await account(request, "DOOR_STAFF");
  await signIn(page, door.email);
  await page.getByRole("link", { name: "Door" }).click();
  await page.getByLabel("Scanning for").selectOption(id);
  await page.getByLabel("Or type the code").fill(code);
  await page.getByRole("button", { name: "Check" }).click();
  await expect(page.getByTestId("scan-result")).toHaveAttribute("data-result", "OK");
  await expect(page.getByText("LEFT · C3")).toBeVisible();
  await expect(page.getByText("1 let in")).toBeVisible();
  await page.getByRole("button", { name: "Next", exact: true }).click();

  await page.getByRole("button", { name: "Check" }).click();
  await expect(page.getByTestId("scan-result")).toHaveAttribute("data-result", "ALREADY_SCANNED");
  await page.getByRole("button", { name: "Next", exact: true }).click();

  await page.getByLabel("Or type the code").fill("SL1.not-a-ticket");
  await page.getByRole("button", { name: "Check" }).click();
  await expect(page.getByTestId("scan-result")).toHaveAttribute("data-result", "INVALID");
});

test("a ticket for another show is turned away at this door", async ({ page, request }) => {
  const a = await onSaleEvent(request);
  const b = await onSaleEvent(request);
  const fan = await account(request);
  const [ticket] = await buy(request, fan.token, a.id, tierId(a.event, "Side"), ["RIGHT-D-6"]);
  const code = (await (await request.get(`/api/me/tickets/${ticket}/qr`, { headers: auth(fan.token) })).json()).code;
  const door = await account(request, "DOOR_STAFF");
  await signIn(page, door.email);
  await page.goto("/door");
  await page.getByLabel("Scanning for").selectOption(b.id);
  await page.getByLabel("Or type the code").fill(code);
  await page.getByRole("button", { name: "Check" }).click();
  await expect(page.getByTestId("scan-result")).toHaveAttribute("data-result", "WRONG_EVENT");
  await expect(page.getByText(`It's for ${a.event.name}.`)).toBeVisible();
});

test("an admin kills the ledger's leader, a new one is elected, and sales carry on", async ({ page, request }) => {
  const { id, event } = await onSaleEvent(request);
  const admin = await account(request, "ADMIN");
  await signIn(page, admin.email);
  await page.goto(`/organizer/events/${id}`);
  const leader = page.locator('[data-state="leader"]');
  await expect(leader).toHaveCount(1, { timeout: 15_000 });
  const before = await leader.getAttribute("data-testid");

  await leader.getByRole("button", { name: "Kill" }).click();
  await expect(page.getByText(/You killed N\d/)).toBeVisible();
  await expect(page.locator(`[data-testid="${before}"]`)).toHaveAttribute("data-state", "down");
  await expect(page.locator('[data-state="leader"]')).toHaveCount(1, { timeout: 15_000 });
  await expect(page.getByText(/won the vote and leads term/)).toBeVisible();

  // four of five up: a fan can still buy
  const fan = await account(request);
  await buy(request, fan.token, id, tierId(event, "Main floor"), ["MAIN-E-9"]);

  await page.locator(`[data-testid="${before}"]`).getByRole("button", { name: "Revive" }).click();
  await expect(page.locator(`[data-testid="${before}"]`)).not.toHaveAttribute("data-state", "down", { timeout: 15_000 });
  await expect(page.locator(`[data-testid="${before}"]`)).toContainText("Up to date", { timeout: 15_000 });
  await expect(page.getByTestId("double-sold")).toHaveText("0");
});

test("theme choice sticks across pages and reloads", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("button", { name: "Switch to light theme" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await page.reload();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "light");
  await page.getByRole("button", { name: "Switch to dark theme" }).click();
  await expect(page.locator("html")).toHaveAttribute("data-theme", "dark");
});

for (const theme of ["dark", "light"]) {
  test(`main pages have no accessibility violations (${theme})`, async ({ page, request }) => {
    const { id, event } = await onSaleEvent(request);
    const fan = await account(request);
    const [ticket] = await buy(request, fan.token, id, tierId(event, "Main floor"), ["MAIN-A-3"]);
    void ticket;
    await page.addInitScript((t) => localStorage.setItem("seatline.theme", t), theme);
    const check = async (path: string, ready: string) => {
      await page.goto(path);
      await page.waitForSelector(ready);
      const result = await new AxeBuilder({ page }).withTags(["wcag2a", "wcag2aa", "wcag21aa"]).analyze();
      expect(result.violations.map((v) => `${path}: ${v.id} (${v.nodes.length}) ${v.nodes[0]?.target}`)).toEqual([]);
    };
    await check("/", "[data-testid=event-card]");
    await check("/login", "form");
    await check("/register", "form");
    await check(`/events/${id}`, "[data-seat]");
    await signIn(page, fan.email);
    await check("/tickets", "[class*=qrSvg] svg");
  });
}
