'use client';

import { useState } from 'react';
import { useRouter } from 'next/navigation';

export default function LoginPage() {
  const router = useRouter();
  const [loginMethod, setLoginMethod] = useState<'email' | 'phone'>('email');
  const [error, setError] = useState<string | null>(null);
  const [loading, setLoading] = useState(false);

  const handleSubmit = async (event: React.FormEvent<HTMLFormElement>) => {
    event.preventDefault();
    setLoading(true);
    setError(null);

    const form = new FormData(event.currentTarget);
    const emailOrPhone = String(form.get('emailOrPhone') ?? '').trim();
    const password = String(form.get('password') ?? '');

    try {
      const res = await fetch('/api/auth/login', {
        method: 'POST',
        headers: { 'Content-Type': 'application/json' },
        body: JSON.stringify({ emailOrPhone, password }),
      });
      const data = await res.json();

      if (!res.ok) {
        setError(data.error || 'Đăng nhập thất bại.');
        return;
      }

      router.push(`/${data.role}`);
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
        <div className="mb-8 text-center">
          <p className="text-sm font-semibold uppercase tracking-[0.38em] text-blue-600">TYL Smart Assessment</p>
          <h1 className="mt-3 text-4xl font-bold text-blue-950">Đăng nhập hệ thống</h1>
        </div>

        <form onSubmit={handleSubmit} className="space-y-5">
          <div>
            <fieldset>
              <legend className="mb-2 block text-sm font-medium text-slate-700">Đăng nhập bằng</legend>
              <div className="grid grid-cols-2 gap-2 rounded-2xl bg-blue-50 p-1">
                {(['email', 'phone'] as const).map((method) => (
                  <button
                    key={method}
                    type="button"
                    aria-pressed={loginMethod === method}
                    onClick={() => setLoginMethod(method)}
                    className={`rounded-xl px-4 py-2 text-sm font-semibold transition ${
                      loginMethod === method
                        ? 'bg-white text-blue-700 shadow-sm'
                        : 'text-slate-600 hover:text-blue-700'
                    }`}
                  >
                    {method === 'email' ? 'Email' : 'Số điện thoại'}
                  </button>
                ))}
              </div>
            </fieldset>
            <label htmlFor="emailOrPhone" className="mb-2 mt-4 block text-sm font-medium text-slate-700">
              {loginMethod === 'email' ? 'Email' : 'Số điện thoại'}
            </label>
            <input
              key={loginMethod}
              id="emailOrPhone"
              name="emailOrPhone"
              type={loginMethod === 'email' ? 'email' : 'tel'}
              inputMode={loginMethod === 'phone' ? 'tel' : undefined}
              required
              autoComplete={loginMethod === 'email' ? 'email' : 'tel'}
              maxLength={loginMethod === 'email' ? 255 : 20}
              placeholder={loginMethod === 'email' ? 'ban@example.com' : '+84 912 345 678'}
              className="w-full rounded-2xl border border-blue-200 bg-white px-4 py-3 text-slate-900 outline-none transition placeholder:text-slate-400 focus:border-blue-500 focus:ring-4 focus:ring-blue-100"
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
              required
              autoComplete="current-password"
              className="w-full rounded-2xl border border-blue-200 bg-white px-4 py-3 text-slate-900 outline-none transition focus:border-blue-500 focus:ring-4 focus:ring-blue-100"
            />
          </div>

          {error ? (
            <div className="rounded-2xl border border-rose-200 bg-rose-50 px-4 py-3 text-sm text-rose-700">
              {error}
            </div>
          ) : null}

          <button
            type="submit"
            disabled={loading}
            className="w-full rounded-2xl bg-blue-600 px-4 py-3 font-semibold text-white transition hover:bg-blue-700 focus:outline-none focus:ring-4 focus:ring-blue-200 disabled:cursor-not-allowed disabled:opacity-60"
          >
            {loading ? 'Đang đăng nhập...' : 'Đăng nhập'}
          </button>
        </form>

        <p className="mt-6 text-center text-sm text-slate-600">
          Chưa có tài khoản?{' '}
          <a href="/register" className="font-semibold text-blue-700 hover:text-blue-800 hover:underline">
            Đăng ký ngay
          </a>
        </p>

      </div>
    </main>
  );
}
