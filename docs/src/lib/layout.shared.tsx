import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';

export function baseOptions(lang: string): BaseLayoutProps {
  return {
    nav: {
      title: 'wasmline',
    },
    i18n: true,
    githubUrl: 'https://github.com/crowforkotlin/wasmline',
    links: [
      {
        text: lang === 'zh' ? '文档' : 'Docs',
        url: `/${lang}/docs`,
        active: 'nested-url',
      },
    ],
  };
}
