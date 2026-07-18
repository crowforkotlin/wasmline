'use client';

import { useEffect } from 'react';
import { useRouter } from 'next/navigation';

// 静态导出下中间件不会运行，因此在客户端根据浏览器语言跳转到对应语言版本。
// 默认进入英文页面，仅当浏览器语言为中文时进入中文页面。
export default function RootPage() {
  const router = useRouter();

  useEffect(() => {
    const languages = navigator.languages?.length
      ? [...navigator.languages]
      : [navigator.language];
    const preferred = languages.some((lang) =>
      lang?.toLowerCase().startsWith('zh'),
    )
      ? '/zh'
      : '/en';
    router.replace(preferred);
  }, [router]);

  return (
    <main
      style={{
        display: 'flex',
        minHeight: '100vh',
        alignItems: 'center',
        justifyContent: 'center',
        gap: '1rem',
        fontFamily: 'sans-serif',
      }}
    >
      <span>Redirecting… / 正在跳转…</span>
      <a href="/wasmline/en">English</a>
      <a href="/wasmline/zh">中文</a>
    </main>
  );
}
