import type { BaseLayoutProps } from 'fumadocs-ui/layouts/shared';
import { i18n } from '@/lib/i18n';

export function baseOptions(): BaseLayoutProps {
  return {
    nav: {
      title: 'wasmline',
    },
    i18n: i18n,
    links: [
    {
      text: 'Documentation',
      url: '/en/docs',
      active: 'nested-url',
    },
  ],
  };
}
