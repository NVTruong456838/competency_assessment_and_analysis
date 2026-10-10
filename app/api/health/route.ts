import { NextResponse } from 'next/server';

export async function GET() {
  try {
    const response = await fetch(`${backendUrl()}/api/health`, {
      cache: 'no-store',
      signal: AbortSignal.timeout(5_000),
    });
    const payload: unknown = await response.json();
    return NextResponse.json(payload, { status: response.status });
  } catch (error) {
    console.error('Không thể kiểm tra PostgreSQL qua Spring Boot.', error);
    return NextResponse.json(
      { status: 'error', message: 'Không thể kết nối tới backend hoặc database.' },
      { status: 503 },
    );
  }
}

function backendUrl(): string {
  return (process.env.SPRING_API_URL ?? 'http://localhost:8081').replace(/\/+$/, '');
}