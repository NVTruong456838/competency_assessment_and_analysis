import { expect, test } from '@playwright/test';
import { loginAsStudent } from './helpers';

test('student logs in, selects a sandbox assessment, and starts an attempt', async ({ page }) => {
  await loginAsStudent(page);
  await page.getByRole('link', { name: 'Xem bài đánh giá' }).click();
  await expect(page.getByRole('heading', { name: 'Synthetic TSA Mock Test' })).toBeVisible();

  await page.getByRole('button', { name: 'Xem hướng dẫn' }).click();
  await expect(page.getByRole('heading', { name: 'Hướng dẫn làm bài' })).toBeVisible();
  await page.getByRole('button', { name: 'Bắt đầu làm bài' }).click();

  await expect(page).toHaveURL(/\/student\/assessments\/7$/);
  await expect(page.getByText('Synthetic browser-test question: choose option A.')).toBeVisible();
});
