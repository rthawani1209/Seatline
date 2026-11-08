import { expect, test } from "@playwright/test";
import { account, auth, signIn } from "./helpers";

test("an admin finds an account and makes it door staff", async ({ page, request }) => {
  const admin = await account(request, "ADMIN");
  const fan = await account(request);
  await signIn(page, admin.email);
  await page.getByRole("link", { name: "Admin" }).click();

  await page.getByLabel("Search by email").fill(fan.email);
  const row = page.getByRole("listitem").filter({ hasText: fan.email });
  await expect(row).toBeVisible();
  await row.getByLabel(`Role for ${fan.email}`).selectOption("DOOR_STAFF");
  await expect(page.getByRole("status")).toContainText(`${fan.email} is now door staff`);

  // their old sign-in carried the old role
  expect((await request.get("/api/me", { headers: auth(fan.token) })).status()).toBe(401);

  // and an admin can't demote themselves from here
  await page.getByLabel("Search by email").fill(admin.email);
  await expect(page.getByLabel(`Role for ${admin.email}`)).toBeDisabled();
});

test("only admins can open the admin page", async ({ page, request }) => {
  const org = await account(request, "ORGANIZER");
  await signIn(page, org.email);
  await expect(page.getByRole("link", { name: "Admin" })).toHaveCount(0);
  await page.goto("/admin");
  await expect(page.getByRole("heading", { name: "Not for this account" })).toBeVisible();
});
