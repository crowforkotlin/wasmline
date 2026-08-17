import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import Image from 'next/image';
import appIcon from '../app/icon.png';
import { chineseSiteContent } from './site-content';

export function baseOptions(lang: string): BaseLayoutProps {
  return {
    nav: {
      title: (
        <Image
          src={appIcon}
          alt="Wasmline"
          title="Wasmline"
          sizes="32px"
          className="size-8"
        />
      ),
      url: `/${lang}`,
    },
    i18n: true,
    githubUrl: 'https://github.com/crowforkotlin/wasmline',
    links: [
      {
        text: lang === 'zh' ? chineseSiteContent.docsLink : 'Docs',
        url: `/${lang}/docs`,
        active: 'nested-url',
      },
      {
        text:
          lang === 'zh'
            ? chineseSiteContent.apiReferenceLink
            : 'API Reference',
        url: '/wasmline/api-docs/',
        active: 'none',
        external: true,
      },
    ],
  };
}
