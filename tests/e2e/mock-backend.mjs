import { createServer } from 'node:http';

const port = Number(process.env.PORT ?? 8187);
const student = {
  id: 90001,
  userId: 90001,
  email: 'sandbox.student@example.test',
  fullName: 'Sandbox Student',
  username: 'sandbox-student',
  role: 'STUDENT',
  status: 'ACTIVE',
  roles: ['STUDENT'],
};
const test = {
  testVersionId: 101,
  testId: 100,
  title: 'Synthetic TSA Mock Test',
  description: 'Fixture dùng riêng cho browser test.',
  exam: 'TSA',
  testType: 'MOCK',
  durationMinutes: 10,
  totalQuestions: 1,
  taxonomyVersion: 'SANDBOX-1',
  activeAttemptId: null,
};
const session = {
  attempt: {
    attemptId: 7,
    status: 'IN_PROGRESS',
    startedAt: new Date().toISOString(),
    endsAt: new Date(Date.now() + 600_000).toISOString(),
    remainingSeconds: 600,
    totalQuestions: 1,
    answeredQuestions: 0,
    markedForReview: 0,
  },
  title: test.title,
  exam: test.exam,
  questions: [{
    testItemId: 1101,
    order: 1,
    questionVersionId: 2001,
    questionType: 'SINGLE_CHOICE',
    responseMode: 'SINGLE_OPTION',
    stem: 'Synthetic browser-test question: choose option A.',
    cognitiveLevel: 'M1',
    estimatedDifficulty: 0.5,
    sectionName: 'Sandbox section',
    passageTitle: null,
    passageContent: null,
    options: [
      { key: 'A', text: 'Synthetic choice A' },
      { key: 'B', text: 'Synthetic choice B' },
    ],
    visualAssets: [],
    selectedOptionKeys: [],
    answerText: null,
    revision: 0,
    markedForReview: false,
    groupStimulus: {
      groupVersionId: 3001,
      title: 'Synthetic shared data table',
      stimulusType: 'TABLE',
      stimulus: {
        columns: [{ key: 'sample', label: 'Sample' }, { key: 'value', label: 'Value' }],
        rows: [['A', 12], ['B', 18]],
      },
      itemOrder: 1,
      itemCount: 2,
    },
  }],
};

function send(response, status, payload) {
  response.writeHead(status, { 'Content-Type': 'application/json' });
  response.end(JSON.stringify(payload));
}

const server = createServer(async (request, response) => {
  const url = new URL(request.url ?? '/', `http://127.0.0.1:${port}`);
  if (url.pathname === '/health') {
    send(response, 200, { status: 'UP' });
    return;
  }

  if (url.pathname === '/api/auth/login' && request.method === 'POST') {
    send(response, 200, {
      accessToken: 'sandbox-e2e-token',
      tokenType: 'Bearer',
      expiresIn: 3600,
      user: student,
    });
    return;
  }

  if (request.headers.authorization !== 'Bearer sandbox-e2e-token') {
    send(response, 401, { error: 'Authentication required.' });
    return;
  }

  if (url.pathname === '/api/auth/me' && request.method === 'GET') {
    send(response, 200, student);
  } else if (url.pathname === '/api/student/dashboard' && request.method === 'GET') {
    send(response, 200, {
      attempts: { total: 1, completed: 0, inProgress: 1 },
      averageAccuracyPercent: 0,
    });
  } else if (url.pathname === '/api/student/analytics' && request.method === 'GET') {
    send(response, 200, {
      scoredAttempts: 1,
      averageAccuracy: 0.75,
      correctAnswers: 3,
      incorrectAnswers: 1,
      skippedAnswers: 0,
      averageTimeSeconds: 120,
      selectedExamId: 1,
      availableExams: [{
        examId: 1,
        examCode: 'TSA',
        examName: 'Synthetic TSA',
        latestScoredAt: new Date().toISOString(),
      }],
      selectedCohort: {
        examId: 1,
        examCode: 'TSA',
        examName: 'Synthetic TSA',
        examVersionId: 2,
        examVersionCode: 'SANDBOX-1',
        testVersionId: 101,
        testTitle: 'Synthetic TSA Mock Test',
        taxonomyReleaseId: 5,
        taxonomyVersion: 'SANDBOX-1',
        scoringVersion: 'objective-exact-v1',
        analyticsVersion: 'evidence-mean-v1',
        latestScoredAt: new Date().toISOString(),
      },
      availableCohorts: [{
        examId: 1,
        examCode: 'TSA',
        examName: 'Synthetic TSA',
        examVersionId: 2,
        examVersionCode: 'SANDBOX-1',
        testVersionId: 101,
        testTitle: 'Synthetic TSA Mock Test',
        taxonomyReleaseId: 5,
        taxonomyVersion: 'SANDBOX-1',
        scoringVersion: 'objective-exact-v1',
        analyticsVersion: 'evidence-mean-v1',
        latestScoredAt: new Date().toISOString(),
      }],
      improvement: null,
      recentAttempts: [{
        attemptId: 7,
        scoredAt: new Date().toISOString(),
        accuracy: 0.75,
        rawScore: 3,
        maxScore: 4,
        examId: 1,
        examCode: 'TSA',
        examVersionId: 2,
        examVersionCode: 'SANDBOX-1',
        testVersionId: 101,
        taxonomyReleaseId: 5,
        taxonomyVersion: 'SANDBOX-1',
        scoringVersion: 'objective-exact-v1',
        analyticsVersion: 'evidence-mean-v1',
      }],
      skills: [],
      competencies: [],
    });
  } else if (url.pathname === '/api/student/assessments/tests' && request.method === 'GET') {
    send(response, 200, [test]);
  } else if (
    url.pathname === '/api/student/assessments/test-versions/101/attempts' &&
    request.method === 'POST'
  ) {
    send(response, 201, { attempt: { attemptId: session.attempt.attemptId } });
  } else if (
    url.pathname === `/api/student/assessments/attempts/${session.attempt.attemptId}` &&
    request.method === 'GET'
  ) {
    send(response, 200, session);
  } else if (
    url.pathname.endsWith('/items/1101/answer') &&
    request.method === 'POST'
  ) {
    const body = await readJson(request);
    send(response, 200, {
      testItemId: 1101,
      revision: body.expectedRevision + 1,
      markedForReview: false,
      idempotentReplay: false,
    });
  } else if (
    url.pathname.endsWith('/items/1101/review') &&
    request.method === 'POST'
  ) {
    const body = await readJson(request);
    send(response, 200, {
      testItemId: 1101,
      revision: body.expectedRevision + 1,
      markedForReview: body.markedForReview,
      idempotentReplay: false,
    });
  } else if (
    url.pathname === `/api/student/assessments/attempts/${session.attempt.attemptId}/submit` &&
    request.method === 'POST'
  ) {
    send(response, 200, {
      attemptId: session.attempt.attemptId,
      status: 'SUBMITTED',
      submittedAt: new Date().toISOString(),
      autoSubmitted: false,
      answeredQuestions: 1,
      totalQuestions: 1,
    });
  } else {
    send(response, 404, { error: 'Unknown sandbox route.' });
  }
});

function readJson(request) {
  return new Promise((resolve, reject) => {
    let body = '';
    request.setEncoding('utf8');
    request.on('data', (chunk) => { body += chunk; });
    request.on('end', () => {
      try {
        resolve(JSON.parse(body));
      } catch (error) {
        reject(error);
      }
    });
    request.on('error', reject);
  });
}

server.listen(port, '127.0.0.1');
