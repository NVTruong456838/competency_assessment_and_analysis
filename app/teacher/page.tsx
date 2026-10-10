import { DashboardMetrics } from '@/components/dashboard-metrics';
import { RoleShell } from '@/components/role-shell';
import { getDashboardData, type TeacherDashboardData } from '@/lib/dashboard-data';
import { requireRole } from '@/lib/auth';
import { DashboardError } from '@/components/dashboard-error';

export default async function TeacherDashboardPage() {
  const user = await requireRole('TEACHER');
  let dashboard: TeacherDashboardData | undefined;

  try {
    dashboard = await getDashboardData<TeacherDashboardData>('TEACHER');
  } catch (error) {
    console.error('Không thể tải dashboard giáo viên.', error);
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
        <h2 className="text-lg font-semibold text-blue-950">Theo dõi học sinh</h2>
        <p className="mt-2 text-sm leading-6 text-slate-600">
          Bảng kết quả từng học sinh và phân tích năng lực lớp sẽ được bổ sung khi hoàn tất các
          luồng làm bài và phân tích.
        </p>
      </section>
    </RoleShell>
  );
}
