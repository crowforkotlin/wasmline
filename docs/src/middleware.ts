import { createI18nMiddleware } from 'fumadocs-core/i18n/middleware';
import { i18n } from '@/lib/i18n';

export default createI18nMiddleware(i18n);

export const config = {
  // Locale handling is only needed for page routes.
  matcher: ['/((?!api|_next/static|_next/image|images|favicon.ico).*)'],
};
