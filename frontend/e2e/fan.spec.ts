import { expect, test } from "@playwright/test";
import { account, auth, buy, confirmationCode, email, onSaleEvent, PASSWORD, signIn, sql, tierId } from "./helpers";

test("a new fan signs up, verifies with the emailed code and signs in", async ({ page }) => {
  const address = email("signup");
  await page.goto("/");
  await page.getByRole("link", { name: "Create account" }).click();
  await page.getByLabel("Name").fill("Sam Rivera");
  await page.getByLabel("Email").fill(address);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Create account" }).click();
  await expect(page.getByRole("heading", { name: "Check your email" })).toBeVisible();

  // not verified yet: signing in is refused with a clear message
  await page.goto("/login");
  await page.getByLabel("Email").fill(address);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator(".alert")).toContainText("Verify your email");

  await page.goto("/verify");
  await page.getByLabel("Confirmation code").fill(await confirmationCode(address));
  await page.getByRole("button", { name: "Verify" }).click();
  await expect(page.getByText("Email verified")).toBeVisible();

  await page.getByLabel("Email").fill(address);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.getByText("Sam Rivera")).toBeVisible();
  await page.getByRole("button", { name: "Sign out" }).click();
  await expect(page.getByRole("link", { name: "Sign in" })).toBeVisible();
});

test("the link in the email verifies straight away, and only once", async ({ page, request }) => {
  const address = email("link");
  await request.post("/api/auth/register", { data: { email: address, password: PASSWORD, displayName: "Link" } });
  const body = (await sql<{ body: string }>("select body from outbox_emails where to_address = $1", [address]))[0].body;
  const link = new URL(body.match(/(https?:\/\/\S+\/verify\?token=\S+)/)![1]);
  await page.goto(link.pathname + link.search);
  await expect(page.getByText("Email verified")).toBeVisible();
  await page.goto(link.pathname + link.search);
  await expect(page.locator(".alert")).toContainText("expired or was already used");
});

test("a wrong password gets a plain answer", async ({ page, request }) => {
  const fan = await account(request);
  await page.goto("/login");
  await page.getByLabel("Email").fill(fan.email);
  await page.getByLabel("Password").fill("not-the-password-123");
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page.locator(".alert")).toHaveText("That email and password don't match.");
});

