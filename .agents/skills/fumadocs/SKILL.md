---
name: fumadocs
description: Maintain the Wasmline Fumadocs site in `docs/`, including MDX pages, bilingual navigation and copy, Next.js routes and layouts, static search and export, Fumadocs configuration and dependencies, and docs developer guidance. Use when changing files under `docs/`, adding or translating documentation pages, updating Fumadocs packages or APIs, or diagnosing the documentation site.
---

# Wasmline Fumadocs Skill

Use this skill for the documentation app owned by `docs/`. Apply the repository
Wasmline skill at the same time for shared workflow and validation rules.

## Reference Routing

Read only the reference needed for the task.

| Reference | Read when working on |
| --- | --- |
| [`project-layout.md`](./references/project-layout.md) | Content, routes, layouts, i18n, search, MDX components, static export, or generated files |
| [`upgrade-fumadocs.md`](./references/upgrade-fumadocs.md) | Fumadocs dependency updates, migrations, import changes, or lockfile changes |

## Hard Constraints

1. Keep source code, code comments, config comments, and `docs/README.md` in
   English. Keep Chinese site copy in `*.zh.mdx` or `*.zh.json` files.
2. Treat English MDX files as the default pages. Keep a matching `.zh.mdx`
   page and update both locale metadata files when adding or moving a page.
3. Preserve `output: 'export'` and `basePath: '/wasmline'` unless the user asks
   to change deployment behavior.
4. Never edit `docs/.source/`, `docs/.next/`, `docs/out/`,
   `docs/node_modules/`, `docs/next-env.d.ts`, or `*.tsbuildinfo` by hand.
5. Use the pnpm version declared by `docs/package.json` for docs dependencies
   and scripts. Keep `pnpm-lock.yaml` as the only JavaScript lockfile and never
   edit it by hand.
6. Update `fumadocs-core`, `fumadocs-mdx`, and `fumadocs-ui` together, but do
   not force them to share one version number.
7. Keep comments only when they explain a constraint or a non-obvious choice.
   Remove comments that restate the code, record old migrations, or make
   guesses about library behavior.

## Workflow

1. Classify the change as content, application code, configuration, or package
   upgrade and read the matching reference.
2. Inspect the current files and installed package APIs before editing. Do not
   rely on old scaffold comments.
3. Make the smallest complete change. Keep locale pairs, navigation order,
   static routes, and the `/wasmline` prefix consistent.
4. Scan non-Chinese source and content for Chinese text:

   ```bash
   rg -n '[一-龥]' docs/src docs/README.md docs/*.ts docs/*.mjs
   rg -n '[一-龥]' docs/content/docs --glob '!*.zh.mdx' --glob '!*.zh.json'
   ```

5. Follow the repository skill before validation. Run type checks or builds
   only when command authorization permits them, then inspect the final diff.
