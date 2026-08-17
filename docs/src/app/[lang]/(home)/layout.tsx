import type { ReactNode } from 'react';
import { HomeLayout } from 'fumadocs-ui/layouts/home';
import { baseOptions } from '@/lib/layout.shared';
import { PageTransition } from '@/components/page-transition';
import { HomeSearchTrigger } from '@/components/home-search-trigger';
import { chineseSiteContent } from '@/lib/site-content';

export default async function Layout({
  children,
  params,
}: {
  children: ReactNode;
  params: Promise<{ lang: string }>;
}) {
  const { lang } = await params;
  const searchLabel = lang === 'zh' ? chineseSiteContent.ui.search : 'Search docs';

  return (
    <HomeLayout
      {...baseOptions(lang)}
      searchToggle={{
        components: {
          lg: <HomeSearchTrigger label={searchLabel} />,
          sm: <HomeSearchTrigger label={searchLabel} compact />,
        },
      }}
      className="wasmline-home"
    >
      <PageTransition>{children}</PageTransition>
    </HomeLayout>
  );
}
