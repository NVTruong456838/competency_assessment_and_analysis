import { DashboardMetrics } from '@/components/dashboard-metrics';
import { RoleShell } from '@/components/role-shell';
import {
  getAnalyticsData,
  getDashboardData,
  type TeacherAnalyticsData,
  type TeacherDashboardData,
} from '@/lib/dashboard-data';
import { requireRole } from '@/lib/auth';
import { DashboardError } from '@/components/dashboard-error';
import Link from 'next/link';

type DashboardSearchParams = {
  examId?: string;
  examVersionId?: string;
  testVersionId?: string;
  cohort?: string;
};

export default async function TeacherDashboardPage({
  searchParams,
}: {
  searchParams: Promise<DashboardSearchParams>;
}) {
  const user = await requireRole('TEACHER');
  const filters = await searchParams;
  const [cohortTestVersionId, scoringVersion, analyticsVersion] = filters.cohort?.split('|') ?? [];
  let dashboard: TeacherDashboardData | undefined;
  let analytics: TeacherAnalyticsData | undefined;

  try {
    dashboard = await getDashboardData<TeacherDashboardData>('TEACHER');
  } catch (error) {
    console.error('Không thể tải dashboard giáo viên.', error);
  }
  try {
    analytics = await getAnalyticsData<TeacherAnalyticsData>('TEACHER', {
      examId: filters.examId,
      examVersionId: filters.examVersionId,
      testVersionId: cohortTestVersionId ?? filters.testVersionId,
      scoringVersion,
      analyticsVersion,
    });
  } catch (error) {
    console.error('Không thể tải phân tích bài đánh giá của giáo viên.', error);
  }

  if (!dashboard) {
    return (
      <RoleShell
        user={user}
        title="Bảng điều khiển giáo viên"
        summary="Tổng quan lớp học và bài đánh giá do bạn phụ trách."
      >
        <DashboardError />
      </RoleShell>
    );
  }

  return (
    <RoleShell
      user={user}
      title="Bảng điều khiển giáo viên"
      summary="Tổng quan lớp học và bài đánh giá do bạn phụ trách."
    >
      <DashboardMetrics
        metrics={[
          {
            label: 'Lớp đang hoạt động',
            value: dashboard.classes.total,
            description: 'Các lớp đang hoạt động do bạn phụ trách.',
          },
          {
            label: 'Học sinh trong lớp',
            value: dashboard.classes.students,
            description: 'Học sinh đang tham gia các lớp của bạn.',
          },
          {
            label: 'Bài đánh giá',
            value: dashboard.tests.total,
            description: 'Bài đánh giá do bạn tạo.',
          },
          {
            label: 'Đã phát hành',
            value: dashboard.tests.published,
            description: 'Bài đánh giá ở trạng thái đã phát hành.',
          },
        ]}
      />
      <section className="rounded-3xl border border-blue-100 bg-white p-6 shadow-sm shadow-blue-900/5">
        <h2 className="text-lg font-semibold text-blue-950">Phân tích bài đánh giá</h2>
        {analytics && analytics.availableExams.length > 0 && (
          <div className="mt-4 flex flex-wrap items-end gap-4">
            <form action="/teacher" method="get" className="flex flex-wrap items-end gap-2">
              <label className="grid gap-1 text-sm text-slate-700">
                Kỳ thi
                <select
                  name="examId"
                  defaultValue={analytics.selectedExamId?.toString()}
                  className="rounded-lg border border-slate-300 bg-white px-3 py-2"
                >
                  {analytics.availableExams.map((exam) => (
                    <option key={exam.examId} value={exam.examId}>{exam.examCode} — {exam.examName}</option>
                  ))}
                </select>
              </label>
              <button className="rounded-lg bg-blue-700 px-3 py-2 text-sm font-semibold text-white">
                Lọc kỳ thi
              </button>
            </form>
            {analytics.selectedCohort && (
              <form action="/teacher" method="get" className="flex flex-wrap items-end gap-2">
                <input type="hidden" name="examId" value={analytics.selectedCohort.examId} />
                <label className="grid gap-1 text-sm text-slate-700">
                  Phiên bản bài thi
                  <select
                    name="cohort"
                    defaultValue={[
                      analytics.selectedCohort.testVersionId,
                      analytics.selectedCohort.scoringVersion,
                      analytics.selectedCohort.analyticsVersion,
                    ].join('|')}
                    className="rounded-lg border border-slate-300 bg-white px-3 py-2"
                  >
                    {analytics.availableCohorts.map((cohort) => (
                      <option
                        key={`${cohort.testVersionId}-${cohort.scoringVersion}-${cohort.analyticsVersion}`}
                        value={[cohort.testVersionId, cohort.scoringVersion, cohort.analyticsVersion].join('|')}
                      >
                        {cohort.examVersionCode} · {cohort.testTitle} · {cohort.scoringVersion}/{cohort.analyticsVersion}
                      </option>
                    ))}
                  </select>
                </label>
                <button className="rounded-lg border border-blue-200 px-3 py-2 text-sm font-semibold text-blue-800">
                  Lọc phiên bản
                </button>
              </form>
            )}
          </div>
        )}
        {analytics && analytics.scoredAttempts > 0 ? (
          <>
            <p className="mt-2 text-sm leading-6 text-slate-600">
              {analytics.selectedCohort?.examCode} · {analytics.selectedCohort?.examVersionCode} ·{' '}
              {analytics.selectedCohort?.testTitle} — {analytics.scoredAttempts} lượt đã chấm trong cùng
              một phiên bản bài thi · độ chính xác {Math.round(analytics.averageAccuracy * 100)}%.
            </p>
            <p className="mt-1 text-xs text-slate-500">
              Taxonomy: {analytics.selectedCohort?.taxonomyVersion ?? 'không gắn release'} · Scoring:{' '}
              {analytics.selectedCohort?.scoringVersion} · Analytics: {analytics.selectedCohort?.analyticsVersion}
            </p>
            <p className="mt-1 text-sm text-slate-600">
              Thay đổi độ chính xác từ lượt đầu đến lượt gần nhất:{' '}
              {analytics.improvement === null
                ? 'chưa đủ hai lượt so sánh trong cohort này'
                : `${analytics.improvement >= 0 ? '+' : ''}${(analytics.improvement * 100).toFixed(1)} điểm phần trăm`}
            </p>
            <p className="mt-2 text-sm text-slate-600">
              Chính xác {analytics.correctAnswers} · Sai {analytics.incorrectAnswers} · Bỏ qua {analytics.skippedAnswers}
            </p>
            {analytics.recentAttempts.length > 0 && (
              <div className="mt-5">
                <h3 className="font-medium text-slate-800">Kết quả gần đây trong cohort</h3>
                <ul className="mt-2 divide-y divide-slate-100">
                  {analytics.recentAttempts.map((attempt) => (
                    <li key={attempt.attemptId} className="flex flex-wrap justify-between gap-2 py-2 text-sm">
                      <span className="text-slate-700">
                        {new Date(attempt.scoredAt).toLocaleDateString('vi-VN')}
                      </span>
                      <span className="text-slate-600">
                        {attempt.rawScore}/{attempt.maxScore} điểm ·{' '}
                        {attempt.accuracy === null ? 'N/A' : `${Math.round(attempt.accuracy * 100)}%`}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            )}
            <AnalyticsRows
              title="Năng lực"
              entries={analytics.competencies.map((entry) => ({
                name: entry.competencyName,
                accuracy: entry.accuracy,
                evidence: entry.evidenceCount,
              }))}
            />
            <AnalyticsRows
              title="Kỹ năng"
              entries={analytics.skills.map((entry) => ({
                name: entry.skillName,
                accuracy: entry.accuracy,
                evidence: entry.evidenceCount,
              }))}
            />
            <p className="mt-3 text-xs text-slate-500">
              Chỉ tổng hợp kết quả có mapping đã phát hành; không suy diễn nguyên nhân câu trả lời sai.
            </p>
          </>
        ) : (
          <p
            role={analytics ? undefined : 'alert'}
            className="mt-2 text-sm leading-6 text-slate-600"
          >
            {analytics
              ? 'Chưa có kết quả chấm trong kỳ thi hoặc phiên bản đã chọn.'
              : 'Không thể tải phân tích bài đánh giá lúc này. Vui lòng thử tải lại.'}
          </p>
        )}
        <Link
          href="/teacher/assessments"
          className="mt-4 inline-flex rounded-xl bg-blue-700 px-4 py-2 text-sm font-semibold text-white hover:bg-blue-800"
        >
          Mở Test Builder
        </Link>
      </section>
    </RoleShell>
  );
}

function AnalyticsRows({
  title,
  entries,
}: {
  title: string;
  entries: { name: string; accuracy: number; evidence: number }[];
}) {
  if (entries.length === 0) return null;
  return (
    <div className="mt-5">
      <h3 className="font-medium text-slate-800">{title}</h3>
      <ul className="mt-2 divide-y divide-slate-100">
        {entries.map((entry) => (
          <li key={entry.name} className="flex flex-wrap justify-between gap-2 py-2 text-sm">
            <span className="text-slate-700">{entry.name}</span>
            <span className="text-slate-600">
              {Math.round(entry.accuracy * 100)}% · {entry.evidence} câu
            </span>
          </li>
        ))}
      </ul>
    </div>
  );
}
