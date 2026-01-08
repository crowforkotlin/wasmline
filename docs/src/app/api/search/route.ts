import { source } from '@/lib/source';
import { createFromSource } from 'fumadocs-core/search/server';

// 强制静态生成
export const revalidate = false;

// 使用 staticGET 处理静态导出
export const { staticGET: GET } = createFromSource(source);
