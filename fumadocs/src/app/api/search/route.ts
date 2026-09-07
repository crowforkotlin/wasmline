import { createTokenizer } from '@orama/tokenizers/mandarin';
import { createFromSource } from 'fumadocs-core/search/server';
import { source } from '@/lib/source';

const search = createFromSource(source, {
  localeMap: {
    en: { language: 'english' },
    zh: {
      components: { tokenizer: createTokenizer() },
      search: { threshold: 0, tolerance: 0 },
    },
  },
});

export const dynamic = 'force-static';

export async function GET() {
  return search.staticGET();
}
