import { notFound } from 'next/navigation';
import { ExamPlayer } from '@/components/exam-player';
import { RoleShell } from '@/components/role-shell';
import { requireRole } from '@/lib/auth';

type PageProps = {
  params: Promise<{ attemptId: string }>;
};

export default async function StudentExamPage({ params }: PageProps) {
  const user = await requireRole('STUDENT');
  const { attemptId } = await params;
  if (!/^\d+$/.test(attemptId)) notFound();
  return (
    <RoleShell
      user={user}
      title="Đang làm bài đánh giá"
      summary="Câu trả lời được lưu tự động. Thời gian kết thúc do máy chủ xác định."
    >
      <ExamPlayer attemptId={Number(attemptId)} />
    </RoleShell>
  );
}
