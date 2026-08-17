import { chineseSiteContent } from '@/lib/site-content';
import { i18n } from '@/lib/i18n';
import Link from 'next/link';
import {
  ArrowRight,
  BookOpen,
  Bug,
  Github,
  Layers,
  ShieldCheck,
  Zap,
} from 'lucide-react';

export function generateStaticParams() {
  return i18n.languages.map((lang) => ({ lang }));
}

const GITHUB_URL = 'https://github.com/crowforkotlin/wasmline';

type HomeContent = {
  badge: string;
  subtitle: string;
  description: string;
  getStarted: string;
  features: Record<'bridge' | 'platforms' | 'sandbox', string>;
  cards: Record<
    'docs' | 'runtime' | 'issues',
    { title: string; description: string }
  >;
  footer: string;
};

const homeContent = {
  en: {
    badge: 'Kotlin Multiplatform · WASI Plugin Framework',
    subtitle:
      'Load and run WebAssembly plugins in Android, iOS, Desktop, and Web apps',
    description:
      'All bridge code is generated at compile time by a Kotlin IR compiler plugin — no reflection, no annotation processing. Native targets are powered by wasmtime; Web targets run inside the browser sandbox.',
    getStarted: 'Get Started',
    features: {
      bridge: 'Compile-time bridge synthesis',
      platforms: 'Android · iOS · Desktop · Web',
      sandbox: 'Sandboxed by wasmtime and the browser',
    },
    cards: {
      docs: {
        title: 'Documentation',
        description:
          'Installation, usage guides, CLI reference, and architecture details.',
      },
      runtime: {
        title: 'Runtime',
        description:
          'Native targets are powered by the wasmtime WebAssembly runtime.',
      },
      issues: {
        title: 'Report Issues',
        description: 'Found a bug or have a feature request? Let us know.',
      },
    },
    footer: 'Licensed under Apache-2.0',
  },
  zh: chineseSiteContent.home,
} as const satisfies Record<'en' | 'zh', HomeContent>;

const homeFeatures = [
  { key: 'bridge', icon: Zap, color: 'text-fd-primary' },
  { key: 'platforms', icon: Layers, color: 'text-fd-info' },
  { key: 'sandbox', icon: ShieldCheck, color: 'text-fd-success' },
] as const;

const homeCards = [
  {
    key: 'docs',
    icon: BookOpen,
    href: '/docs',
    internal: true,
  },
  {
    key: 'runtime',
    icon: Zap,
    href: 'https://wasmtime.dev',
    internal: false,
  },
  {
    key: 'issues',
    icon: Bug,
    href: `${GITHUB_URL}/issues`,
    internal: false,
  },
] as const;

export default async function HomePage({
  params,
}: {
  params: Promise<{ lang: string }>;
}) {
  const { lang } = await params;
  const content = lang === 'zh' ? homeContent.zh : homeContent.en;

  return (
    <main className="relative flex flex-1 flex-col overflow-hidden bg-fd-background">
      <div className="h-1 w-full bg-fd-primary" />

      <section className="flex flex-col items-center border-b border-fd-border px-6 pb-12 pt-16 text-center sm:pt-20">
        <span className="mb-5 inline-flex items-center gap-2 border-s-2 border-fd-primary px-3 py-1 text-xs text-fd-muted-foreground">
          <Zap className="size-3.5" />
          {content.badge}
        </span>

        <h1 className="text-5xl font-bold text-fd-foreground sm:text-6xl">
          wasmline
        </h1>

        <p className="mt-4 max-w-3xl text-lg text-fd-foreground sm:text-xl">
          {content.subtitle}
        </p>

        <p className="mt-5 max-w-2xl text-sm leading-7 text-fd-muted-foreground">
          {content.description}
        </p>

        <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
          <Link
            href={`/${lang}/docs`}
            className="inline-flex min-h-10 items-center gap-2 rounded-md bg-fd-primary px-5 py-2.5 text-sm text-fd-primary-foreground transition-colors hover:opacity-90"
          >
            {content.getStarted}
            <ArrowRight className="size-4" />
          </Link>
          <a
            href={GITHUB_URL}
            target="_blank"
            rel="noreferrer"
            className="inline-flex min-h-10 items-center gap-2 rounded-md border border-fd-border bg-fd-card px-5 py-2.5 text-sm text-fd-foreground transition-colors hover:bg-fd-accent"
          >
            <Github className="size-4" />
            GitHub
          </a>
        </div>

        <div className="mt-10 grid w-full max-w-3xl grid-cols-3 gap-2 border-t border-fd-border pt-5">
          {homeFeatures.map((feature) => {
            const Icon = feature.icon;
            const label = content.features[feature.key];

            return (
              <span
                key={label}
                className="inline-flex min-w-0 flex-col items-center justify-start gap-2 px-1 text-xs leading-5 text-fd-muted-foreground sm:flex-row sm:justify-center"
              >
                <Icon className={`size-4 shrink-0 ${feature.color}`} />
                <span className="break-words">{label}</span>
              </span>
            );
          })}
        </div>
      </section>

      <section className="mx-auto grid w-full max-w-5xl grid-cols-1 gap-3 px-6 pb-16 pt-10 sm:grid-cols-2 lg:grid-cols-3">
        {homeCards.map((card) => {
          const Icon = card.icon;
          const copy = content.cards[card.key];
          const href = card.internal ? `/${lang}${card.href}` : card.href;
          const className =
            'group flex min-h-44 flex-col gap-3 rounded-lg border border-fd-border bg-fd-card p-5 transition-colors hover:border-fd-primary/55 hover:bg-fd-accent/35';
          const inner = (
            <>
              <span className="inline-flex size-9 items-center justify-center rounded-md border border-fd-border bg-fd-secondary text-fd-primary">
                <Icon className="size-5" />
              </span>
              <span className="flex items-center gap-1.5 text-sm text-fd-foreground">
                {copy.title}
                <ArrowRight className="size-3.5 opacity-0 transition group-hover:translate-x-0.5 group-hover:opacity-100" />
              </span>
              <span className="text-sm leading-relaxed text-fd-muted-foreground">
                {copy.description}
              </span>
            </>
          );

          return card.internal ? (
            <Link key={card.key} href={href} className={className}>
              {inner}
            </Link>
          ) : (
            <a
              key={card.key}
              href={href}
              target="_blank"
              rel="noreferrer"
              className={className}
            >
              {inner}
            </a>
          );
        })}
      </section>

      <footer className="border-t border-fd-border px-6 py-6 text-center text-xs text-fd-muted-foreground">
        {content.footer}
      </footer>
    </main>
  );
}
