import './global.css';
import type { ReactNode } from 'react';
import localFont from 'next/font/local';

const mapleMono = localFont({
  src: [
    {
      path: '../../assets/fonts/MapleMono-NF-CN-SemiBold.woff2',
      weight: '600',
      style: 'normal',
    },
    {
      path: '../../assets/fonts/MapleMono-NF-CN-Bold.woff2',
      weight: '700',
      style: 'normal',
    },
  ],
  display: 'swap',
  variable: '--font-maple-mono',
  fallback: ['ui-monospace', 'SFMono-Regular', 'Consolas', 'monospace'],
  adjustFontFallback: false,
});

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
      <body className={`${mapleMono.variable} wasmline-docs`}>
        {children}
      </body>
    </html>
  );
}
