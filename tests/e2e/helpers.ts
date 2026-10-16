import { randomUUID } from 'node:crypto';
import { expect, type Page } from '@playwright/test';

export async function loginAsStudent(page: Page) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(`student-${randomUUID()}@example.test`);
  await page.getByLabel('Mật khẩu').fill('sandbox-only-password');
  await page.getByRole('button', { name: 'Đăng nhập' }).click();
  await expect(page).toHaveURL(/\/student$/);
}
