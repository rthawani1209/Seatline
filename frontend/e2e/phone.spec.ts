import { expect, test } from "@playwright/test";
import { account, onSaleEvent, signIn } from "./helpers";

test("on a phone: menu, seat map and hold all work", async ({ page, request }) => {
  const { id } = await onSaleEvent(request);
  const fan = await account(request);
  await page.goto("/");
  await page.getByRole("button", { name: "Menu" }).click();
  await expect(page.getByRole("link", { name: "Sign in" })).toBeVisible();
  await page.getByRole("button", { name: "Menu" }).click();
  await expect(page.getByRole("link", { name: "Sign in" })).toBeHidden();

  await signIn(page, fan.email);
  await page.goto(`/events/${id}`);
  await page.getByText("Main floor").click();
  await page.locator('[data-seat="MAIN-A-5"]').tap();
  await expect(page.locator('[data-seat="MAIN-A-5"]')).toHaveAttribute("data-state", "picked");
  // nothing on the page is wider than the screen
  const overflow = await page.evaluate(() => document.documentElement.scrollWidth - window.innerWidth);
  expect(overflow).toBeLessThanOrEqual(0);
  await page.getByRole("button", { name: /Hold 1/ }).click();
  await expect(page).toHaveURL(/\/checkout\//);
  await expect(page.getByRole("button", { name: /^Pay / })).toBeVisible();
});
