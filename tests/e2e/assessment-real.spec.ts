import { createHmac, randomUUID } from 'node:crypto';
import { expect, test, type Page } from '@playwright/test';

const fixturePassword = 'phase05-isolated-e2e-password-20261015';
const diagnosticTitle = `Synthetic DIAGNOSTIC ${randomUUID()}`;
const timeoutTitle = `Synthetic TIMEOUT ${randomUUID()}`;
const hsaScienceTitle = `Synthetic HSA Science ${randomUUID()}`;
const tableGroupTitle = `Synthetic TABLE GROUP ${randomUUID()}`;
const chartGroupTitle = `Synthetic CHART GROUP ${randomUUID()}`;

function requiredFixture(name: string): string {
  const value = process.env[name];
  if (!value) throw new Error(`Missing isolated acceptance fixture value: ${name}`);
  return value;
}

async function login(page: Page, email: string) {
  await page.goto('/login');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Mật khẩu').fill(fixturePassword);
  const loginResponsePromise = page.waitForResponse((response) =>
    response.url().endsWith('/api/auth/login'),
  );
  await page.getByRole('button', { name: 'Đăng nhập' }).click();
  const loginResponse = await loginResponsePromise;
  expect(loginResponse.status(), await loginResponse.text()).toBe(200);
}

function expiredTestToken() {
  const encode = (value: object) => Buffer.from(JSON.stringify(value)).toString('base64url');
  const input = `${encode({ alg: 'HS256', typ: 'JWT' })}.${encode({
    iss: 'https://smart-assessment.local',
    sub: 'expired-phase05-test@example.invalid',
    iat: Math.floor(Date.now() / 1000) - 7200,
    exp: Math.floor(Date.now() / 1000) - 3600,
  })}`;
  const signature = createHmac(
    'sha256',
    Buffer.from('AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=', 'base64'),
  ).update(input).digest('base64url');
  return `${input}.${signature}`;
}

async function buildGroupedTest(
  page: Page,
  title: string,
  examVersionId: number,
  sectionId: number,
  releaseId: number,
  skillId: number,
) {
  const blueprintResponse = await page.request.post(
    '/api/assessments/teacher/assessments/blueprints',
    {
      data: {
        title,
        description: 'Isolated structured shared-stimulus fixture',
        examVersionId,
        testType: 'MINI_MOCK',
        durationMinutes: 30,
        sections: [{ sectionId, questionCount: 2, durationMinutes: null }],
        selectionRules: {
          skillIds: [skillId],
          cognitiveLevelDistribution: {},
          difficultyDistribution: {},
          questionTypeDistribution: {},
        },
        optionalBranchSelections: [],
      },
    },
  );
  expect(blueprintResponse.status()).toBe(201);
  const blueprint = await blueprintResponse.json() as { blueprintVersionId: number };
  const buildResponse = await page.request.post(
    '/api/assessments/teacher/assessments/tests/build',
    {
      data: {
        blueprintVersionId: blueprint.blueprintVersionId,
        taxonomyReleaseId: releaseId,
        seed: 8052026,
        title,
      },
    },
  );
  expect(buildResponse.status()).toBe(200);
  const build = await buildResponse.json() as {
    testVersionId: number;
    status: string;
    selectedQuestions: number;
  };
  expect(build).toMatchObject({ status: 'DRAFT', selectedQuestions: 2 });
  return build.testVersionId;
}

async function renderStructuredGroup(page: Page, title: string, stimulusType: 'TABLE' | 'CHART') {
  await page.goto('/student/assessments');
  const assessment = page.locator('article').filter({ hasText: title }).first();
  await assessment.getByRole('button', { name: 'Xem hướng dẫn' }).click();
  const startResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
    && /\/api\/assessments\/student\/assessments\/test-versions\/\d+\/attempts$/.test(response.url()),
  );
  await page.getByRole('button', { name: 'Bắt đầu làm bài' }).click();
  const startResponse = await startResponsePromise;
  expect(startResponse.status()).toBe(201);
  const session = await startResponse.json() as {
    questions: Array<{ groupStimulus: { stimulusType: string } | null }>;
  };
  expect(session.questions[0].groupStimulus?.stimulusType).toBe(stimulusType);
  await expect(page.locator('aside[data-question-group-version]')).toBeVisible();
  await expect(page.getByRole('table')).toBeVisible();
  await expect(page.getByRole('columnheader', { name: 'Value' })).toBeVisible();
  await expect(page.getByRole('cell', { name: '18' })).toBeVisible();
}

