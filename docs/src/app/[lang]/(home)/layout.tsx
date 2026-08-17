import type { ReactNode } from 'react';
import { HomeLayout } from 'fumadocs-ui/layouts/home';
import { baseOptions } from '@/lib/layout.shared';
import { PageTransition } from '@/components/page-transition';

export default async function Layout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ lang: string }>;
}) {
  const { lang } = await params;
  return (
    <HomeLayout {...baseOptions(lang)} className="wasmline-home">
      <PageTransition>{children}</PageTransition>
    </HomeLayout>
  );
}
