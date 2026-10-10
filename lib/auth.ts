import { cookies } from 'next/headers';
import { redirect } from 'next/navigation';
import type { Role } from './user-types';

export type SessionUser = {
  id: number;
  email: string;
  fullName: string;
  role: Role;
  username: string;
};

export const SESSION_COOKIE_NAME = 'tyl_session';

export async function getSessionUser(): Promise<SessionUser | null> {
  const cookieStore = await cookies();
  const token = cookieStore.get(SESSION_COOKIE_NAME)?.value;
  if (!token) return null;

  const response = await fetch(`${backendUrl()}/api/auth/me`, {
    headers: { Authorization: `Bearer ${token}` },
    cache: 'no-store',
    signal: AbortSignal.timeout(5_000),
  });
  if (response.status === 401) return null;
  if (!response.ok) {
    throw new Error(`Không xác minh được phiên đăng nhập (HTTP ${response.status}).`);
  }

  const user: unknown = await response.json();
  if (!isSessionUser(user)) {
    throw new Error('Backend trả về hồ sơ phiên đăng nhập không hợp lệ.');
  }
  return user;
}

export async function requireRole(role: Role): Promise<SessionUser> {
  const user = await getSessionUser();

  if (!user || user.role !== role) {
    redirect('/login');
  }

  return user;
}

export async function getRoleDashboardPath(role: Role): Promise<string> {
  const rolePaths: Record<Role, string> = {
    STUDENT: '/student',
    TEACHER: '/teacher',
    ADMIN: '/admin',
  };

  return rolePaths[role];
}

function isSessionUser(value: unknown): value is SessionUser {
  if (!value || typeof value !== 'object') return false;
  const user = value as Partial<SessionUser>;
  return (
    Number.isSafeInteger(user.id) &&
    typeof user.email === 'string' &&
    typeof user.fullName === 'string' &&
    typeof user.username === 'string' &&
    (user.role === 'STUDENT' || user.role === 'TEACHER' || user.role === 'ADMIN')
  );
}

function backendUrl(): string {
  return (process.env.SPRING_API_URL ?? 'http://localhost:8081').replace(/\/+$/, '');
}
