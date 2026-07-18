// src/lib/i18n.ts
import type { I18nConfig } from 'fumadocs-core/i18n';

export const i18n: I18nConfig = {
  defaultLanguage: 'en', // 默认语言（无内容后缀的 .mdx 视为英文）
  languages: ['en', 'zh'], // 支持的语言列表
};