test('teacher builds Diagnostic and HSA branch tests; admin publishes with role-specific JWTs', async ({ browser }) => {
  test.setTimeout(120_000);
  const teacherContext = await browser.newContext({ baseURL: 'http://127.0.0.1:3088' });
  const adminContext = await browser.newContext({ baseURL: 'http://127.0.0.1:3088' });
  const teacher = await teacherContext.newPage();
  const admin = await adminContext.newPage();
  const teacherEmail = requiredFixture('PHASE05_E2E_TEACHER_EMAIL');
  const adminEmail = requiredFixture('PHASE05_E2E_ADMIN_EMAIL');
  const examVersionId = Number(requiredFixture('PHASE05_E2E_EXAM_VERSION_ID'));
  const sectionId = Number(requiredFixture('PHASE05_E2E_SECTION_ID'));
  const releaseId = Number(requiredFixture('PHASE05_E2E_TAXONOMY_RELEASE_ID'));
  const skillId = Number(requiredFixture('PHASE05_E2E_SKILL_ID'));
  const hsaExamVersionId = Number(requiredFixture('PHASE05_E2E_HSA_EXAM_VERSION_ID'));
  const scienceSectionId = Number(requiredFixture('PHASE05_E2E_HSA_SCIENCE_SECTION_ID'));
  const englishSectionId = Number(requiredFixture('PHASE05_E2E_HSA_ENGLISH_SECTION_ID'));
  const optionalGroupId = Number(requiredFixture('PHASE05_E2E_HSA_OPTIONAL_GROUP_ID'));
  const hsaReleaseId = Number(requiredFixture('PHASE05_E2E_HSA_TAXONOMY_RELEASE_ID'));
  const scienceSkillId = Number(requiredFixture('PHASE05_E2E_HSA_SCIENCE_SKILL_ID'));
  const englishSkillId = Number(requiredFixture('PHASE05_E2E_HSA_ENGLISH_SKILL_ID'));

  try {
    await login(teacher, teacherEmail);
    await expect(teacher).toHaveURL(/\/teacher$/);
    const examVersions = await teacher.request.get(
      '/api/assessments/teacher/assessments/exam-versions',
    );
    expect(examVersions.status()).toBe(200);
    const questionGroupsResponse = await teacher.request.get(
      '/api/assessments/teacher/question-groups',
    );
    expect(questionGroupsResponse.status()).toBe(200);
    const questionGroups = await questionGroupsResponse.json() as {
      question_group_version_id: number;
      review_status: string;
    }[];
    const publishedGroup = questionGroups.find((group) =>
      group.question_group_version_id === Number(requiredFixture('PHASE05_E2E_QUESTION_GROUP_VERSION_ID')),
    );
    expect(publishedGroup?.review_status).toBe('PUBLISHED');
    const groupSnapshotResponse = await teacher.request.get(
      `/api/assessments/teacher/question-group-versions/${publishedGroup?.question_group_version_id}`,
    );
    expect(groupSnapshotResponse.status()).toBe(200);
    const groupSnapshot = await groupSnapshotResponse.json() as {
      review_status: string;
      items: Array<{ question_version_id: number; item_order: number }>;
    };
    expect(groupSnapshot.review_status).toBe('PUBLISHED');
    expect(groupSnapshot.items.map((item) => item.item_order)).toEqual([1, 2, 3]);
    expect((await teacher.request.post(
      `/api/assessments/teacher/question-group-versions/${publishedGroup?.question_group_version_id}/items`,
      { data: { questionVersionId: groupSnapshot.items[0].question_version_id, itemOrder: 4 } },
    )).status()).toBe(400);

    const diagnosticBlueprint = await teacher.request.post(
      '/api/assessments/teacher/assessments/blueprints',
      {
        data: {
          title: diagnosticTitle,
          description: 'Isolated Phase 05 acceptance fixture',
          examVersionId,
          testType: 'DIAGNOSTIC',
          durationMinutes: 30,
          sections: [{ sectionId, questionCount: 3, durationMinutes: null }],
          selectionRules: {
            skillIds: [skillId],
            cognitiveLevelDistribution: {},
            difficultyDistribution: {},
            questionTypeDistribution: {},
          },
          optionalBranchSelections: [],
        },
      },
    );
    expect(diagnosticBlueprint.status()).toBe(201);
    const diagnosticBlueprintData = await diagnosticBlueprint.json() as {
      blueprintVersionId: number;
    };
    const diagnosticBuild = await teacher.request.post(
      '/api/assessments/teacher/assessments/tests/build',
      {
        data: {
          blueprintVersionId: diagnosticBlueprintData.blueprintVersionId,
          taxonomyReleaseId: releaseId,
          seed: 8052026,
          title: diagnosticTitle,
        },
      },
    );
    expect(diagnosticBuild.status()).toBe(200);
    const diagnostic = await diagnosticBuild.json() as {
      testVersionId: number;
      status: string;
      selectedQuestions: number;
    };
    expect(diagnostic).toMatchObject({ status: 'DRAFT', selectedQuestions: 3 });
    const timeoutBlueprint = await teacher.request.post(
      '/api/assessments/teacher/assessments/blueprints',
      {
        data: {
          title: timeoutTitle,
          description: 'Isolated Phase 05 timeout acceptance fixture',
          examVersionId,
          testType: 'DIAGNOSTIC',
          durationMinutes: 1,
          sections: [{ sectionId, questionCount: 3, durationMinutes: null }],
          selectionRules: {
            skillIds: [skillId],
            cognitiveLevelDistribution: {},
            difficultyDistribution: {},
            questionTypeDistribution: {},
          },
          optionalBranchSelections: [],
        },
      },
    );
    expect(timeoutBlueprint.status()).toBe(201);
    const timeoutBlueprintData = await timeoutBlueprint.json() as {
      blueprintVersionId: number;
    };
    const timeoutBuild = await teacher.request.post(
      '/api/assessments/teacher/assessments/tests/build',
      {
        data: {
          blueprintVersionId: timeoutBlueprintData.blueprintVersionId,
          taxonomyReleaseId: releaseId,
          seed: 8052026,
          title: timeoutTitle,
        },
      },
    );
    expect(timeoutBuild.status()).toBe(200);
    const timeoutTest = await timeoutBuild.json() as {
      testVersionId: number;
      status: string;
      selectedQuestions: number;
    };
    expect(timeoutTest).toMatchObject({ status: 'DRAFT', selectedQuestions: 3 });
    const tableTestVersionId = await buildGroupedTest(
      teacher,
      tableGroupTitle,
      Number(requiredFixture('PHASE05_E2E_TABLE_EXAM_VERSION_ID')),
      Number(requiredFixture('PHASE05_E2E_TABLE_SECTION_ID')),
      Number(requiredFixture('PHASE05_E2E_TABLE_TAXONOMY_RELEASE_ID')),
      Number(requiredFixture('PHASE05_E2E_TABLE_SKILL_ID')),
    );
    const chartTestVersionId = await buildGroupedTest(
      teacher,
      chartGroupTitle,
      Number(requiredFixture('PHASE05_E2E_CHART_EXAM_VERSION_ID')),
      Number(requiredFixture('PHASE05_E2E_CHART_SECTION_ID')),
      Number(requiredFixture('PHASE05_E2E_CHART_TAXONOMY_RELEASE_ID')),
      Number(requiredFixture('PHASE05_E2E_CHART_SKILL_ID')),
    );
    expect((await teacher.request.post(
      `/api/assessments/admin/assessments/test-versions/${diagnostic.testVersionId}/publish`,
    )).status()).toBe(403);

    const createHsaBlueprint = async (
      title: string,
      sectionIds: number[],
      skillIds: number[],
      branchCode: string,
    ) => teacher.request.post('/api/assessments/teacher/assessments/blueprints', {
      data: {
        title,
        description: 'Synthetic HSA optional-branch acceptance fixture',
        examVersionId: hsaExamVersionId,
        testType: 'MINI_MOCK',
        durationMinutes: 30,
        sections: sectionIds.map((id) => ({ sectionId: id, questionCount: 1, durationMinutes: null })),
        selectionRules: {
          skillIds,
          cognitiveLevelDistribution: {},
          difficultyDistribution: {},
          questionTypeDistribution: {},
        },
        optionalBranchSelections: [{ optionalSectionGroupId: optionalGroupId, branchCode }],
      },
    });

    let scienceBlueprintVersionId = 0;
    let scienceTestVersionId = 0;
    for (const branch of [
      { title: hsaScienceTitle, sectionId: scienceSectionId, skillId: scienceSkillId, code: 'SCIENCE' },
      { title: 'Synthetic HSA English', sectionId: englishSectionId, skillId: englishSkillId, code: 'ENGLISH' },
    ]) {
      const blueprintResponse = await createHsaBlueprint(
        branch.title, [branch.sectionId], [branch.skillId], branch.code,
      );
      expect(blueprintResponse.status()).toBe(201);
      const blueprint = await blueprintResponse.json() as { blueprintVersionId: number };
      if (branch.code === 'SCIENCE') scienceBlueprintVersionId = blueprint.blueprintVersionId;
      const buildResponse = await teacher.request.post(
        '/api/assessments/teacher/assessments/tests/build',
        {
          data: {
            blueprintVersionId: blueprint.blueprintVersionId,
            taxonomyReleaseId: hsaReleaseId,
            seed: 8052026,
            title: branch.title,
          },
        },
      );
      expect(buildResponse.status()).toBe(200);
      const hsaBuild = await buildResponse.json() as {
        testVersionId: number;
        status: string;
        requestedQuestions: number;
        selectedQuestions: number;
      };
      expect(hsaBuild).toMatchObject({
        status: 'DRAFT',
        requestedQuestions: 1,
        selectedQuestions: 1,
      });
      if (branch.code === 'SCIENCE') scienceTestVersionId = hsaBuild.testVersionId;
    }
    const incompatibleRelease = await teacher.request.post(
      '/api/assessments/teacher/assessments/tests/build',
      {
        data: {
          blueprintVersionId: scienceBlueprintVersionId,
          taxonomyReleaseId: releaseId,
          seed: 8052026,
          title: 'Invalid HSA taxonomy release',
        },
      },
    );
    expect(incompatibleRelease.status()).toBe(400);

    const mixedBranch = await createHsaBlueprint(
      'Invalid mixed HSA optional branches',
      [scienceSectionId, englishSectionId],
      [scienceSkillId, englishSkillId],
      'SCIENCE',
    );
    expect(mixedBranch.status()).toBe(400);
    const invalidBranch = await createHsaBlueprint(
      'Invalid HSA optional branch',
      [scienceSectionId],
      [scienceSkillId],
      'BIOLOGY',
    );
    expect(invalidBranch.status()).toBe(400);

    await login(admin, adminEmail);
    await expect(admin).toHaveURL(/\/admin$/);
    const adminTeacherRoute = await admin.request.get(
      '/api/assessments/teacher/assessments/blueprints',
    );
    expect(adminTeacherRoute.status()).toBe(200);
    const publishResponse = await admin.request.post(
      `/api/assessments/admin/assessments/test-versions/${diagnostic.testVersionId}/publish`,
    );
    expect(publishResponse.status()).toBe(204);
    const publishTimeoutResponse = await admin.request.post(
      `/api/assessments/admin/assessments/test-versions/${timeoutTest.testVersionId}/publish`,
    );
    expect(publishTimeoutResponse.status()).toBe(204);
    expect((await admin.request.post(
      `/api/assessments/admin/assessments/test-versions/${tableTestVersionId}/publish`,
    )).status()).toBe(204);
    expect((await admin.request.post(
      `/api/assessments/admin/assessments/test-versions/${chartTestVersionId}/publish`,
    )).status()).toBe(204);
    expect((await admin.request.post(
      `/api/assessments/admin/assessments/test-versions/${scienceTestVersionId}/publish`,
    )).status()).toBe(204);

    const invalidJwtContext = await browser.newContext({ baseURL: 'http://127.0.0.1:3088' });
    await invalidJwtContext.addCookies([{
      name: 'tyl_session',
      value: 'not-a-valid-jwt',
      url: 'http://127.0.0.1:3088',
    }]);
    expect((await invalidJwtContext.request.get(
      '/api/assessments/teacher/assessments/blueprints',
    )).status()).toBe(401);
    await invalidJwtContext.clearCookies();
    await invalidJwtContext.addCookies([{
      name: 'tyl_session',
      value: expiredTestToken(),
      url: 'http://127.0.0.1:3088',
    }]);
    expect((await invalidJwtContext.request.get(
      '/api/assessments/teacher/assessments/blueprints',
    )).status()).toBe(401);
    await invalidJwtContext.close();
  } finally {
    await teacherContext.close();
    await adminContext.close();
  }
});

