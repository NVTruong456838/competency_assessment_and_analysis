import type { ReactNode } from 'react';
import { LogoutButton } from '@/components/logout-button';

export function RoleShell({
  user,
  title,
  summary,
  children,
}: {
  user: { fullName: string; role: string };
  title: string;
  summary: string;
  children: ReactNode;
}) {
  return (
    <main className="min-h-screen bg-blue-50/70 text-slate-800">
      <div className="mx-auto max-w-7xl px-4 py-8 sm:px-6 lg:px-8">
        <header className="mb-8 flex flex-col gap-4 rounded-3xl border border-blue-100 bg-white p-6 shadow-xl shadow-blue-900/5 md:flex-row md:items-center md:justify-between">
          <div>
            <p className="text-sm font-semibold uppercase tracking-[0.3em] text-blue-600">TYL Smart Assessment</p>
            <h1 className="mt-2 text-3xl font-bold text-blue-950">{title}</h1>
            <p className="mt-2 text-sm text-slate-600">{summary}</p>
          </div>

          <div className="flex items-center gap-3">
            <div className="rounded-2xl border border-blue-100 bg-blue-50 px-4 py-2 text-right">
              <div className="text-[11px] uppercase tracking-[0.2em] text-slate-500">Tài khoản</div>
              <div className="text-sm font-semibold text-slate-800">{user.fullName}</div>
              <div className="text-xs font-medium text-blue-700">{user.role}</div>
            </div>
            <LogoutButton />
          </div>
        </header>

        <div className="space-y-6">{children}</div>
      </div>
    </main>
  );
}
