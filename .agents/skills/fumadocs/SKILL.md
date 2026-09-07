---
name: fumadocs
description: Maintain the Wasmline Fumadocs site in `fumadocs/`, including MDX pages, bilingual navigation and copy, Next.js routes and layouts, static search and export, Fumadocs configuration and dependencies, and docs developer guidance. Use when changing files under `fumadocs/`, adding or translating documentation pages, updating Fumadocs packages or APIs, or diagnosing the documentation site.
---

# Wasmline Fumadocs Skill

Use this skill for the documentation app owned by `fumadocs/`. Apply the repository
Wasmline skill at the same time for shared workflow and validation rules.

## Execution Policy

Default to implementation only. Apply the repository skill's Execution Policy
to every procedure in this skill and its references. Do not automatically run
doctor, lint, checks, type generation/checking, builds, tests, dev servers,
browser validation, or package scripts. This includes Python and Shell checks
used while maintaining the website. An implementation or upgrade request alone
does not authorize validation. Only run the explicitly requested command or
validation scope. Keep generated files untouched when their generator is not
authorized, and report the pending command.

Final responses must state validation as "not run" when applicable and provide
the relevant working directory, prerequisites, and manual validation commands.

## Reference Routing

Read only the reference needed for the task.

| Reference | Read when working on |
| --- | --- |
| [`project-layout.md`](./references/project-layout.md) | Content, routes, layouts, i18n, search, MDX components, static export, or generated files |
| [`upgrade-fumadocs.md`](./references/upgrade-fumadocs.md) | Fumadocs dependency updates, migrations, import changes, or lockfile changes |

## Hard Constraints

1. Keep source code, code comments, config comments, and `fumadocs/README.md` in
   English. Keep Chinese site copy in `*.zh.mdx` or `*.zh.json` files.
2. Treat English MDX files as the default pages. Keep a matching `.zh.mdx`
   page and update both locale metadata files when adding or moving a page.
3. Preserve `output: 'export'` and `basePath: '/wasmline'` unless the user asks
   to change deployment behavior.
4. Never edit `fumadocs/.source/`, `fumadocs/.next/`, `fumadocs/out/`,
   `fumadocs/node_modules/`, `fumadocs/next-env.d.ts`, or `*.tsbuildinfo` by hand.
5. Use the pnpm version declared by `fumadocs/package.json` for docs dependencies
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
4. Provide the relevant language checks for manual execution; do not run them
   automatically:

   ```bash
   rg -n '[一-龥]' fumadocs/src fumadocs/README.md fumadocs/*.ts fumadocs/*.mjs
   rg -n '[一-龥]' fumadocs/content/docs --glob '!*.zh.mdx' --glob '!*.zh.json'
   ```

5. Read the final diff. Report what changed and which validation commands the
   user can run; execute them only when explicitly requested.
