
import { DashboardMetrics } from '@/components/dashboard-metrics';
import { RoleShell } from '@/components/role-shell';
import { getDashboardData, type AdminDashboardData } from '@/lib/dashboard-data';
import { requireRole } from '@/lib/auth';
import { DashboardError } from '@/components/dashboard-error';

export default async function AdminDashboardPage() {
  const user = await requireRole('ADMIN');
  let dashboard: AdminDashboardData | undefined;

  try {
    dashboard = await getDashboardData<AdminDashboardData>('ADMIN');
  } catch (error) {
    console.error('Không thể tải dashboard quản trị.', error);
  }

  if (!dashboard) {
    return (
      <RoleShell
        user={user}
        title="Bảng điều khiển quản trị"
        summary="Tổng quan tài khoản và dữ liệu vận hành hệ thống."
      >
        <DashboardError />
      </RoleShell>
    );
  }

  return (
    <RoleShell
      user={user}
      title="Bảng điều khiển quản trị"
      summary="Tổng quan tài khoản và dữ liệu vận hành hệ thống."
    >
      <DashboardMetrics
        metrics={[
          {
            label: 'Tài khoản',
            value: dashboard.users.total,
            description: `${dashboard.users.active} tài khoản đang hoạt động.`,
          },
          {
            label: 'Phân bố vai trò',
            value: `${dashboard.users.students} / ${dashboard.users.teachers} / ${dashboard.users.admins}`,
            description: 'Học sinh / giáo viên / quản trị viên.',
          },
          {
            label: 'Lớp học',
            value: dashboard.content.classes,
            description: 'Tổng số lớp đã được tạo.',
          },
          {
            label: 'Ngân hàng câu hỏi',
            value: dashboard.content.questions,
            description: 'Tổng số câu hỏi trong hệ thống.',
          },
          {
            label: 'Bài đánh giá',
            value: dashboard.content.tests,
            description: `${dashboard.content.publishedTests} bài đã phát hành.`,
          },
        ]}
      />
      <section className="rounded-3xl border border-blue-100 bg-white p-6 shadow-sm shadow-blue-900/5">
        <h2 className="text-lg font-semibold text-blue-950">Quản trị hệ thống</h2>
        <p className="mt-2 text-sm leading-6 text-slate-600">
          Thống kê được đọc trực tiếp từ PostgreSQL. Các thao tác quản lý tài khoản, phân quyền,
          khung năng lực và cấu hình AI chưa được mở trong phạm vi hiện tại.
        </p>
      </section>
    </RoleShell>
  );
}