test('student completes and scores a published assessment through Next.js, JWT API, and PostgreSQL', async ({ page, browser }) => {
  test.setTimeout(180_000);
  const email = `phase05-${randomUUID()}@example.test`;
  const password = 'phase05-isolated-test-password';
  const unauthenticatedCatalog = await page.request.get(
    '/api/assessments/student/assessments/tests',
  );
  expect(unauthenticatedCatalog.status()).toBe(401);

  await page.goto('/register');
  await page.getByLabel('Họ và tên').fill('Phase 05 E2E Student');
  await page.getByLabel('Email').fill(email);
  await page.getByLabel('Mật khẩu', { exact: true }).fill(password);
  await page.getByLabel('Xác nhận mật khẩu').fill(password);
  await page.getByRole('button', { name: 'Đăng ký' }).click();
  await expect(page).toHaveURL(/\/student$/);

  const catalogResponsePromise = page.waitForResponse((response) =>
    response.url().endsWith('/api/assessments/student/assessments/tests'),
  );
  await page.goto('/student/assessments');
  const catalogResponse = await catalogResponsePromise;
  expect(catalogResponse.status()).toBe(200);
  const catalog = await catalogResponse.json();
  expect(catalog).toEqual(expect.arrayContaining([
    expect.objectContaining({ title: diagnosticTitle, testType: 'DIAGNOSTIC' }),
  ]));
  const teacherOnlyRoute = await page.request.get(
    '/api/assessments/teacher/assessments/blueprints',
  );
  expect(teacherOnlyRoute.status()).toBe(403);

  const assessment = page.locator('article').filter({ hasText: diagnosticTitle }).first();
  await assessment.getByRole('button', { name: 'Xem hướng dẫn' }).click();
  const startResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
    && /\/api\/assessments\/student\/assessments\/test-versions\/\d+\/attempts$/.test(response.url()),
  );
  await page.getByRole('button', { name: 'Bắt đầu làm bài' }).click();
  const startResponse = await startResponsePromise;
  expect(startResponse.status()).toBe(201);
  const session = await startResponse.json() as {
    attempt: { attemptId: number };
    questions: Array<{
      testItemId: number;
      passageTitle: string | null;
      visualAssets: Array<{ type: string; path: string; altText: string | null }>;
      groupStimulus: {
        groupVersionId: number;
        stimulusType: string;
        itemOrder: number;
        itemCount: number;
        stimulus: { content?: string };
      } | null;
    }>;
  };
  const attemptId: number = session.attempt.attemptId;
  const itemId: number = session.questions[0].testItemId;
  expect(JSON.stringify(session)).not.toMatch(/correct_answer|answer_key|explanation/i);
  expect(session.questions.map((question) => question.groupStimulus?.groupVersionId))
    .toEqual(Array(3).fill(Number(requiredFixture('PHASE05_E2E_QUESTION_GROUP_VERSION_ID'))));
  expect(session.questions.map((question) => question.groupStimulus?.itemOrder)).toEqual([1, 2, 3]);
  expect(session.questions.every((question) => question.groupStimulus?.itemCount === 3)).toBe(true);
  await expect(page).toHaveURL(new RegExp(`/student/assessments/${attemptId}$`));
  await expect(page.getByText(/Synthetic approved question/)).toBeVisible();
  await expect(page.getByText('Synthetic option A')).toBeVisible();
  await page.route('**/fixtures/phase05-*.svg', (route) => route.fulfill({
    status: 200,
    contentType: 'image/svg+xml',
    body: '<svg xmlns="http://www.w3.org/2000/svg" viewBox="0 0 20 20"><rect width="20" height="20" fill="blue"/></svg>',
  }));
  const visualQuestionIndex = session.questions.findIndex((question) =>
    question.visualAssets.length > 0 || question.passageTitle !== null,
  );
  expect(visualQuestionIndex).toBeGreaterThanOrEqual(0);
  await page.getByRole('button', {
    name: `Câu ${visualQuestionIndex + 1}, chưa trả lời`,
  }).click();
  await expect(page.getByRole('heading', { name: 'Synthetic scientific passage' })).toBeVisible();
  await expect(page.getByText('Synthetic shared stimulus for builder and player integration.'))
    .toBeVisible();
  for (const assetType of ['IMAGE', 'TABLE', 'CHART']) {
    await expect(page.locator(`figure[data-asset-type="${assetType}"] img`)).toBeVisible();
  }
  await page.setViewportSize({ width: 390, height: 844 });
  await expect(page.getByRole('heading', { name: 'Synthetic scientific passage' })).toBeVisible();
  await page.setViewportSize({ width: 1280, height: 900 });
  await page.getByRole('button', { name: 'Câu 1, chưa trả lời' }).click();

  const answerRequestPromise = page.waitForRequest((request) =>
    request.method() === 'POST'
    && request.url().endsWith(`/items/${itemId}/answer`),
  );
  await page.context().setOffline(true);
  await page.getByLabel('Synthetic option A').check();
  const answerRequest = await answerRequestPromise;
  const originalSave = answerRequest.postDataJSON() as {
    selectedOptionKeys: string[];
    answerText: string | null;
    expectedRevision: number;
    requestId: string;
  };
  await expect(page.getByRole('status')).toContainText('Mất kết nối');
  await page.getByRole('button', {
    name: `Câu ${session.questions.length}, chưa trả lời`,
  }).click();
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Nộp bài' }).click();
  await expect(page.locator('p[role="alert"]'))
    .toContainText('Có câu trả lời chưa lưu được');

  const recoveredAnswerResponse = page.waitForResponse((response) =>
    response.request().method() === 'POST'
    && response.url().endsWith(`/items/${itemId}/answer`),
  );
  await page.context().setOffline(false);
  expect((await recoveredAnswerResponse).status()).toBe(200);
  await expect(page.getByRole('status')).toHaveText('Đã lưu');

  const answerPath = `/api/assessments/student/assessments/attempts/${attemptId}/items/${itemId}/answer`;
  const retryResponse = await page.request.post(answerPath, { data: originalSave });
  expect(retryResponse.status()).toBe(200);
  expect(await retryResponse.json()).toMatchObject({ revision: 1, idempotentReplay: true });
  const staleResponse = await page.request.post(answerPath, {
    data: { ...originalSave, requestId: randomUUID() },
  });
  expect(staleResponse.status()).toBe(409);

  await page.getByRole('button', { name: /^Câu 1,/ }).click();
  const reviewPath = `/api/assessments/student/assessments/attempts/${attemptId}/items/${itemId}/review`;
  const reviewResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
    && new URL(response.url()).pathname === reviewPath,
  );
  await page.getByRole('button', { name: 'Đánh dấu xem lại' }).click();
  expect((await reviewResponsePromise).status()).toBe(200);
  await expect(page.getByRole('button', { name: 'Bỏ đánh dấu xem lại' })).toBeVisible();
  await page.reload();
  await expect(page.getByLabel('Synthetic option A')).toBeChecked();
  await expect(page.getByRole('button', { name: 'Bỏ đánh dấu xem lại' })).toBeVisible();

  const clearResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
    && response.url().endsWith(`/items/${itemId}/answer`),
  );
  await page.getByRole('button', { name: 'Xóa câu trả lời' }).click();
  expect((await clearResponsePromise).status()).toBe(200);
  await expect(page.getByRole('status')).toHaveText('Đã lưu');
  await expect(page.getByLabel('Synthetic option A')).not.toBeChecked();
  await page.reload();
  await expect(page.getByLabel('Synthetic option A')).not.toBeChecked();

  const finalSaveResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
    && response.url().endsWith(`/items/${itemId}/answer`),
  );
  await page.getByLabel('Synthetic option B').check();
  expect((await finalSaveResponsePromise).status()).toBe(200);
  await expect(page.getByRole('status')).toHaveText('Đã lưu');
  await page.getByRole('button', {
    name: `Câu ${session.questions.length}, chưa trả lời`,
  }).click();
  page.once('dialog', (dialog) => dialog.accept());
  await page.getByRole('button', { name: 'Nộp bài' }).click();
  await expect(page.getByRole('heading', { name: 'Bài làm đã kết thúc' })).toBeVisible();
  await expect(page.getByText(/đáp án và lời giải không được hiển thị/i)).toBeVisible();
  await expect(page.getByText(/Kết quả nội bộ: 0\/3 điểm/)).toBeVisible();

  const scoringPath = `/api/assessments/student/assessments/attempts/${attemptId}/scoring`;
  const scoringResponse = await page.request.get(scoringPath);
  expect(scoringResponse.status()).toBe(200);
  const scoring = await scoringResponse.json() as {
    run: {
      scoringRunId: number;
      attemptId: number;
      examId: number;
      examVersionId: number;
      testVersionId: number;
      taxonomyReleaseId: number | null;
      inputSha256: string;
      provenance: Record<string, unknown>;
      scoringVersion: string;
      analyticsVersion: string;
      rawScore: number;
      maxScore: number;
      accuracy: number;
      totalQuestions: number;
      answeredQuestions: number;
      correctQuestions: number;
      incorrectQuestions: number;
      skippedQuestions: number;
    };
    sections: Array<{ totalQuestions: number; incorrectQuestions: number; skippedQuestions: number }>;
    questions: Array<{ correct: boolean | null; skipped: boolean }>;
    skills: Array<{
      accuracy: number;
      masteryScore: number;
      evidenceCount: number;
      confidence: number;
      sufficientEvidence: boolean;
    }>;
  };
  expect(scoring.run).toMatchObject({
    attemptId,
    examId: expect.any(Number),
    examVersionId: expect.any(Number),
    testVersionId: expect.any(Number),
    inputSha256: expect.stringMatching(/^[a-f0-9]{64}$/),
    scoringVersion: 'objective-exact-v1',
    analyticsVersion: 'evidence-mean-v1',
    rawScore: 0,
    maxScore: 3,
    accuracy: 0,
    totalQuestions: 3,
    answeredQuestions: 1,
    correctQuestions: 0,
    incorrectQuestions: 1,
    skippedQuestions: 2,
  });
  expect(scoring.sections).toHaveLength(1);
  expect(scoring.sections[0]).toMatchObject({
    totalQuestions: 3,
    incorrectQuestions: 1,
    skippedQuestions: 2,
  });
  expect(scoring.questions.filter((question) => question.skipped)).toHaveLength(2);
  expect(scoring.skills).toEqual(expect.arrayContaining([
    expect.objectContaining({
      accuracy: 0,
      masteryScore: 0,
      evidenceCount: 1,
      confidence: 0.25,
      sufficientEvidence: false,
    }),
  ]));
  expect(JSON.stringify(scoring)).not.toMatch(/correct_answer|answer_key|explanation/i);

  const studentAnalyticsResponse = await page.request.get('/api/assessments/student/analytics');
  expect(studentAnalyticsResponse.status()).toBe(200);
  const studentAnalytics = await studentAnalyticsResponse.json() as {
    scoredAttempts: number;
    averageAccuracy: number;
    correctAnswers: number;
    incorrectAnswers: number;
    skippedAnswers: number;
    selectedExamId: number;
    selectedCohort: {
      examCode: string;
      examVersionId: number;
      testVersionId: number;
      taxonomyReleaseId: number | null;
      scoringVersion: string;
      analyticsVersion: string;
    };
    improvement: number | null;
    recentAttempts: Array<{
      attemptId: number;
      accuracy: number;
      rawScore: number;
      maxScore: number;
      examCode: string;
      testVersionId: number;
      scoringVersion: string;
      analyticsVersion: string;
    }>;
  };
  expect(studentAnalytics).toMatchObject({
    scoredAttempts: 1,
    averageAccuracy: 0,
    correctAnswers: 0,
    incorrectAnswers: 1,
    skippedAnswers: 2,
  });
  expect(studentAnalytics.recentAttempts).toEqual([
    expect.objectContaining({
      attemptId,
      accuracy: 0,
      rawScore: 0,
      maxScore: 3,
      examCode: 'TSA',
      testVersionId: scoring.run.testVersionId,
      scoringVersion: scoring.run.scoringVersion,
      analyticsVersion: scoring.run.analyticsVersion,
    }),
  ]);
  expect(studentAnalytics.selectedCohort).toMatchObject({
    examCode: 'TSA',
    testVersionId: scoring.run.testVersionId,
    scoringVersion: scoring.run.scoringVersion,
    analyticsVersion: scoring.run.analyticsVersion,
  });
  expect(studentAnalytics.improvement).toBeNull();
  const filteredAnalytics = await page.request.get(
    `/api/assessments/student/analytics?examId=${scoring.run.examId}&testVersionId=${scoring.run.testVersionId}`,
  );
  expect(filteredAnalytics.status()).toBe(200);
  expect(await filteredAnalytics.json()).toMatchObject({
    selectedExamId: scoring.run.examId,
    selectedCohort: { testVersionId: scoring.run.testVersionId },
  });
  const unavailableAnalyticsCohort = await page.request.get(
    `/api/assessments/student/analytics?examId=${scoring.run.examId}&testVersionId=9223372036854775807`,
  );
  expect(unavailableAnalyticsCohort.status()).toBe(404);

  const publishedHsaTestsResponse = await page.request.get('/api/assessments/student/assessments/tests');
  expect(publishedHsaTestsResponse.status()).toBe(200);
  const publishedHsaTests = await publishedHsaTestsResponse.json() as Array<{
    testVersionId: number;
    title: string;
    exam: string;
  }>;
  const hsaTest = publishedHsaTests.find((candidate) => candidate.title === hsaScienceTitle);
  expect(hsaTest).toMatchObject({ exam: 'HSA' });
  if (!hsaTest) throw new Error('Published HSA test fixture was not found in the student catalog.');
  const hsaStartResponse = await page.request.post(
    `/api/assessments/student/assessments/test-versions/${hsaTest.testVersionId}/attempts`,
  );
  expect(hsaStartResponse.status()).toBe(201);
  const hsaSession = await hsaStartResponse.json() as {
    attempt: { attemptId: number };
    questions: Array<{ testItemId: number }>;
  };
  const hsaItem = hsaSession.questions[0];
  const hsaAnswerResponse = await page.request.post(
    `/api/assessments/student/assessments/attempts/${hsaSession.attempt.attemptId}/items/${hsaItem.testItemId}/answer`,
    { data: { selectedOptionKeys: ['A'], answerText: null, expectedRevision: 0, requestId: randomUUID() } },
  );
  expect(hsaAnswerResponse.status()).toBe(200);
  expect(await hsaAnswerResponse.text()).not.toMatch(/correct_answer|answer_key|explanation/i);
  const hsaSubmit = await page.request.post(
    `/api/assessments/student/assessments/attempts/${hsaSession.attempt.attemptId}/submit`,
  );
  expect(hsaSubmit.status()).toBe(200);
  const hsaScoringResponse = await page.request.post(
    `/api/assessments/student/assessments/attempts/${hsaSession.attempt.attemptId}/scoring-runs`,
    { data: { requestId: randomUUID() } },
  );
  expect(hsaScoringResponse.status()).toBe(201);
  const hsaScoring = await hsaScoringResponse.json() as {
    run: { examId: number; testVersionId: number; scoringVersion: string; analyticsVersion: string };
  };
  const crossExamAnalyticsResponse = await page.request.get('/api/assessments/student/analytics');
  expect(crossExamAnalyticsResponse.status()).toBe(200);
  const crossExamAnalytics = await crossExamAnalyticsResponse.json() as {
    selectedCohort: { examCode: string; testVersionId: number };
    availableExams: Array<{ examCode: string }>;
  };
  expect(crossExamAnalytics.selectedCohort).toMatchObject({
    examCode: 'HSA',
    testVersionId: hsaScoring.run.testVersionId,
  });
  expect(crossExamAnalytics.availableExams.map((exam) => exam.examCode)).toEqual(
    expect.arrayContaining(['TSA', 'HSA']),
  );

  await page.goto('/student');
  await expect(page.getByLabel('Kỳ thi')).toHaveValue(String(hsaScoring.run.examId));
  await page.getByLabel('Kỳ thi').selectOption(String(scoring.run.examId));
  await page.getByRole('button', { name: 'Lọc kỳ thi' }).click();
  await expect(page.getByLabel('Kỳ thi')).toHaveValue(String(scoring.run.examId));
  await expect(page.getByLabel('Phiên bản bài thi')).toHaveValue([
    scoring.run.testVersionId,
    scoring.run.scoringVersion,
    scoring.run.analyticsVersion,
  ].join('|'));

  const idempotencyRequestId = randomUUID();
  const firstRunResponse = await page.request.post(
    `/api/assessments/student/assessments/attempts/${attemptId}/scoring-runs`,
    { data: { requestId: idempotencyRequestId } },
  );
  expect(firstRunResponse.status()).toBe(201);
  const firstRunId = (await firstRunResponse.json()).run.scoringRunId as number;
  expect(firstRunId).not.toBe(scoring.run.scoringRunId);
  const retryRunResponse = await page.request.post(
    `/api/assessments/student/assessments/attempts/${attemptId}/scoring-runs`,
    { data: { requestId: idempotencyRequestId } },
  );
  expect(retryRunResponse.status()).toBe(201);
  expect((await retryRunResponse.json()).run.scoringRunId).toBe(firstRunId);

  const teacherContext = await browser.newContext({ baseURL: 'http://127.0.0.1:3088' });
  const teacherPage = await teacherContext.newPage();
  try {
    await login(teacherPage, requiredFixture('PHASE05_E2E_TEACHER_EMAIL'));
    const teacherResult = await teacherPage.request.get(
      `/api/assessments/teacher/assessments/attempts/${attemptId}/scoring`,
    );
    expect(teacherResult.status()).toBe(200);
    expect((await teacherResult.json()).run.scoringRunId).toBe(firstRunId);

    const teacherAnalyticsResponse = await teacherPage.request.get('/api/assessments/teacher/analytics');
    expect(teacherAnalyticsResponse.status()).toBe(200);
    const teacherAnalytics = await teacherAnalyticsResponse.json() as {
      availableExams: Array<{ examCode: string }>;
    };
    expect(teacherAnalytics.availableExams.map((exam) => exam.examCode)).toEqual(
      expect.arrayContaining(['TSA', 'HSA']),
    );
    const teacherHsaAnalytics = await teacherPage.request.get(
      `/api/assessments/teacher/analytics?examId=${hsaScoring.run.examId}&testVersionId=${hsaScoring.run.testVersionId}&scoringVersion=${hsaScoring.run.scoringVersion}&analyticsVersion=${hsaScoring.run.analyticsVersion}`,
    );
    expect(teacherHsaAnalytics.status()).toBe(200);
    expect(await teacherHsaAnalytics.json()).toMatchObject({
      scoredAttempts: 1,
      selectedCohort: { testVersionId: hsaScoring.run.testVersionId },
    });
    const teacherTsaAnalytics = await teacherPage.request.get(
      `/api/assessments/teacher/analytics?examId=${scoring.run.examId}&testVersionId=${scoring.run.testVersionId}&scoringVersion=${scoring.run.scoringVersion}&analyticsVersion=${scoring.run.analyticsVersion}`,
    );
    expect(teacherTsaAnalytics.status()).toBe(200);
    expect(await teacherTsaAnalytics.json()).toMatchObject({
      scoredAttempts: 1,
      averageAccuracy: 0,
      incorrectAnswers: 1,
      skippedAnswers: 2,
    });
    await teacherPage.goto('/teacher');
    await teacherPage.getByLabel('Kỳ thi').selectOption(String(hsaScoring.run.examId));
    await teacherPage.getByRole('button', { name: 'Lọc kỳ thi' }).click();
    await expect(teacherPage.getByLabel('Kỳ thi')).toHaveValue(String(hsaScoring.run.examId));
    await expect(teacherPage.getByText(/1 lượt đã chấm trong cùng/)).toBeVisible();
    await teacherPage.getByLabel('Kỳ thi').selectOption(String(scoring.run.examId));
    await teacherPage.getByRole('button', { name: 'Lọc kỳ thi' }).click();
    await expect(teacherPage.getByLabel('Kỳ thi')).toHaveValue(String(scoring.run.examId));
    await expect(teacherPage.getByText(/1 lượt đã chấm trong cùng/)).toBeVisible();
    await expect(teacherPage.getByText(/độ chính xác 0%/)).toBeVisible();
  } finally {
    await teacherContext.close();
  }

  const unrelatedTeacherContext = await browser.newContext({ baseURL: 'http://127.0.0.1:3088' });
  const unrelatedTeacher = await unrelatedTeacherContext.newPage();
  try {
    await login(unrelatedTeacher, requiredFixture('PHASE06_E2E_UNRELATED_TEACHER_EMAIL'));
    const outOfScopeResult = await unrelatedTeacher.request.get(
      `/api/assessments/teacher/assessments/attempts/${attemptId}/scoring`,
    );
    expect(outOfScopeResult.status()).toBe(404);
    const outOfScopeAnalytics = await unrelatedTeacher.request.get(
      '/api/assessments/teacher/analytics',
    );
    expect(outOfScopeAnalytics.status()).toBe(200);
    expect(await outOfScopeAnalytics.json()).toMatchObject({
      scoredAttempts: 0,
      averageAccuracy: 0,
      correctAnswers: 0,
      incorrectAnswers: 0,
      skippedAnswers: 0,
    });
  } finally {
    await unrelatedTeacherContext.close();
  }

  const forbiddenTeacherAnalytics = await page.request.get('/api/assessments/teacher/analytics');
  expect(forbiddenTeacherAnalytics.status()).toBe(403);
  const unauthenticatedAnalytics = await page.request.get(
    `http://127.0.0.1:18081/api/student/analytics`,
  );
  expect(unauthenticatedAnalytics.status()).toBe(401);
  const invalidJwtAnalytics = await page.request.get(
    `http://127.0.0.1:18081/api/student/analytics`,
    { headers: { Authorization: 'Bearer invalid-token' } },
  );
  expect(invalidJwtAnalytics.status()).toBe(401);
  const expiredJwtAnalytics = await page.request.get(
    'http://127.0.0.1:18081/api/student/analytics',
    { headers: { Authorization: `Bearer ${expiredTestToken()}` } },
  );
  expect(expiredJwtAnalytics.status()).toBe(401);

  const assessmentCatalog = await page.request.get('/api/assessments/student/assessments/tests');
  expect(assessmentCatalog.status()).toBe(200);
  const catalogEntries = await assessmentCatalog.json() as Array<{
    title: string;
    testVersionId: number;
  }>;
  const scoringAssessment = catalogEntries.find((entry) => entry.title === diagnosticTitle);
  expect(scoringAssessment).toBeDefined();
  const unsubmittedAttemptResponse = await page.request.post(
    `/api/assessments/student/assessments/test-versions/${scoringAssessment?.testVersionId}/attempts`,
  );
  expect(unsubmittedAttemptResponse.status()).toBe(201);
  const unsubmittedAttempt = await unsubmittedAttemptResponse.json() as {
    attempt: { attemptId: number };
  };
  const prematureScoringResponse = await page.request.post(
    `/api/assessments/student/assessments/attempts/${unsubmittedAttempt.attempt.attemptId}/scoring-runs`,
    { data: { requestId: randomUUID() } },
  );
  expect(prematureScoringResponse.status()).toBe(409);

  const selectedTsaCohort = encodeURIComponent([
    scoring.run.testVersionId,
    scoring.run.scoringVersion,
    scoring.run.analyticsVersion,
  ].join('|'));
  const dashboardResponse = await page.goto(
    `/student?examId=${scoring.run.examId}&cohort=${selectedTsaCohort}`,
  );
  expect(dashboardResponse?.status()).toBe(200);
  await expect(page.getByText('Tiến độ gần đây')).toBeVisible();
  await expect(page.getByText(/0\/3 điểm · 0%/)).toBeVisible();

  await renderStructuredGroup(page, tableGroupTitle, 'TABLE');
  await renderStructuredGroup(page, chartGroupTitle, 'CHART');

  const duplicateSubmit = await page.request.post(
    `/api/assessments/student/assessments/attempts/${attemptId}/submit`,
  );
  expect(duplicateSubmit.status()).toBe(200);
  expect(await duplicateSubmit.json()).toMatchObject({ attemptId, status: 'SUBMITTED' });

  await page.goto('/student/assessments');
  const timeoutAssessment = page.locator('article').filter({ hasText: timeoutTitle }).first();
  await timeoutAssessment.getByRole('button', { name: 'Xem hướng dẫn' }).click();
  const timeoutStartResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
    && /\/api\/assessments\/student\/assessments\/test-versions\/\d+\/attempts$/.test(response.url()),
  );
  await page.getByRole('button', { name: 'Bắt đầu làm bài' }).click();
  const timeoutStartResponse = await timeoutStartResponsePromise;
  expect(timeoutStartResponse.status()).toBe(201);
  const timeoutSession = await timeoutStartResponse.json() as {
    attempt: { attemptId: number; remainingSeconds: number };
  };
  expect(timeoutSession.attempt.remainingSeconds).toBeLessThanOrEqual(60);
  const autoSubmitResponsePromise = page.waitForResponse((response) =>
    response.request().method() === 'POST'
    && response.url().endsWith(`/attempts/${timeoutSession.attempt.attemptId}/submit`),
    { timeout: 75_000 },
  );
  const autoSubmitResponse = await autoSubmitResponsePromise;
  expect(autoSubmitResponse.status()).toBe(200);
  expect(await autoSubmitResponse.json()).toMatchObject({
    attemptId: timeoutSession.attempt.attemptId,
    status: 'AUTO_SUBMITTED',
    autoSubmitted: true,
  });
  await expect(page.getByRole('heading', { name: 'Bài làm đã kết thúc' })).toBeVisible();

  const secondStudent = await page.request.post('/api/auth/register', {
    data: {
      fullName: 'Phase 05 Other Student',
      email: `phase05-other-${randomUUID()}@example.test`,
      phoneNumber: null,
      password: 'phase05-isolated-test-password',
    },
  });
  expect(secondStudent.status()).toBe(201);
  const unauthorizedAttempt = await page.request.get(
    `/api/assessments/student/assessments/attempts/${attemptId}`,
  );
  expect(unauthorizedAttempt.status()).toBe(404);
  const unauthorizedScoring = await page.request.get(scoringPath);
  expect(unauthorizedScoring.status()).toBe(404);

  console.log(`PHASE06_SCORING_E2E=${JSON.stringify({
    attemptId,
    scoringRunId: scoring.run.scoringRunId,
    studentEmail: email,
    scoring: scoring.run,
    analytics: studentAnalytics,
  })}`);
});
