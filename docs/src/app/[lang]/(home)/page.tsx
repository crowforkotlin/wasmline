// src/app/[lang]/(home)/page.tsx
import { i18n } from '@/lib/i18n';
import Link from 'next/link';
import {
  ArrowRight,
  BookOpen,
  Bug,
  Github,
  Layers,
  Mail,
  ShieldCheck,
  Zap,
} from 'lucide-react';

export function generateStaticParams() {
  return i18n.languages.map((lang) => ({ lang }));
}

const GITHUB_URL = 'https://github.com/crowforkotlin/wasmline';

const content = {
  en: {
    badge: 'Kotlin Multiplatform · WASI Plugin Framework',
    subtitle: 'Run WebAssembly plugins anywhere Kotlin runs.',
    description:
      'Wasmline is a Kotlin Multiplatform framework for loading and calling WASI-compliant WebAssembly plugins in Android, iOS, Desktop, and Web applications. All bridge code is generated at compile time by a Kotlin IR compiler plugin — no reflection, no annotation processing.',
    getStarted: 'Get Started',
    features: [
      { icon: 'zap', label: 'Compile-time bridge synthesis' },
      { icon: 'layers', label: 'Android · iOS · Desktop · Web' },
      { icon: 'shield', label: 'Sandboxed by wasmtime & the browser' },
    ],
    cards: [
      {
        icon: 'docs',
        title: 'Documentation',
        description: 'Installation, usage guides, CLI reference, and architecture details.',
        href: '/docs',
        internal: true,
      },
      {
        icon: 'github',
        title: 'GitHub',
        description: 'Source code, releases, and contribution guidelines.',
        href: GITHUB_URL,
      },
      {
        icon: 'bug',
        title: 'Report Issues',
        description: 'Found a bug or have a feature request? Let us know.',
        href: `${GITHUB_URL}/issues`,
      },
      {
        icon: 'mail',
        title: 'Contact',
        description: 'Questions, discussions, and community support.',
        href: 'https://github.com/crowforkotlin',
      },
      {
        icon: 'zap',
        title: 'wasmtime',
        description: 'Native targets are powered by the wasmtime WebAssembly runtime.',
        href: 'https://wasmtime.dev',
      },
    ],
    footer: 'Licensed under Apache-2.0',
  },
  zh: {
    badge: 'Kotlin Multiplatform · WASI 插件框架',
    subtitle: '在 Kotlin 运行的任何地方执行 WebAssembly 插件。',
    description:
      'Wasmline 是一个 Kotlin Multiplatform 框架，用于在 Android、iOS、Desktop 与 Web 应用中加载并调用符合 WASI 规范的 WebAssembly 插件。全部桥接代码由 Kotlin IR 编译器插件在编译期生成——无反射、无注解处理。',
    getStarted: '快速开始',
    features: [
      { icon: 'zap', label: '编译期桥接生成' },
      { icon: 'layers', label: 'Android · iOS · Desktop · Web' },
      { icon: 'shield', label: 'wasmtime 与浏览器沙箱隔离' },
    ],
    cards: [
      {
        icon: 'docs',
        title: '文档',
        description: '安装、使用指南、CLI 参考与架构细节。',
        href: '/docs',
        internal: true,
      },
      {
        icon: 'github',
        title: 'GitHub',
        description: '源码、发行版与贡献指南。',
        href: GITHUB_URL,
      },
      {
        icon: 'bug',
        title: '问题反馈',
        description: '发现了 Bug 或有功能建议？告诉我们。',
        href: `${GITHUB_URL}/issues`,
      },
      {
        icon: 'mail',
        title: '联系方式',
        description: '问题讨论与社区交流。',
        href: 'https://github.com/crowforkotlin',
      },
      {
        icon: 'zap',
        title: 'wasmtime',
        description: '原生目标由 wasmtime WebAssembly 运行时驱动。',
        href: 'https://wasmtime.dev',
      },
    ],
    footer: '基于 Apache-2.0 许可证发布',
  },
} as const;

const iconMap = {
  zap: Zap,
  layers: Layers,
  shield: ShieldCheck,
  docs: BookOpen,
  github: Github,
  bug: Bug,
  mail: Mail,
};

