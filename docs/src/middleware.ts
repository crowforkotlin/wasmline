// src/middleware.ts
import { createI18nMiddleware } from 'fumadocs-core/i18n/middleware';
import { i18n } from '@/lib/i18n';

export default createI18nMiddleware(i18n);

export const config = {
  // 匹配所有路径，除了 api、_next 静态资源、public 图片与 favicon
  matcher: ['/((?!api|_next/static|_next/image|images|favicon.ico).*)'],
};
