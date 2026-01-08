import type { ReactNode } from 'react';
import { RootProvider } from 'fumadocs-ui/provider/next';
import { i18n } from '@/lib/i18n';
import { defineI18nUI } from 'fumadocs-ui/i18n';
import '../global.css';
import { Inter } from 'next/font/google';

const inter = Inter({
  subsets: ['latin'],
});

const { provider } = defineI18nUI(i18n, { translations: {} });

export default async function Layout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ lang: string }>;
}) {
  const { lang } = await params; // 获取当前 URL 的语言
  return (
    <RootProvider search={{ options: { type: 'static', }, }} i18n={provider(lang)}>
      {children}
    </RootProvider>
  );
}
