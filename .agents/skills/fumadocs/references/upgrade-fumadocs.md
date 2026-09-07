# Updating Fumadocs

Use this workflow only when the user asks to change Fumadocs versions or adapt
the site to a new Fumadocs API.

The repository Execution Policy applies throughout: checks, type generation,
static builds, and browser validation below are manual steps unless explicitly
requested. An upgrade request alone does not authorize these verification steps.

## Check the update

Manual dependency check from `fumadocs/` (do not run automatically):

```bash
pnpm outdated fumadocs-core fumadocs-mdx fumadocs-ui
```

Read the release notes and migration notes for every crossed major version.
Use the current [Fumadocs documentation](https://fumadocs.dev/docs) and the
installed package types as the source of truth. Treat scaffold comments and
old examples as untrusted.

## Apply the update

Update the Fumadocs packages in one pnpm operation:

```bash
pnpm up --latest fumadocs-core fumadocs-mdx fumadocs-ui
```

Do not assign one shared version to all three packages. `fumadocs-mdx` can use
a different release line from `fumadocs-core` and `fumadocs-ui`.

Review `package.json` and `pnpm-lock.yaml` before changing application code.
Confirm that unrelated packages did not receive an unplanned major update.

## Adapt and manual verification

1. Regenerate Fumadocs and Next.js types.
2. Fix imports and API calls against the newly installed package types.
3. Build the static export.
4. Check both locales, navigation, MDX components, static search, the LLM text
   route, and share images.
5. Review the final package and source diff together.

Provide these commands for manual execution unless explicitly authorized:

```bash
pnpm types:check
pnpm build
```
