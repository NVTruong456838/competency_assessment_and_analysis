import { NextResponse } from 'next/server';
import type { SessionUser } from '@/lib/auth';
import type { Role } from '@/lib/user-types';

type AuthResponse = {
  accessToken: string;
  user: SessionUser;
};

const sessionCookieName = 'tyl_session';
const sessionMaxAgeSeconds = 60 * 60 * 8;

export async function proxyAuthenticationRequest(
  path: '/api/auth/login' | '/api/auth/register',
  body: object,
  successStatus: 200 | 201,
): Promise<NextResponse> {
  let upstream: Response;
  try {
    upstream = await fetch(`${backendUrl()}${path}`, {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
      body: JSON.stringify(body),
      cache: 'no-store',
      signal: AbortSignal.timeout(10_000),
    });
  } catch (error) {
    console.error(`Spring Boot authentication request failed (${path}).`, error);
    return NextResponse.json(
      { error: 'Dịch vụ xác thực tạm thời không khả dụng. Vui lòng thử lại sau.' },
      { status: 503 },
    );
  }

  const payload: unknown = await upstream.json().catch(() => null);
  if (!upstream.ok) {
    const message =
      payload && typeof payload === 'object' && 'error' in payload &&
      typeof payload.error === 'string'
        ? payload.error
        : 'Yêu cầu xác thực không thành công.';
    return NextResponse.json({ error: message }, { status: upstream.status });
  }

  if (!isAuthResponse(payload)) {
    console.error(`Spring Boot returned an invalid authentication response (${path}).`);
    return NextResponse.json(
      { error: 'Phản hồi xác thực từ máy chủ không hợp lệ.' },
      { status: 502 },
    );
  }

  const response = NextResponse.json(
    {
      ok: true,
      role: payload.user.role.toLowerCase(),
      name: payload.user.fullName,
    },
    { status: successStatus },
  );
  response.cookies.set(sessionCookieName, payload.accessToken, cookieOptions());
  return response;
}

function isAuthResponse(value: unknown): value is AuthResponse {
  if (!value || typeof value !== 'object') return false;
  const candidate = value as Partial<AuthResponse>;
  const user = candidate.user;
  return (
    typeof candidate.accessToken === 'string' &&
    !!user &&
    Number.isSafeInteger(user.id) &&
    (user.email === null || typeof user.email === 'string') &&
    typeof user.fullName === 'string' &&
    typeof user.username === 'string' &&
    isRole(user.role)
  );
}

function isRole(value: unknown): value is Role {
  return value === 'STUDENT' || value === 'TEACHER' || value === 'ADMIN';
}

function cookieOptions() {
  return {
    httpOnly: true,
    sameSite: 'lax' as const,
    secure: process.env.NODE_ENV === 'production',
    path: '/',
    maxAge: sessionMaxAgeSeconds,
  };
}

function backendUrl(): string {
  return (process.env.SPRING_API_URL ?? 'http://localhost:8081').replace(/\/+$/, '');
}