export default async function HomePage({
  params,
}: {
  params: Promise<{ lang: string }>;
}) {
  const { lang } = await params;
  const t = lang === 'zh' ? content.zh : content.en;

  return (
    <main className="relative flex flex-1 flex-col overflow-hidden">
      {/* 背景：紫色光晕 + 网格 */}
      <div className="pointer-events-none absolute inset-0 -z-10">
        <div className="absolute left-1/2 top-[-120px] h-[420px] w-[720px] -translate-x-1/2 rounded-full bg-violet-600/25 blur-[120px] dark:bg-violet-500/20" />
        <div className="absolute inset-0 [background-image:linear-gradient(to_right,rgb(128_128_128/0.06)_1px,transparent_1px),linear-gradient(to_bottom,rgb(128_128_128/0.06)_1px,transparent_1px)] [background-size:56px_56px] [mask-image:radial-gradient(ellipse_at_top,black_30%,transparent_75%)]" />
      </div>

      {/* Hero */}
      <section className="flex flex-col items-center px-6 pb-16 pt-24 text-center sm:pt-32">
        <span className="mb-6 inline-flex items-center gap-2 rounded-full border border-violet-500/30 bg-violet-500/10 px-4 py-1.5 text-xs font-medium text-violet-600 dark:text-violet-300">
          <Zap className="size-3.5" />
          {t.badge}
        </span>

        <h1 className="bg-gradient-to-br from-violet-500 via-purple-500 to-indigo-600 bg-clip-text text-6xl font-bold tracking-tight text-transparent sm:text-7xl dark:from-violet-300 dark:via-purple-400 dark:to-indigo-400">
          wasmline
        </h1>

        <p className="mt-4 text-xl font-medium text-fd-foreground sm:text-2xl">
          {t.subtitle}
        </p>

        <p className="mt-6 max-w-2xl text-sm leading-relaxed text-fd-muted-foreground sm:text-base">
          {t.description}
        </p>

        <div className="mt-10 flex flex-wrap items-center justify-center gap-4">
          <Link
            href={`/${lang}/docs`}
            className="inline-flex items-center gap-2 rounded-lg bg-violet-600 px-6 py-3 text-sm font-semibold text-white shadow-lg shadow-violet-600/25 transition hover:bg-violet-500"
          >
            {t.getStarted}
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
          {t.features.map((feature) => {
            const Icon = iconMap[feature.icon];
            return (
              <span
                key={feature.label}
                className="inline-flex items-center gap-2 text-sm text-fd-muted-foreground"
              >
                <Icon className="size-4 text-violet-500" />
                {feature.label}
              </span>
            );
          })}
        </div>
      </section>

      {/* 链接卡片 */}
      <section className="mx-auto grid w-full max-w-5xl grid-cols-1 gap-4 px-6 pb-20 sm:grid-cols-2 lg:grid-cols-3">
        {t.cards.map((card) => {
          const Icon = iconMap[card.icon];
          const isInternal = 'internal' in card && card.internal;
          const href = isInternal ? `/${lang}${card.href}` : card.href;
          const className =
            'group flex flex-col gap-3 rounded-xl border border-fd-border bg-fd-card p-6 transition hover:border-violet-500/50 hover:shadow-lg hover:shadow-violet-600/10';
          const inner = (
            <>
              <span className="inline-flex size-10 items-center justify-center rounded-lg bg-violet-500/10 text-violet-600 dark:text-violet-300">
                <Icon className="size-5" />
              </span>
              <span className="flex items-center gap-1.5 font-semibold text-fd-foreground">
                {card.title}
                <ArrowRight className="size-3.5 opacity-0 transition group-hover:translate-x-0.5 group-hover:opacity-100" />
              </span>
              <span className="text-sm leading-relaxed text-fd-muted-foreground">
                {card.description}
              </span>
            </>
          );
          return isInternal ? (
            <Link key={card.title} href={href} className={className}>
              {inner}
            </Link>
          ) : (
            <a
              key={card.title}
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

      {/* 页脚 */}
      <footer className="border-t border-fd-border px-6 py-6 text-center text-xs text-fd-muted-foreground">
        {t.footer}
      </footer>
    </main>
  );
}
