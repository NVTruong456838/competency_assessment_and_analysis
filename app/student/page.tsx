import { DashboardMetrics } from '@/components/dashboard-metrics';
import { RoleShell } from '@/components/role-shell';
import { getDashboardData, type StudentDashboardData } from '@/lib/dashboard-data';
import { requireRole } from '@/lib/auth';
import { DashboardError } from '@/components/dashboard-error';

export default async function StudentDashboardPage() {
  const user = await requireRole('STUDENT');
  let dashboard: StudentDashboardData | undefined;

  try {
    dashboard = await getDashboardData<StudentDashboardData>('STUDENT');
  } catch (error) {
    console.error('Không thể tải dashboard học sinh.', error);
  }

  if (!dashboard) {
    return (
      <RoleShell
        user={user}
        title="Bảng điều khiển học sinh"
        summary="Theo dõi hoạt động làm bài và kết quả đánh giá của bạn."
      >
        <DashboardError />
      </RoleShell>
    );
  }

  return (
    <RoleShell
      user={user}
      title="Bảng điều khiển học sinh"
      summary="Theo dõi hoạt động làm bài và kết quả đánh giá của bạn."
    >
      <DashboardMetrics
        metrics={[
          {
            label: 'Tổng lượt thi',
            value: dashboard.attempts.total,
            description: 'Số lượt thi đã tạo cho tài khoản của bạn.',
          },
          {
            label: 'Đã hoàn thành',
            value: dashboard.attempts.completed,
            description: 'Các lượt thi đã nộp hoặc được hệ thống tự nộp.',
          },
          {
            label: 'Đang thực hiện',
            value: dashboard.attempts.inProgress,
            description: 'Các lượt thi chưa nộp.',
          },
          {
            label: 'Độ chính xác trung bình',
            value: `${dashboard.averageAccuracyPercent}%`,
            description: 'Trung bình trên các lượt thi đã có kết quả chấm.',
          },
        ]}
      />
      <section className="rounded-3xl border border-blue-100 bg-white p-6 shadow-sm shadow-blue-900/5">
        <h2 className="text-lg font-semibold text-blue-950">Luyện tập theo năng lực</h2>
        <p className="mt-2 text-sm leading-6 text-slate-600">
          Hồ sơ năng lực, phân tích kỹ năng và đề xuất luyện tập sẽ xuất hiện khi dữ liệu đánh giá
          tương ứng được triển khai.
        </p>
      </section>
    </RoleShell>
  );
}
