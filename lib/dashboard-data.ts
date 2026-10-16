import { cookies } from 'next/headers';
import type { Role } from '@/lib/user-types';
import { SESSION_COOKIE_NAME } from '@/lib/auth';

export type StudentDashboardData = {
  attempts: {
    total: number;
    completed: number;
    inProgress: number;
  };
  averageAccuracyPercent: number;
};

export type TeacherDashboardData = {
  classes: {
    total: number;
    students: number;
  };
  tests: {
    total: number;
    published: number;
  };
};

export type AdminDashboardData = {
  users: {
    total: number;
    active: number;
    students: number;
    teachers: number;
    admins: number;
  };
  content: {
    classes: number;
    tests: number;
    publishedTests: number;
    questions: number;
  };
};

export type AnalyticsSkill = {
  skillId: number;
  skillName: string;
  accuracy: number;
  masteryScore: number;
  evidenceCount: number;
  confidence: number;
  averageTimeSeconds: number | null;
  sufficientEvidence: boolean;
};

export type AnalyticsCompetency = {
  competencyId: number;
  competencyName: string;
  accuracy: number;
  masteryScore: number;
  evidenceCount: number;
  confidence: number;
  sufficientEvidence: boolean;
};

export type AnalyticsExam = {
  examId: number;
  examCode: string;
  examName: string;
  latestScoredAt: string;
};

export type AnalyticsCohort = {
  examId: number;
  examCode: string;
  examName: string;
  examVersionId: number;
  examVersionCode: string;
  testVersionId: number;
  testTitle: string;
  taxonomyReleaseId: number | null;
  taxonomyVersion: string | null;
  scoringVersion: string;
  analyticsVersion: string;
  latestScoredAt: string;
};

export type StudentAnalyticsData = {
  scoredAttempts: number;
  averageAccuracy: number;
  correctAnswers: number;
  incorrectAnswers: number;
  skippedAnswers: number;
  averageTimeSeconds: number | null;
  selectedExamId: number | null;
  availableExams: AnalyticsExam[];
  selectedCohort: AnalyticsCohort | null;
  availableCohorts: AnalyticsCohort[];
  improvement: number | null;
  recentAttempts: {
    attemptId: number;
    scoredAt: string;
    accuracy: number | null;
    rawScore: number;
    maxScore: number;
    examId: number;
    examCode: string;
    examVersionId: number;
    examVersionCode: string;
    testVersionId: number;
    taxonomyReleaseId: number | null;
    taxonomyVersion: string | null;
    scoringVersion: string;
    analyticsVersion: string;
  }[];
  skills: AnalyticsSkill[];
  competencies: AnalyticsCompetency[];
};

export type TeacherAnalyticsData = Omit<StudentAnalyticsData, 'averageTimeSeconds'>;

export async function getDashboardData<T>(role: Role): Promise<T> {
  const cookieStore = await cookies();
  const token = cookieStore.get(SESSION_COOKIE_NAME)?.value;
  if (!token) {
    throw new Error('Không tìm thấy phiên đăng nhập để tải dashboard.');
  }

  let response: Response;
  try {
    response = await fetch(`${backendUrl()}/api/${role.toLowerCase()}/dashboard`, {
      headers: { Authorization: 'Bearer ' + token },
      cache: 'no-store',
      signal: AbortSignal.timeout(10_000),
    });
  } catch (error) {
    console.error(`Không thể tải dashboard cho role ${role}.`, error);
    throw new Error('Không thể kết nối dịch vụ dashboard. Vui lòng thử lại sau.');
  }

  if (response.status === 401 || response.status === 403) {
    throw new Error('Phiên đăng nhập không được phép xem dashboard này.');
  }
  if (!response.ok) {
    console.error(`Dashboard request for ${role} failed with HTTP ${response.status}.`);
    throw new Error('Không thể tải dữ liệu dashboard.');
  }

  return (await response.json()) as T;
}

export async function getAnalyticsData<T>(
  role: 'STUDENT' | 'TEACHER',
  filters: {
    examId?: string;
    examVersionId?: string;
    testVersionId?: string;
    scoringVersion?: string;
    analyticsVersion?: string;
  } = {},
): Promise<T> {
  const cookieStore = await cookies();
  const token = cookieStore.get(SESSION_COOKIE_NAME)?.value;
  if (!token) throw new Error('Không tìm thấy phiên đăng nhập để tải phân tích.');

  let response: Response;
  try {
    const query = new URLSearchParams();
    if (filters.examId) query.set('examId', filters.examId);
    if (filters.examVersionId) query.set('examVersionId', filters.examVersionId);
    if (filters.testVersionId) query.set('testVersionId', filters.testVersionId);
    if (filters.scoringVersion) query.set('scoringVersion', filters.scoringVersion);
    if (filters.analyticsVersion) query.set('analyticsVersion', filters.analyticsVersion);
    const suffix = query.size ? `?${query.toString()}` : '';
    response = await fetch(`${backendUrl()}/api/${role.toLowerCase()}/analytics${suffix}`, {
      headers: { Authorization: 'Bearer ' + token },
      cache: 'no-store',
      signal: AbortSignal.timeout(10_000),
    });
  } catch (error) {
    console.error(`Không thể tải phân tích cho role ${role}.`, error);
    throw new Error('Không thể kết nối dịch vụ phân tích. Vui lòng thử lại sau.');
  }

  if (response.status === 401 || response.status === 403) {
    throw new Error('Phiên đăng nhập không được phép xem dữ liệu phân tích này.');
  }
  if (!response.ok) {
    console.error(`Analytics request for ${role} failed with HTTP ${response.status}.`);
    throw new Error('Không thể tải dữ liệu phân tích.');
  }
  return (await response.json()) as T;
}

function backendUrl(): string {
  return (process.env.SPRING_API_URL ?? 'http://localhost:8081').replace(/\/+$/, '');
}
