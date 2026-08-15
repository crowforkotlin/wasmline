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
  { key: 'bridge', icon: Zap },
  { key: 'platforms', icon: Layers },
  { key: 'sandbox', icon: ShieldCheck },
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
    <main className="relative flex flex-1 flex-col overflow-hidden">
      <div className="pointer-events-none absolute inset-0 -z-10">
        <div className="absolute left-1/2 top-[-120px] h-[420px] w-[720px] -translate-x-1/2 rounded-full bg-violet-600/25 blur-[120px] dark:bg-violet-500/20" />
        <div className="absolute inset-0 [background-image:linear-gradient(to_right,rgb(128_128_128/0.06)_1px,transparent_1px),linear-gradient(to_bottom,rgb(128_128_128/0.06)_1px,transparent_1px)] [background-size:56px_56px] [mask-image:radial-gradient(ellipse_at_top,black_30%,transparent_75%)]" />
      </div>

      <section className="flex flex-col items-center px-6 pb-16 pt-24 text-center sm:pt-32">
        <span className="mb-6 inline-flex items-center gap-2 rounded-full border border-violet-500/30 bg-violet-500/10 px-4 py-1.5 text-xs font-medium text-violet-600 dark:text-violet-300">
          <Zap className="size-3.5" />
          {content.badge}
        </span>

        <h1 className="bg-gradient-to-br from-violet-500 via-purple-500 to-indigo-600 bg-clip-text text-6xl font-bold tracking-tight text-transparent sm:text-7xl dark:from-violet-300 dark:via-purple-400 dark:to-indigo-400">
          wasmline
        </h1>

        <p className="mt-4 text-xl font-medium text-fd-foreground sm:text-2xl">
          {content.subtitle}
        </p>

        <p className="mt-6 max-w-2xl text-sm leading-relaxed text-fd-muted-foreground sm:text-base">
          {content.description}
        </p>

        <div className="mt-10 flex flex-wrap items-center justify-center gap-4">
          <Link
            href={`/${lang}/docs`}
            className="inline-flex items-center gap-2 rounded-lg bg-violet-600 px-6 py-3 text-sm font-semibold text-white shadow-lg shadow-violet-600/25 transition hover:bg-violet-500"
          >
            {content.getStarted}
            <ArrowRight className="size-4" />
          </Link>
          <a
            href={GITHUB_URL}
            target="_blank"
            rel="noreferrer"
            className="inline-flex items-center gap-2 rounded-lg border border-fd-border bg-fd-card px-6 py-3 text-sm font-semibold text-fd-foreground transition hover:bg-fd-accent"
          >
            <Github className="size-4" />
            GitHub
          </a>
        </div>

        <div className="mt-12 flex flex-wrap items-center justify-center gap-x-8 gap-y-3">
          {homeFeatures.map((feature) => {
            const Icon = feature.icon;
            const label = content.features[feature.key];

            return (
              <span
                key={label}
                className="inline-flex items-center gap-2 text-sm text-fd-muted-foreground"
              >
                <Icon className="size-4 text-violet-500" />
                {label}
              </span>
            );
          })}
        </div>
      </section>

      <section className="mx-auto grid w-full max-w-5xl grid-cols-1 gap-4 px-6 pb-20 sm:grid-cols-2 lg:grid-cols-3">
        {homeCards.map((card) => {
          const Icon = card.icon;
          const copy = content.cards[card.key];
          const href = card.internal ? `/${lang}${card.href}` : card.href;
          const className =
            'group flex flex-col gap-3 rounded-xl border border-fd-border bg-fd-card p-6 transition hover:border-violet-500/50 hover:shadow-lg hover:shadow-violet-600/10';
          const inner = (
            <>
              <span className="inline-flex size-10 items-center justify-center rounded-lg bg-violet-500/10 text-violet-600 dark:text-violet-300">
                <Icon className="size-5" />
              </span>
              <span className="flex items-center gap-1.5 font-semibold text-fd-foreground">
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
