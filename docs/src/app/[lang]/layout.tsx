import type { ReactNode } from 'react';
import { RootProvider } from 'fumadocs-ui/provider/next';
import { i18n } from '@/lib/i18n';
import { defineI18nUI } from 'fumadocs-ui/i18n';
import '../global.css';
import { chineseSiteContent } from '@/lib/site-content';
import WasmlineSearchDialog from '@/components/search-dialog';

const { provider } = defineI18nUI(i18n, {
  translations: {
    en: { displayName: 'English' },
    zh: {
      displayName: chineseSiteContent.languageName,
      ...chineseSiteContent.ui,
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
  const { lang } = await params;
  return (
    <RootProvider
      search={{
        SearchDialog: WasmlineSearchDialog,
        options: { api: '/wasmline/api/search' },
      }}
      theme={{ defaultTheme: 'system', enableSystem: true }}
      i18n={provider(lang)}
    >
      {children}
    </RootProvider>
  );
}
