// src/app/layout.tsx
import './global.css';
import type { ReactNode } from 'react';

export default function RootLayout({ children }: { children: ReactNode }) {
  return (
    // 这里的 lang 也可以保留 defaults，具体的由 middleware 和内层 layout 控制
    <html lang="zh" suppressHydrationWarning>
      <body>{children}</body>
    </html>
  );
}