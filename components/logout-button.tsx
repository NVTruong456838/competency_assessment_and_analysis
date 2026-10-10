'use client';

import { useRouter } from 'next/navigation';

export function LogoutButton() {
  const router = useRouter();

  const handleLogout = async () => {
    await fetch('/api/auth/logout', {
      method: 'POST',
      headers: { 'Content-Type': 'application/json' },
    });

    router.push('/login');
    router.refresh();
  };

  return (
    <button
      type="button"
      onClick={handleLogout}
      className="rounded-full border border-blue-200 bg-white px-4 py-2 text-sm font-medium text-blue-700 transition hover:border-blue-500 hover:bg-blue-50"
    >
      Đăng xuất
    </button>
  );
}
