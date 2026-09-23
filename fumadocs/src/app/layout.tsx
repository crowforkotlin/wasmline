import './global.css';
import type { ReactNode } from 'react';
import localFont from 'next/font/local';

// ChillRound ships a single static weight, so it is declared over the full
// 400-700 range: every weight request resolves to this face instead of
// triggering browser faux-bold synthesis.
const chillRound = localFont({
  src: [
    {
      path: '../../assets/fonts/ChillRoundM-SemiBold.woff2',
      weight: '400 700',
      style: 'normal',
    },
  ],
  display: 'swap',
  variable: '--font-chill-round',
  fallback: [
    'ui-sans-serif',
    'system-ui',
    'Noto Sans SC',
    'PingFang SC',
    'Hiragino Sans GB',
    'Microsoft YaHei',
    'sans-serif',
  ],
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
      <body className={`${chillRound.variable} wasmline-docs`}>
        {children}
      </body>
    </html>
  );
}
