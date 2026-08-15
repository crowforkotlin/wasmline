import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import { chineseSiteContent } from './site-content';

export function baseOptions(lang: string): BaseLayoutProps {
  return {
    nav: {
      title: 'wasmline',
    },
    i18n: true,
    githubUrl: 'https://github.com/crowforkotlin/wasmline',
    links: [
      {
        text: lang === 'zh' ? chineseSiteContent.docsLink : 'Docs',
        url: `/${lang}/docs`,
        active: 'nested-url',
      },
    ],
  };
}
