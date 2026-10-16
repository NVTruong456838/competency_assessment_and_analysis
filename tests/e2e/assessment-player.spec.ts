import { expect, test } from '@playwright/test';
import { loginAsStudent } from './helpers';

test('student renders structured chart data accessibly', async ({ page }) => {
  await page.route('**/api/assessments/student/assessments/attempts/7', async (route) => {
    const response = await route.fetch();
    const payload = await response.json() as {
      questions: Array<{ groupStimulus: Record<string, unknown> }>;
    };
    payload.questions[0].groupStimulus = {
      groupVersionId: 3002,
      title: 'Synthetic shared chart',
      stimulusType: 'CHART',
      stimulus: {
        labels: ['Sample A', 'Sample B'],
        series: [{ name: 'Value', values: [12, 18] }],
      },
      itemOrder: 1,
      itemCount: 2,
    };
    await route.fulfill({ response, json: payload });
  });

  await loginAsStudent(page);
  await page.goto('/student/assessments/7');
  await expect(page.getByRole('table')).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'Value' })).toBeVisible();
  await expect(page.getByRole('row', { name: 'Sample B 18' })).toBeVisible();
});

test('student autosaves, flags, and submits without seeing answer keys', async ({ page }) => {
  await page.route('**/api/assessments/student/assessments/attempts/7/scoring', async (route) => {
    await route.fulfill({ status: 404, contentType: 'application/json', body: '{}' });
  });
  await page.route('**/api/assessments/student/assessments/attempts/7/scoring-runs', async (route) => {
    await route.fulfill({
      status: 201,
      contentType: 'application/json',
      body: JSON.stringify({
        run: {
          rawScore: 1,
          maxScore: 1,
          accuracy: 1,
          correctQuestions: 1,
          totalQuestions: 1,
        },
      }),
    });
  });
  await loginAsStudent(page);
  const sessionResponse = page.waitForResponse((response) =>
    response.url().includes('/api/assessments/student/assessments/attempts/7') &&
    response.request().method() === 'GET',
  );
  await page.goto('/student/assessments/7');
  const payload: unknown = await (await sessionResponse).json();
  const question = (payload as { questions: Record<string, unknown>[] }).questions[0];
  expect(question).not.toHaveProperty('correctAnswer');
  expect(question).not.toHaveProperty('explanation');
  await expect(page.getByRole('table')).toBeVisible();
  await expect(page.getByRole('cell', { name: '18' })).toBeVisible();

  await page.getByRole('radio', { name: 'Synthetic choice A' }).check();
  await expect(page.getByRole('status')).toContainText('Đã lưu');

  await page.getByRole('button', { name: 'Đánh dấu xem lại' }).click();
  await expect(page.getByRole('button', { name: 'Bỏ đánh dấu xem lại' })).toBeVisible();
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Nộp bài' }).click();
  await expect(page.getByRole('heading', { name: 'Bài làm đã kết thúc' })).toBeVisible();
  await expect(page.getByText(/Kết quả nội bộ: 1\/1 điểm/)).toBeVisible();
  await expect(page.getByText(/đáp án và lời giải không được hiển thị/i)).toBeVisible();
});

test('student cannot submit while an answer remains unsaved', async ({ page }) => {
  let saveRequests = 0;
  let submitRequests = 0;
  await page.route(
    '**/api/assessments/student/assessments/attempts/7/items/1101/answer',
    async (route) => {
      saveRequests += 1;
      await route.fulfill({
        status: 503,
        contentType: 'application/json',
        body: JSON.stringify({ error: 'Answer service temporarily unavailable.' }),
      });
    },
  );
  page.on('request', (request) => {
    if (request.url().endsWith('/attempts/7/submit')) submitRequests += 1;
  });

  await loginAsStudent(page);
  await page.goto('/student/assessments/7');
  await page.getByRole('radio', { name: 'Synthetic choice A' }).check();
  await expect(page.locator('p[role="alert"]')).toContainText('Answer service temporarily unavailable.');
  expect(saveRequests).toBeGreaterThanOrEqual(2);

  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Nộp bài' }).click();
  await expect(page.locator('p[role="alert"]')).toContainText('chưa lưu được');
  await expect(page.getByRole('heading', { name: 'Bài làm đã kết thúc' })).toHaveCount(0);
  expect(submitRequests).toBe(0);
});
