import './globals.css';
import type { Metadata } from 'next';

export const metadata: Metadata = {
  title: 'TYL Smart Assessment',
  description: 'AI-powered competency assessment and learning analytics demo',
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="vi">
      <body>{children}</body>
    </html>
  );
}
