import { source } from '@/lib/source';
import { NextResponse } from 'next/server';

export const dynamic = 'force-static';

export async function GET() {
  // Build the index without a request because the site is statically exported.
  const indexes = source.getPages().map((page) => ({
    id: page.url,
    title: page.data.title,
    description: page.data.description,
    url: page.url,
    structuredData: page.data.structuredData,
    content: page.data.description ?? '',
    tag: page.locale ?? 'en',
  }));

  return NextResponse.json(indexes);
}
