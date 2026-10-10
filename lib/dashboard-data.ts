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

export async function getDashboardData<T>(role: Role): Promise<T> {
  const cookieStore = await cookies();
  const token = cookieStore.get(SESSION_COOKIE_NAME)?.value;
  if (!token) {
    throw new Error('Không tìm thấy phiên đăng nhập để tải dashboard.');
  }

  let response: Response;
  try {
    response = await fetch(`${backendUrl()}/api/${role.toLowerCase()}/dashboard`, {
      headers: { Authorization: `Bearer ${token}` },
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

function backendUrl(): string {
  return (process.env.SPRING_API_URL ?? 'http://localhost:8081').replace(/\/+$/, '');
}
