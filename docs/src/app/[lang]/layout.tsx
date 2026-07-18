import type { ReactNode } from 'react';
import { RootProvider } from 'fumadocs-ui/provider/next';
import { i18n } from '@/lib/i18n';
import { defineI18nUI } from 'fumadocs-ui/i18n';
import '../global.css';
import { Inter } from 'next/font/google';

const inter = Inter({
  subsets: ['latin'],
});

const { provider } = defineI18nUI(i18n, {
  translations: {
    en: { displayName: 'English' },
    zh: {
      displayName: '中文',
      search: '搜索文档',
      searchNoResult: '未找到结果',
      toc: '本页目录',
      tocNoHeadings: '本页没有目录',
      lastUpdate: '最后更新',
      chooseLanguage: '选择语言',
      nextPage: '下一页',
      previousPage: '上一页',
      chooseTheme: '选择主题',
      editOnGithub: '在 GitHub 上编辑',
    },
  },
});

export default async function Layout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ lang: string }>;
}) {
  const { lang } = await params; // 获取当前 URL 的语言
  return (
    <RootProvider search={{ options: { type: 'static' } }} i18n={provider(lang)}>
      <div className={inter.className}>{children}</div>
    </RootProvider>
  );
}
