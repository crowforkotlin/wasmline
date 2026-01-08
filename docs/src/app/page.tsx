import { redirect } from 'next/navigation';
import { i18n } from '@/lib/i18n';

export default function RootPage() {
  // 在服务端/构建时直接重定向到默认语言
  redirect(`/${i18n.defaultLanguage}`);
}