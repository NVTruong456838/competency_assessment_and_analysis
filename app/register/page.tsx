'use client';

import Link from 'next/link';
import { useState } from 'react';
import { useRouter } from 'next/navigation';

export default function RegisterPage() {
  const router = useRouter();
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setError(null);
    setLoading(true);

    const form = new FormData(event.currentTarget);
    const fullName = String(form.get('fullName') ?? '').trim();
    const email = String(form.get('email') ?? '').trim();
    const password = String(form.get('password') ?? '');
    const confirmPassword = String(form.get('confirmPassword') ?? '');

    if (password !== confirmPassword) {
      setError('Mật khẩu xác nhận không khớp.');
      setLoading(false);
      return;
    }

    try {
      const response = await fetch('/api/auth/register', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ fullName, email, password }),
      });
      const data = await response.json();

      if (!response.ok) {
        setError(data.error || 'Không thể tạo tài khoản. Vui lòng thử lại.');
        return;
      }

      router.push('/student');
      router.refresh();
    } catch {
      setError('Không thể kết nối đến máy chủ. Vui lòng thử lại.');
    } finally {
      setLoading(false);
    }
  };

  return (
    <main className="flex min-h-screen items-center justify-center bg-gradient-to-br from-blue-50 via-white to-sky-100 px-4 py-10">
      <div className="w-full max-w-xl rounded-3xl border border-blue-100 bg-white p-8 shadow-2xl shadow-blue-900/10">
        <header className="mb-8 text-center">
          <p className="text-sm font-semibold uppercase tracking-[0.38em] text-blue-600">
            TYL Smart Assessment
          </p>
          <h1 className="mt-3 text-4xl font-bold text-blue-950">Tạo tài khoản</h1>
          <p className="mt-3 text-sm text-slate-600">
            Đăng ký tài khoản học sinh miễn phí để bắt đầu theo dõi lộ trình học tập.
          </p>
        </header>

        <form onSubmit={handleSubmit} className="space-y-5">
          <div>
            <label htmlFor="fullName" className="mb-2 block text-sm font-medium text-slate-700">
              Họ và tên
            </label>
            <input
              id="fullName"
              name="fullName"
              type="text"
              autoComplete="name"
              minLength={2}
              maxLength={255}
              required
              className="w-full rounded-2xl border border-blue-200 bg-white px-4 py-3 text-slate-900 outline-none transition focus:border-blue-500 focus:ring-4 focus:ring-blue-100"
              placeholder="Nguyễn Minh An"
            />
          </div>

          <div>
            <label htmlFor="email" className="mb-2 block text-sm font-medium text-slate-700">
              Email
            </label>
            <input
              id="email"
              name="email"
              type="email"
              autoComplete="email"
              maxLength={255}
              required
              className="w-full rounded-2xl border border-blue-200 bg-white px-4 py-3 text-slate-900 outline-none transition focus:border-blue-500 focus:ring-4 focus:ring-blue-100"
              placeholder="ban@example.com"
            />
          </div>

          <div>
            <label htmlFor="password" className="mb-2 block text-sm font-medium text-slate-700">
              Mật khẩu
            </label>
            <input
              id="password"
              name="password"
              type="password"
              autoComplete="new-password"
              minLength={8}
              maxLength={72}
              required
              className="w-full rounded-2xl border border-blue-200 bg-white px-4 py-3 text-slate-900 outline-none transition focus:border-blue-500 focus:ring-4 focus:ring-blue-100"
              placeholder="Tối thiểu 8 ký tự"
            />
          </div>

          <div>
            <label htmlFor="confirmPassword" className="mb-2 block text-sm font-medium text-slate-700">
              Xác nhận mật khẩu
            </label>
            <input
              id="confirmPassword"
              name="confirmPassword"
              type="password"
              autoComplete="new-password"
              minLength={8}
              maxLength={72}
              required
              className="w-full rounded-2xl border border-blue-200 bg-white px-4 py-3 text-slate-900 outline-none transition focus:border-blue-500 focus:ring-4 focus:ring-blue-100"
              placeholder="Nhập lại mật khẩu"
            />
          </div>

          {error ? (
            <div role="alert" className="rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
              {error}
            </div>
          ) : null}

          <button
            type="submit"
            disabled={loading}
            className="w-full rounded-2xl bg-blue-600 px-4 py-3 font-semibold text-white transition hover:bg-blue-700 focus:outline-none focus:ring-4 focus:ring-blue-200 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {loading ? 'Đang tạo tài khoản...' : 'Đăng ký'}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-slate-600">
          Đã có tài khoản?{' '}
          <Link href="/login" className="font-semibold text-blue-700 hover:text-blue-800 hover:underline">
            Đăng nhập
          </Link>
        </p>
      </div>
    </main>
  );
}
