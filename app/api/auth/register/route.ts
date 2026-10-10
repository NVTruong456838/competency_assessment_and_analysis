import { NextResponse } from 'next/server';
import { proxyAuthenticationRequest } from '@/lib/spring-api';

export async function POST(request: Request) {
  let body: unknown;
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ error: 'Dữ liệu gửi lên không hợp lệ.' }, { status: 400 });
  }

  if (!body || typeof body !== 'object') {
    return NextResponse.json({ error: 'Dữ liệu gửi lên không hợp lệ.' }, { status: 400 });
  }

  return proxyAuthenticationRequest('/api/auth/register', body, 201);
}
