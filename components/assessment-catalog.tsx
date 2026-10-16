'use client';

import Link from 'next/link';
import { useRouter } from 'next/navigation';
import { useEffect, useState } from 'react';

type AvailableTest = {
  testVersionId: number;
  testId: number;
  title: string;
  description: string | null;
  exam: string;
  testType: string;
  durationMinutes: number;
  totalQuestions: number;
  taxonomyVersion: string;
  activeAttemptId: number | null;
};

export function AssessmentCatalog() {
  const router = useRouter();
  const [tests, setTests] = useState<AvailableTest[]>([]);
  const [error, setError] = useState('');
  const [loading, setLoading] = useState(true);
  const [instructionsFor, setInstructionsFor] = useState<number | null>(null);

  useEffect(() => {
    fetch('/api/assessments/student/assessments/tests', { cache: 'no-store' })
      .then(async (response) => {
        const payload: unknown = await response.json();
        if (!response.ok) throw new Error(errorMessage(payload));
        if (!Array.isArray(payload)) throw new Error('Danh sách bài thi không hợp lệ.');
        setTests(payload as AvailableTest[]);
      })
      .catch((cause: unknown) => {
        setError(cause instanceof Error ? cause.message : 'Không thể tải danh sách bài thi.');
      })
      .finally(() => setLoading(false));
  }, []);

  async function start(testVersionId: number) {
    setError('');
    try {
      const response = await fetch(
        `/api/assessments/student/assessments/test-versions/${testVersionId}/attempts`,
        { method: 'POST' },
      );
      const payload: unknown = await response.json();
      if (!response.ok) throw new Error(errorMessage(payload));
      const session = payload as { attempt: { attemptId: number } };
      router.push(`/student/assessments/${session.attempt.attemptId}`);
    } catch (cause) {
      setError(cause instanceof Error ? cause.message : 'Không thể bắt đầu bài thi.');
    }
  }

  return (
    <section className="space-y-5">
      {error && (
        <p role="alert" className="rounded-xl border border-red-200 bg-red-50 p-4 text-sm text-red-800">
          {error}
        </p>
      )}
      {loading ? (
        <p className="text-sm text-slate-600">Đang tải danh sách bài đánh giá...</p>
      ) : tests.length === 0 ? (
        <div className="rounded-2xl border border-dashed border-slate-300 bg-white p-8 text-center">
          <h2 className="font-semibold text-slate-900">Chưa có bài thi được phát hành</h2>
          <p className="mt-2 text-sm text-slate-600">
            Các bài thi thử chỉ xuất hiện sau khi Test Version đã qua publication gate.
          </p>
        </div>
      ) : (
        <div className="grid gap-4 md:grid-cols-2">
          {tests.map((test) => (
            <article
              key={test.testVersionId}
              className="rounded-2xl border border-blue-100 bg-white p-6 shadow-sm"
            >
              <p className="text-xs font-semibold uppercase tracking-wide text-blue-700">
                {test.exam} · {test.testType}
              </p>
              <h2 className="mt-2 text-lg font-semibold text-blue-950">{test.title}</h2>
              <p className="mt-2 min-h-10 text-sm text-slate-600">
                {test.description || 'Bài đánh giá theo cấu hình đã được phát hành.'}
              </p>
              <p className="mt-4 text-sm text-slate-600">
                {test.totalQuestions} câu · {test.durationMinutes} phút · Taxonomy{' '}
                {test.taxonomyVersion}
              </p>
              {test.activeAttemptId ? (
                <Link
                  href={`/student/assessments/${test.activeAttemptId}`}
                  className="mt-5 inline-flex rounded-xl bg-blue-700 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-800"
                >
                  Tiếp tục làm bài
                </Link>
              ) : (
                <button
                  type="button"
                  onClick={() => setInstructionsFor(test.testVersionId)}
                  className="mt-5 rounded-xl bg-blue-700 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-800"
                >
                  Xem hướng dẫn
                </button>
              )}
            </article>
          ))}
        </div>
      )}
      {instructionsFor !== null && (
        <section aria-labelledby="assessment-instructions-title" className="rounded-2xl border border-blue-200 bg-blue-50 p-6">
          <h2 id="assessment-instructions-title" className="font-semibold text-blue-950">Hướng dẫn làm bài</h2>
          <ul className="mt-3 list-inside list-disc space-y-2 text-sm leading-6 text-slate-700">
            <li>Đồng hồ và thời hạn làm bài do máy chủ xác định.</li>
            <li>Phản hồi được tự động lưu; khi mất kết nối ngắn, ứng dụng sẽ thử gửi lại.</li>
            <li>Bạn có thể điều hướng câu hỏi và đánh dấu câu cần xem lại.</li>
            <li>Không thể thay đổi đáp án sau khi nộp hoặc hết giờ.</li>
          </ul>
          <div className="mt-5 flex gap-3">
            <button type="button" onClick={() => setInstructionsFor(null)} className="rounded-xl border border-slate-300 px-4 py-2 text-sm">
              Quay lại
            </button>
            <button
              type="button"
              onClick={() => void start(instructionsFor)}
              className="rounded-xl bg-blue-700 px-4 py-2 text-sm font-semibold text-white"
            >
              Bắt đầu làm bài
            </button>
          </div>
        </section>
      )}
    </section>
  );
}

function errorMessage(value: unknown): string {
  if (value && typeof value === 'object' && 'message' in value && typeof value.message === 'string') {
    return value.message;
  }
  if (value && typeof value === 'object' && 'error' in value && typeof value.error === 'string') {
    return value.error;
  }
  return 'Yêu cầu không thành công.';
}
