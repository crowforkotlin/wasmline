// src/app/api/search/route.ts
import { source } from '@/lib/source';
import { NextResponse } from 'next/server';

// ✅ 1. 强制静态导出：告诉 Next.js 这是一个构建时生成的静态文件
export const dynamic = 'force-static';

export async function GET() {
  // ✅ 2. 手动生成索引数据
  // 我们不再使用 createSearchAPI，而是自己生成这个数组
  // 这完全避免了 "request.url" 被调用的风险
  const indexes = source.getPages().map((page) => ({
    id: page.url,
    title: page.data.title,
    description: page.data.description,
    url: page.url,
    structuredData: page.data.structuredData,
    // 必填字段
    content: page.data.description ?? '',
    // 语言标记
    tag: (page as any).file?.locale || 'zh',
  }));

  // ✅ 3. 直接返回 JSON
  // Next.js 会在构建时执行这个函数，并将结果保存为 /wasmline/api/search 对应的静态文件
  return NextResponse.json(indexes);
}