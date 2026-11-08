import { createHmac } from "node:crypto";
import { expect, test, type Page } from "@playwright/test";
import { account, PASSWORD, signIn, sql } from "./helpers";

// Next adds an empty route announcer with role=alert; the page's own alerts have the .alert class
const alert = (page: Page) => page.locator(".alert");

// The same TOTP an authenticator app computes (RFC 6238: SHA-1, 30 s, 6 digits).
function totp(secret: string, at = Date.now()): string {
  const alphabet = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";
  let bits = "";
  for (const ch of secret.replace(/=+$/, "").toUpperCase()) bits += alphabet.indexOf(ch).toString(2).padStart(5, "0");
  const key = Buffer.from(bits.match(/.{8}/g)!.map((b) => parseInt(b, 2)));
  const counter = Buffer.alloc(8);
  counter.writeBigUInt64BE(BigInt(Math.floor(at / 1000 / 30)));
  const h = createHmac("sha1", key).update(counter).digest();
  const o = h[h.length - 1] & 0xf;
  return String((h.readUInt32BE(o) & 0x7fffffff) % 1_000_000).padStart(6, "0");
}

/** A code from a later 30-second step than `used`, since each step's code works only once. */
async function freshCode(secret: string, used: string) {
  for (;;) {
    const c = totp(secret);
    if (c !== used) return c;
    await new Promise((r) => setTimeout(r, 1000));
  }
}

test("turn two-step sign-in on from the account page, need a code to sign in, then turn it off", async ({ page, request }) => {
  test.setTimeout(150_000);
  const fan = await account(request);
  await signIn(page, fan.email);

  await page.getByRole("link", { name: "Account" }).click();
  await expect(page).toHaveURL(/\/account/);
  await expect(page.getByText(fan.email)).toBeVisible();

  await page.getByRole("button", { name: "Set up two-step sign-in" }).click();
  await expect(page.getByRole("img", { name: /QR code/ })).toBeVisible();
  const secret = (await page.getByLabel("Setup key").textContent())!.trim();

  await page.getByLabel("Code from the app").fill("000000");
  await page.getByRole("button", { name: "Turn on" }).click();
  await expect(alert(page)).toContainText("didn't work");

  const first = totp(secret);
  await page.getByLabel("Code from the app").fill(first);
  await page.getByRole("button", { name: "Turn on" }).click();
  await expect(page.getByText("Two-step sign-in is on.")).toBeVisible();
  await expect(page.getByText("ON", { exact: true })).toBeVisible();

  // signing in now asks for a code
  await page.getByRole("button", { name: "Sign out", exact: true }).first().click();
  await page.goto("/login");
  await page.getByLabel("Email").fill(fan.email);
  await page.getByLabel("Password").fill(PASSWORD);
  await page.getByRole("button", { name: "Sign in" }).click();
  const second = await freshCode(secret, first);
  await page.getByLabel("Authenticator code").fill(second);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).not.toHaveURL(/\/login/);

  await page.goto("/account");
  await page.getByRole("button", { name: "Turn off two-step sign-in" }).click();
  await page.getByLabel("Code to turn it off").fill(await freshCode(secret, second));
  await page.getByRole("button", { name: "Turn off" }).click();
  await expect(page.getByText(/Two-step sign-in is off\./)).toBeVisible();
  await expect(page.getByText("OFF", { exact: true })).toBeVisible();
});

test("change the password: the wrong current one is refused, the new one works, this tab stays signed in", async ({ page, request }) => {
  const fan = await account(request);
  await signIn(page, fan.email);
  await page.goto("/account");

  const newPassword = "a-brand-new-password-42";
  await page.getByLabel("Current password").fill("not-my-password-at-all");
  await page.getByLabel(/^New password(?! again)/).fill(newPassword);
  await page.getByLabel("New password again").fill(newPassword);
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(alert(page)).toContainText("current password isn't right");

  await page.getByLabel("Current password").fill(PASSWORD);
  await page.getByRole("button", { name: "Change password" }).click();
  await expect(page.getByText("Password changed.")).toBeVisible();

  // the old token is dead, but this tab got a new one
  expect((await request.get("/api/me", { headers: { authorization: `Bearer ${fan.token}` } })).status()).toBe(401);
  await page.goto("/tickets");
  await expect(page).toHaveURL(/\/tickets/);

  const old = await request.post("/api/auth/login", { data: { email: fan.email, password: PASSWORD } });
  expect(old.status()).toBe(401);
  const fresh = await request.post("/api/auth/login", { data: { email: fan.email, password: newPassword } });
  expect(fresh.ok()).toBeTruthy();
});

test("sign out everywhere ends other sessions and this one", async ({ page, request }) => {
  const fan = await account(request);
  await signIn(page, fan.email);
  await page.goto("/account");
  await page.getByRole("button", { name: "Sign out everywhere" }).click();
  await expect(page).toHaveURL(/\/login/);
  expect((await request.get("/api/me", { headers: { authorization: `Bearer ${fan.token}` } })).status()).toBe(401);
});

test("forgot password: the emailed link sets a new password, and works only once", async ({ page, request }) => {
  const fan = await account(request);
  await page.goto("/login");
  await page.getByRole("link", { name: "Forgot password?" }).click();
  await page.getByLabel("Email").fill(fan.email);
  await page.getByRole("button", { name: "Email me a reset link" }).click();
  await expect(page.getByRole("status")).toContainText("we've emailed it a link");

  const rows = await sql<{ body: string }>(
    "select body from outbox_emails where to_address = $1 and subject like 'Reset%' order by id desc limit 1",
    [fan.email],
  );
  const link = rows[0]?.body.match(/\/reset\?token=(\S+)/);
  expect(link, "reset email").toBeTruthy();

  const newPassword = "reset-to-something-else-7";
  await page.goto(`/reset?token=${link![1]}`);
  await page.getByLabel(/^New password(?! again)/).fill(newPassword);
  await page.getByLabel("New password again").fill(newPassword);
  await page.getByRole("button", { name: "Save new password" }).click();
  await expect(page).toHaveURL(/\/login\?reset=1/);
  await expect(page.getByText("Password changed. Sign in with the new one.")).toBeVisible();

  await page.getByLabel("Email").fill(fan.email);
  await page.getByLabel("Password").fill(newPassword);
  await page.getByRole("button", { name: "Sign in" }).click();
  await expect(page).not.toHaveURL(/\/login/);

  // the same link again is refused
  await page.goto(`/reset?token=${link![1]}`);
  await page.getByLabel(/^New password(?! again)/).fill(newPassword + "x");
  await page.getByLabel("New password again").fill(newPassword + "x");
  await page.getByRole("button", { name: "Save new password" }).click();
  await expect(alert(page)).toContainText("expired or was already used");
});
