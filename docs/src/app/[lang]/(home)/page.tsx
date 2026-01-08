// src/app/[lang]/(home)/page.tsx
import { i18n } from '@/lib/i18n';
import Link from 'next/link';

// ---------------------------------------------------------
// ✅ 必须添加这个函数，告诉 Next.js 要生成哪些语言的首页
// ---------------------------------------------------------
export function generateStaticParams() {
  return i18n.languages.map((lang) => ({ lang }));
}

// 你的首页组件
export default async function HomePage() {
  return (
    <main
      // ✅ 修改点 1: 添加 Tailwind 类
      // flex-1: 让 main 占满 PageTransition 留下的所有垂直空间
      // flex flex-col: 开启 Flex 布局并垂直排列
      // items-center: 水平居中
      // justify-center: 垂直居中 (核心)
      className="flex flex-1 flex-col items-center justify-center text-center"
      style={{ padding: '2rem' }}
    >
      <h1 className="text-4xl font-bold mb-4">Wasmline Documentation</h1>
      <p className="text-muted-foreground mb-8">Select a language to continue:</p>
      <div style={{ display: 'flex', gap: '1rem' }}>
        <Link
          href="/zh/docs"
          className="px-4 py-2 bg-blue-600 text-white rounded hover:bg-blue-700 transition"
        >
          中文文档
        </Link>
        <Link
          href="/en/docs"
          className="px-4 py-2 bg-zinc-200 text-zinc-900 rounded hover:bg-zinc-300 transition dark:bg-zinc-800 dark:text-zinc-100"
        >
          English Documentation
        </Link>
      </div>
    </main>
  );
}