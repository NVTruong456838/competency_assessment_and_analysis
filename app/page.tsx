import { redirect } from 'next/navigation';
import { getRoleDashboardPath, getSessionUser } from '@/lib/auth';

export default async function HomePage() {
  const session = await getSessionUser();

  if (!session) {
    redirect('/login');
  }

  redirect(await getRoleDashboardPath(session.role));
}
