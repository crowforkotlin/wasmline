import { source } from '@/lib/source';
import {
  DocsBody,
  DocsDescription,
  DocsPage,
  DocsTitle,
} from 'fumadocs-ui/page';
import { notFound } from 'next/navigation';
import { getMDXComponents } from '@/mdx-components';
import type { Metadata } from 'next';
import { PageTransition } from '@/components/page-transition';
import { PageMetadata } from '@/components/page-metadata';

interface PageProps {
  params: Promise<{ lang: string; slug?: string[] }>;
}

export default async function Page(props: PageProps) {
  const { lang, slug } = await props.params;
  const page = source.getPage(slug, lang);

  if (!page) notFound();

  const MDX = page.data.body;
  const ast = await page.data.getMDAST();
  const wordCount = countWords(ast, lang);

  return (
    <DocsPage toc={page.data.toc} full={page.data.full}>
      <DocsTitle>{page.data.title}</DocsTitle>
      <DocsDescription>{page.data.description}</DocsDescription>
      <PageTransition>
        <DocsBody>
          <MDX components={getMDXComponents()} />
        </DocsBody>
      </PageTransition>
      <PageMetadata
        lang={lang}
        createdAt={page.data.createdAt}
        updatedAt={page.data.updatedAt}
        wordCount={wordCount}
      />
    </DocsPage>
  );
}

function countWords(root: unknown, lang: string) {
  const text = collectText(root);
  const locale = lang === 'zh' ? 'zh-CN' : 'en';
  const segmenter = new Intl.Segmenter(locale, { granularity: 'word' });

  return [...segmenter.segment(text)].filter((segment) => segment.isWordLike)
    .length;
}

function collectText(node: unknown): string {
  if (!node || typeof node !== 'object') return '';

  const { value, children } = node as {
    value?: unknown;
    children?: unknown;
  };
  const ownText = typeof value === 'string' ? value : '';
  const childText = Array.isArray(children)
    ? children.map(collectText).join(' ')
    : '';

  return `${ownText} ${childText}`;
}

export async function generateStaticParams() {
  return source.generateParams();
}

export async function generateMetadata(props: PageProps): Promise<Metadata> {
  const { lang, slug } = await props.params;
  const page = source.getPage(slug, lang);

  if (!page) notFound();

  return {
    title: page.data.title,
    description: page.data.description,
  };
}
