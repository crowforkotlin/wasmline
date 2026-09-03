# Fumadocs Project Layout

## Content and locales

- Put documentation pages in `docs/content/docs/`.
- Use `<slug>.mdx` for English and `<slug>.zh.mdx` for Chinese.
- Keep page order in the nearest `meta.json` and `meta.zh.json` files. Use the
  same slug order in both files unless the user asks for a locale-specific
  difference. Parenthesized directories such as `(reference)` are route
  groups; they organize the page tree without adding a URL segment. A group
  whose metadata sets `root: true` is a top-level sidebar tab. Nested groups
  omit `root` and render as sidebar folders.
- Keep shared Chinese site copy outside MDX in `docs/content/site.zh.json`.
- Keep code samples in Chinese pages in English unless the sample itself must
  show localized data.

When adding a page, create both locale files and add the slug to both metadata
files. When renaming a page, update links in both locales.

## Application map

| Path | Role |
| --- | --- |
| `docs/source.config.ts` | Defines the MDX collection and processed Markdown used by the LLM text route. |
| `docs/src/lib/source.ts` | Loads pages, locales, page images, and processed text. |
| `docs/src/lib/i18n.ts` | Declares `en` as the default and enables `en` and `zh`. |
| `docs/src/lib/layout.shared.tsx` | Holds shared navigation and repository links. |
| `docs/src/mdx-components.tsx` | Registers components that MDX pages may use. |
| `docs/src/app/[lang]/` | Holds localized layouts, the home page, and docs pages. |
| `docs/src/app/api/search/route.ts` | Emits the static JSON search index. |
| `docs/src/app/llms-full.txt/route.ts` | Joins processed Markdown for LLM readers. |
| `docs/src/app/og/docs/[...slug]/route.tsx` | Generates documentation share images. |

Check `docs/src/mdx-components.tsx` before using a custom MDX component. Add an
import and registration there only when the page needs a component that is not
already available.

## Static export and URLs

`docs/next.config.mjs` exports static files and sets the base path to
`/wasmline`.

- Use app-local paths such as `/${lang}/docs` with Next.js `Link`; Next.js adds
  the base path.
- Include `/wasmline` in `window.location` values and plain root-page anchors.
- Do not expect middleware or a server-only handler to run after deployment.
- Keep the root client redirect as the static-export locale fallback.
- Keep search in static mode in the provider and keep its route buildable
  without request data.

The static search index currently exposes `id`, `title`, `description`, `url`,
`structuredData`, `content`, and a locale `tag`. Preserve this shape when
changing search unless the installed Fumadocs API requires a migration.

## Generated files

`fumadocs-mdx` writes collection types and modules to `docs/.source/`. Next.js
writes `.next/`, `out/`, `next-env.d.ts`, and TypeScript build info. Regenerate
these through package scripts; never patch them.

## Checks

Use the lightest checks that cover the change and that the repository rules
allow:

```bash
pnpm --dir docs run types:check
pnpm --dir docs run build
```

For a static build, check `/wasmline/en`, `/wasmline/zh`, one docs page in each
locale, `/wasmline/api/search`, and `/wasmline/llms-full.txt`.
