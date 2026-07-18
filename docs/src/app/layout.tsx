// src/app/layout.tsx
import './global.css';
import type { ReactNode } from 'react';

export default async function RootLayout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ lang?: string }>;
}) {
  const { lang } = await params;
  return (
    <html lang={lang ?? 'en'} suppressHydrationWarning>
      <body>{children}</body>
    </html>
  );
}
