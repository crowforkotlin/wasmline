import { chineseSiteContent } from '@/lib/site-content';
import { i18n } from '@/lib/i18n';
import Image from 'next/image';
import Link from 'next/link';
import appIcon from '../../icon.png';
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
    <main className="relative isolate flex flex-1 flex-col overflow-hidden bg-fd-background">
      <div className="h-1 w-full bg-violet-500 dark:bg-violet-300" />

      <div
        aria-hidden="true"
        className="pointer-events-none absolute inset-0 -z-10 overflow-hidden"
      >
        <div className="absolute left-1/2 top-[-120px] h-[420px] w-[min(720px,94vw)] -translate-x-1/2 rounded-full bg-violet-400/18 blur-[110px] dark:bg-violet-400/14" />
        <div className="absolute inset-0 [background-image:linear-gradient(to_right,rgb(139_92_246/0.035)_1px,transparent_1px),linear-gradient(to_bottom,rgb(139_92_246/0.035)_1px,transparent_1px)] [background-size:56px_56px] [mask-image:radial-gradient(ellipse_at_top,black_30%,transparent_75%)] dark:opacity-60" />
      </div>

      <section className="relative flex flex-col items-center px-6 pb-14 pt-14 text-center sm:pt-16">
        <span className="mb-5 inline-flex items-center gap-2 rounded-full border border-violet-200/70 bg-white/50 px-4 py-1.5 text-xs text-violet-600 shadow-sm shadow-violet-950/5 backdrop-blur-md dark:border-violet-200/18 dark:bg-violet-950/18 dark:text-violet-200">
          <Zap className="size-3.5" />
          {content.badge}
        </span>

        <div className="flex flex-col items-center gap-3">
          <Image
            src={appIcon}
            alt="Wasmline app icon"
            priority
            sizes="96px"
            className="size-24 drop-shadow-[0_14px_24px_rgba(139,92,246,0.18)]"
          />
          <h1 className="bg-gradient-to-br from-violet-500 via-purple-500 to-indigo-500 bg-clip-text text-5xl font-bold text-transparent dark:from-violet-200 dark:via-purple-200 dark:to-indigo-200">
            wasmline
          </h1>
        </div>

        <p className="mt-5 w-full max-w-6xl text-lg text-fd-foreground sm:text-xl lg:whitespace-nowrap">
          {content.subtitle}
        </p>

        <p className="mt-5 max-w-2xl text-sm leading-7 text-fd-muted-foreground">
          {content.description}
        </p>

        <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
          <Link
            href={`/${lang}/docs`}
            className="inline-flex min-h-10 items-center gap-2 rounded-md bg-violet-500 px-5 py-2.5 text-sm text-white shadow-lg shadow-violet-500/15 transition-colors hover:bg-violet-600 dark:bg-violet-300 dark:text-violet-950 dark:hover:bg-violet-200"
          >
            {content.getStarted}
            <ArrowRight className="size-4" />
          </Link>
          <a
            href={GITHUB_URL}
            target="_blank"
            rel="noreferrer"
            className="inline-flex min-h-10 items-center gap-2 rounded-md border border-violet-200/70 bg-white/50 px-5 py-2.5 text-sm text-fd-foreground shadow-sm shadow-violet-950/5 backdrop-blur-md transition-colors hover:bg-white/70 dark:border-violet-200/18 dark:bg-violet-950/18 dark:hover:bg-violet-900/28"
          >
            <Github className="size-4" />
            GitHub
          </a>
        </div>

        <div className="mt-10 grid w-full max-w-4xl grid-cols-1 divide-y divide-violet-200/50 overflow-hidden rounded-lg border border-violet-200/60 bg-white/40 shadow-[0_18px_60px_-42px_rgba(124,58,237,0.4)] backdrop-blur-xl sm:grid-cols-3 sm:divide-x sm:divide-y-0 dark:divide-violet-200/12 dark:border-violet-200/16 dark:bg-violet-950/16">
          {homeFeatures.map((feature) => {
            const Icon = feature.icon;
            const label = content.features[feature.key];

            return (
              <span
                key={label}
                className="inline-flex min-w-0 items-center justify-center gap-2 px-4 py-4 text-xs leading-5 text-fd-muted-foreground"
              >
                <Icon className="size-4 shrink-0 text-violet-500 dark:text-violet-200" />
                <span className="break-words">{label}</span>
              </span>
            );
          })}
        </div>
      </section>

      <section className="relative mx-auto grid w-full max-w-5xl grid-cols-1 gap-4 px-6 pb-16 sm:grid-cols-2 lg:grid-cols-3">
        {homeCards.map((card) => {
          const Icon = card.icon;
          const copy = content.cards[card.key];
          const href = card.internal ? `/${lang}${card.href}` : card.href;
          const className =
            'group flex min-h-44 flex-col gap-3 rounded-lg border border-violet-200/60 bg-white/50 p-5 shadow-[0_20px_60px_-44px_rgba(124,58,237,0.5)] backdrop-blur-xl transition-[background-color,border-color,box-shadow,transform] hover:-translate-y-0.5 hover:border-violet-300/70 hover:bg-white/70 hover:shadow-[0_24px_64px_-42px_rgba(124,58,237,0.58)] dark:border-violet-200/16 dark:bg-violet-950/16 dark:hover:border-violet-200/28 dark:hover:bg-violet-900/20';
          const inner = (
            <>
              <span className="inline-flex size-9 items-center justify-center rounded-md bg-violet-400/10 text-violet-600 dark:bg-violet-200/10 dark:text-violet-200">
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

      <footer className="mt-4 border-t border-violet-200/45 bg-white/25 px-6 py-6 text-center text-xs text-fd-muted-foreground backdrop-blur-sm dark:border-violet-200/12 dark:bg-violet-950/8">
        {content.footer}
      </footer>
    </main>
  );
}
