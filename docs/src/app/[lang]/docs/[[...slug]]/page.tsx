import { source } from '@/lib/source';
import { 
  DocsBody, 
  DocsDescription, 
  DocsPage, 
  DocsTitle 
} from 'fumadocs-ui/page'; // ✅ 建议：从这里导入，而不是 layouts/docs/page
import { notFound } from 'next/navigation';
import { getMDXComponents } from '@/mdx-components';
import type { Metadata } from 'next';
import { PageTransition } from '@/components/page-transition';

// ✅ 定义 Props 类型，明确包含 lang
interface PageProps {
  params: Promise<{ lang: string; slug?: string[] }>;
}

export default async function Page(props: PageProps) {
  // ✅ 1. 等待 params 解析 (Next.js 15 要求)
  const { lang, slug } = await props.params;
  
  // ✅ 2. 传入 lang 获取对应语言的页面
  const page = source.getPage(slug, lang);
  
  if (!page) notFound();

  const MDX = page.data.body;

  return (
    <DocsPage toc={page.data.toc} full={page.data.full}>
      <DocsTitle>{page.data.title}</DocsTitle>
      <DocsDescription>{page.data.description}</DocsDescription>
      <PageTransition>
        <DocsBody>
          {/* 这里去掉了 createRelativeLink，通常最新的 fumadocs 不需要手动处理相对链接，
              如果必须保留，请确保 fumadocs-ui/mdx 路径正确 */}
          <MDX components={getMDXComponents()} />
        </DocsBody>
      </PageTransition>
    </DocsPage>
  );
}

export async function generateStaticParams() {
  // ✅ source.generateParams() 在配置了 i18n 后会自动生成包含 { lang, slug } 的数组
  return source.generateParams();
}

export async function generateMetadata(props: PageProps): Promise<Metadata> {
  const { lang, slug } = await props.params;
  const page = source.getPage(slug, lang);
  
  if (!page) notFound();

  return {
    title: page.data.title,
    description: page.data.description,
    // 如果你有 getPageImage 工具函数，这里也可以加上
  };
}