test("pick seats on the map, get declined, pay, and see the tickets", async ({ page, request }) => {
  const { id, event } = await onSaleEvent(request);
  const fan = await account(request);
  await signIn(page, fan.email);

  await page.goto("/");
  await page.getByTestId("event-card").filter({ hasText: event.name }).click();
  await expect(page.getByRole("heading", { name: event.name })).toBeVisible();

  await page.getByText("Main floor").click();
  await page.locator('[data-seat="MAIN-B-3"]').click();
  await page.locator('[data-seat="MAIN-B-4"]').click();
  await expect(page.getByRole("button", { name: "Hold 2 for 10 minutes" })).toBeEnabled();
  // clicking a picked seat again un-picks it
  await page.locator('[data-seat="MAIN-B-4"]').click();
  await expect(page.getByRole("button", { name: "Hold 1 for 10 minutes" })).toBeEnabled();
  await page.locator('[data-seat="MAIN-B-4"]').click();

  await page.getByRole("button", { name: /Hold 2/ }).click();
  await expect(page).toHaveURL(/\/checkout\//);
  await expect(page.getByRole("timer")).toHaveText(/^(10:00|9:5\d)$/);
  await expect(page.getByText("MAIN · Row B · Seat 3")).toBeVisible();

  // the seats now show as held to everyone else
  const seats = await (await request.get(`/api/events/${id}/seats`)).json();
  expect(seats.find((x: { seat: string }) => x.seat === "MAIN-B-3").status).toBe("HELD");

  await page.getByText("Visa ending 0002").click();
  await page.getByRole("button", { name: /^Pay / }).click();
  await expect(page.locator(".alert")).toHaveText("Your card was declined. Nothing was charged.");

  await page.getByText("Visa ending 4242").click();
  await page.getByRole("button", { name: /^Pay / }).click();
  await expect(page.getByRole("heading", { name: "You're in." })).toBeVisible();
  await page.getByRole("link", { name: "See my tickets" }).click();

  await expect(page.getByText("1 of 2")).toBeVisible();
  await expect(page.locator("[class*=qrSvg] svg")).toBeVisible();
  await page.getByRole("button", { name: "Next →" }).click();
  await expect(page.getByText("2 of 2")).toBeVisible();
  await page.getByRole("button", { name: "← Previous" }).click();
  await expect(page.getByText("1 of 2")).toBeVisible();
});

test("best available picks seats together, and a promo code lowers the price", async ({ page, request }) => {
  const { id, organizer, event } = await onSaleEvent(request);
  await request.post(`/api/events/${id}/promo-codes`, { headers: auth(organizer.token), data: { code: "E2EHALF", percentOff: 50, maxUses: 5 } });
  const fan = await account(request);
  await signIn(page, fan.email);
  await page.goto(`/events/${id}`);
  await page.getByText("Main floor").click();
  await page.getByRole("button", { name: "More seats" }).click(); // 3
  await page.getByRole("button", { name: "Best available" }).click();
  const picked = page.locator('[data-state="picked"]');
  await expect(picked).toHaveCount(3);
  const rows = new Set(await picked.evaluateAll((els) => els.map((e) => e.getAttribute("data-seat")!.split("-")[1])));
  expect(rows.size).toBe(1);

  await page.getByRole("button", { name: "Clear" }).click();
  await expect(picked).toHaveCount(0);
  await page.getByRole("button", { name: "Fewer seats" }).click(); // 2
  await page.getByRole("button", { name: "Best available" }).click();
  await expect(picked).toHaveCount(2);
  await page.getByLabel("Promo code").fill("e2ehalf");
  await page.getByRole("button", { name: /Hold 2/ }).click();
  await expect(page.getByText("Promo E2EHALF")).toBeVisible();
  void event;
});

test("a bad promo code is refused before anything is held", async ({ page, request }) => {
  const { id } = await onSaleEvent(request);
  const fan = await account(request);
  await signIn(page, fan.email);
  await page.goto(`/events/${id}`);
  await page.getByText("Main floor").click();
  await page.locator('[data-seat="MAIN-A-1"]').click();
  await page.getByLabel("Promo code").fill("NOPE");
  await page.getByRole("button", { name: /Hold 1/ }).click();
  await expect(page.locator(".alert")).toHaveText("That promo code isn't valid for this event.");
  await expect(page.locator('[data-seat="MAIN-A-1"]')).toHaveAttribute("data-state", "picked");
});

test("a seat someone else takes disappears from your picks", async ({ page, request }) => {
  const { id, event } = await onSaleEvent(request);
  const fan = await account(request);
  await signIn(page, fan.email);
  await page.goto(`/events/${id}`);
  await page.getByText("Main floor").click();
  await page.locator('[data-seat="MAIN-C-5"]').click();
  await expect(page.locator('[data-seat="MAIN-C-5"]')).toHaveAttribute("data-state", "picked");

  const rival = await account(request);
  const main = tierId(event, "Main floor");
  await buy(request, rival.token, id, main, ["MAIN-C-5"]);

  await expect(page.locator('[data-seat="MAIN-C-5"]')).toHaveAttribute("data-state", "sold", { timeout: 6000 });
  await expect(page.getByText("Pick seats on the map")).toBeVisible();
});

test("letting a hold go puts the seats straight back on sale", async ({ page, request }) => {
  const { id } = await onSaleEvent(request);
  const fan = await account(request);
  await signIn(page, fan.email);
  await page.goto(`/events/${id}`);
  await page.getByText("Side").first().click();
  await page.locator('[data-seat="LEFT-A-1"]').click();
  await page.getByRole("button", { name: /Hold 1/ }).click();
  await page.getByRole("button", { name: "Let these seats go" }).click();
  await expect(page).toHaveURL(new RegExp(`/events/${id}$`));
  await expect(page.locator('[data-seat="LEFT-A-1"]')).toHaveAttribute("data-state", "free");
});

test("the seat map works from the keyboard", async ({ page, request }) => {
  const { id } = await onSaleEvent(request);
  const fan = await account(request);
  await signIn(page, fan.email);
  await page.goto(`/events/${id}`);
  await page.getByText("Main floor").click();
  await page.locator('[data-seat="MAIN-A-1"]').focus();
  await page.keyboard.press("ArrowRight");
  await page.keyboard.press("ArrowDown");
  await page.keyboard.press("Enter");
  await expect(page.locator('[data-seat="MAIN-B-2"]')).toHaveAttribute("data-state", "picked");
  await expect(page.locator('[data-seat="MAIN-B-2"]')).toHaveAttribute("aria-pressed", "true");
  await page.keyboard.press(" ");
  await expect(page.locator('[data-seat="MAIN-B-2"]')).toHaveAttribute("aria-pressed", "false");
  // sold-out-for-this-type seats can't be picked
  await page.locator('[data-seat="LEFT-A-1"]').click({ force: true });
  await expect(page.locator('[data-seat="LEFT-A-1"]')).toHaveAttribute("data-state", "off");
});

test("zoom and filters", async ({ page, request }) => {
  const { id } = await onSaleEvent(request);
  await page.goto(`/events/${id}`);
  await page.getByText("Main floor").click();
  await page.getByRole("button", { name: "Zoom in" }).click();
  await expect(page.getByText("125%")).toBeVisible();
  await page.getByRole("button", { name: "Zoom out" }).click();
  await page.getByRole("button", { name: "Zoom out" }).click();
  await expect(page.getByText("75%")).toBeVisible();
  await page.getByRole("button", { name: "Reset zoom" }).click();
  await expect(page.getByText("100%")).toBeVisible();

  await page.getByRole("button", { name: "Accessible only" }).click();
  await expect(page.locator('[data-seat="MAIN-A-1"]')).toHaveAttribute("data-state", "off");
  await expect(page.locator('[data-seat="MAIN-F-5"]')).toHaveAttribute("data-state", "free");
  await page.getByRole("button", { name: "Accessible only" }).click();
  await expect(page.locator('[data-seat="MAIN-A-1"]')).toHaveAttribute("data-state", "free");
  await page.getByRole("button", { name: "Hide limited view" }).click();
  await expect(page.getByRole("button", { name: "Hide limited view" })).toHaveAttribute("aria-pressed", "true");

  // signed out: the hold button sends you to sign in and back
  await page.locator('[data-seat="MAIN-A-2"]').click();
  await page.getByRole("button", { name: "Sign in to hold seats" }).click();
  await expect(page).toHaveURL(new RegExp(`/login\\?next=%2Fevents%2F${id}`));
});
