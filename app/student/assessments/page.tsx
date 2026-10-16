import Link from 'next/link';
import { AssessmentCatalog } from '@/components/assessment-catalog';
import { RoleShell } from '@/components/role-shell';
import { requireRole } from '@/lib/auth';

export default async function StudentAssessmentsPage() {
  const user = await requireRole('STUDENT');
  return (
    <RoleShell
      user={user}
      title="Bài đánh giá TSA/HSA"
      summary="Chọn bài thi đã được phát hành hoặc tiếp tục một lượt làm bài đang thực hiện."
    >
      <div className="mb-5">
        <Link href="/student" className="text-sm font-medium text-blue-700 hover:underline">
          ← Quay lại bảng điều khiển
        </Link>
      </div>
      <AssessmentCatalog />
    </RoleShell>
  );
}
