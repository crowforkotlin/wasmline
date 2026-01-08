import { HomeLayout } from 'fumadocs-ui/layouts/home';
import { baseOptions } from '@/lib/layout.shared';
import { PageTransition } from '@/components/page-transition';

export default function Layout({ children }: LayoutProps<'/'>) {
  return (
    <HomeLayout {...baseOptions()}>
      <PageTransition>
        {children}
      </PageTransition>
    </HomeLayout>
  );
}