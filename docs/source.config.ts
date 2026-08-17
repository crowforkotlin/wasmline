import {
  defineConfig,
  defineDocs,
  frontmatterSchema,
  metaSchema,
} from 'fumadocs-mdx/config';
import { z } from 'zod';

const docFrontmatterSchema = frontmatterSchema.extend({
  createdAt: z.coerce.date(),
  updatedAt: z.coerce.date(),
});

export const docs = defineDocs({
  dir: 'content/docs',
  docs: {
    schema: docFrontmatterSchema,
    postprocess: {
      // `/llms-full.txt` reads this processed Markdown at build time.
      includeProcessedMarkdown: true,
      includeMDAST: true,
    },
  },
  meta: {
    schema: metaSchema,
  },
});

export default defineConfig({});
