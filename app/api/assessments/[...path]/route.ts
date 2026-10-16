import { cookies } from 'next/headers';
import { NextResponse } from 'next/server';

type RouteContext = {
  params: Promise<{ path: string[] }>;
};

export async function GET(request: Request, context: RouteContext) {
  return proxyAssessmentRequest(request, context);
}

export async function POST(request: Request, context: RouteContext) {
  return proxyAssessmentRequest(request, context);
}

export async function DELETE(request: Request, context: RouteContext) {
  return proxyAssessmentRequest(request, context);
}

async function proxyAssessmentRequest(request: Request, context: RouteContext) {
  const session = (await cookies()).get('tyl_session')?.value;
  if (!session) {
    return NextResponse.json({ error: 'Bạn cần đăng nhập để tiếp tục.' }, { status: 401 });
  }

  const { path } = await context.params;
  const upstreamUrl = new URL(
    `/api/${path.map(encodeURIComponent).join('/')}`,
    process.env.SPRING_API_URL ?? 'http://localhost:8081',
  );
  upstreamUrl.search = new URL(request.url).search;

  const headers = new Headers({ Authorization: `Bearer ${session}` });
  const contentType = request.headers.get('content-type');
  if (contentType) headers.set('Content-Type', contentType);

  try {
    const upstream = await fetch(upstreamUrl, {
      method: request.method,
      headers,
      body: request.method === 'GET' ? undefined : await request.text(),
      cache: 'no-store',
      signal: AbortSignal.timeout(20_000),
    });
    return new Response(upstream.body, {
      status: upstream.status,
      headers: {
        'Content-Type': upstream.headers.get('content-type') ?? 'application/json',
        'Cache-Control': 'no-store',
      },
    });
  } catch (error) {
    console.error('Spring Boot assessment request failed.', error);
    return NextResponse.json(
      { error: 'Dịch vụ đánh giá tạm thời không khả dụng. Vui lòng thử lại.' },
      { status: 503 },
    );
  }
}
