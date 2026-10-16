import { expect, test } from '@playwright/test';

test('unauthenticated students are redirected and the assessment BFF rejects requests', async ({
  page,
}) => {
  await page.goto('/student/assessments');
  await expect(page).toHaveURL(/\/login$/);
  await expect(page.getByRole('heading', { name: 'Đăng nhập hệ thống' })).toBeVisible();

  const response = await page.request.get(
    'http://127.0.0.1:3087/api/assessments/student/assessments/tests',
  );
  expect(response.status()).toBe(401);
  await expect(response.json()).resolves.toMatchObject({
    error: 'Bạn cần đăng nhập để tiếp tục.',
  });
});
