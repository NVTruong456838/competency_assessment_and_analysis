import Link from 'next/link';
import { AssessmentBuilder } from '@/components/assessment-builder';
import { RoleShell } from '@/components/role-shell';
import { requireRole } from '@/lib/auth';

export default async function TeacherAssessmentsPage() {
  const user = await requireRole('TEACHER');
  return (
    <RoleShell
      user={user}
      title="Test Builder"
      summary="Tạo Blueprint và dựng Test Version từ Question Bank đã qua review gates."
    >
      <div className="mb-5">
        <Link href="/teacher" className="text-sm font-medium text-blue-700 hover:underline">
          ← Quay lại bảng điều khiển
        </Link>
      </div>
      <AssessmentBuilder />
    </RoleShell>
  );
}
