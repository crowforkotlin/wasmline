'use client';

import { useEffect } from 'react';

// 静态导出下中间件不会运行，因此在客户端根据浏览器语言跳转到对应语言版本。
// 默认进入英文页面，仅当浏览器语言为中文时进入中文页面。
export default function RootPage() {
  useEffect(() => {
    const languages = navigator.languages?.length
      ? [...navigator.languages]
      : [navigator.language];
    const preferred = languages.some((lang) =>
      lang?.toLowerCase().startsWith('zh'),
    )
      ? '/wasmline/zh'
      : '/wasmline/en';
    window.location.replace(preferred);
  }, []);

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
      <a href="/wasmline/en">English</a>
      <a href="/wasmline/zh">中文</a>
    </main>
  );
}
