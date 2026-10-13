export const metadata = {
  title: "Smart Assessment AI",
  description: "Student analytics and assessment portal",
};

export default function RootLayout({ children }: { children: React.ReactNode }) {
  return (
    <html lang="en">
      <body>{children}</body>
    </html>
  );
}
