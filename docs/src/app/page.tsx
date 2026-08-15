'use client';

import { useEffect } from 'react';
import Image from 'next/image';
import { LoaderCircle } from 'lucide-react';
import wasmlineIcon from './icon.png';

// Static exports do not run middleware, so the browser picks the root locale.
export default function RootPage() {
  useEffect(() => {
    const languages = navigator.languages?.length
      ? [...navigator.languages]
      : [navigator.language];
    const preferred = languages.some((lang) =>
      lang?.toLowerCase().startsWith('zh'),
    )
      ? '/wasmline/zh'
      : '/wasmline/en';
    window.location.replace(preferred);
  }, []);

  return (
    <main
      className="flex min-h-svh items-center justify-center bg-zinc-950 px-6 text-zinc-100"
      style={{
        minHeight: '100svh',
        backgroundColor: '#09090b',
        color: '#f4f4f5',
      }}
    >
      <div className="flex flex-col items-center gap-5 text-center">
        <Image
          src={wasmlineIcon}
          alt=""
          width={88}
          height={88}
          priority
          className="size-[88px] rounded-[22px] shadow-2xl shadow-violet-950/50"
        />
        <div className="space-y-2">
          <p className="text-xl font-semibold">wasmline</p>
          <div
            role="status"
            aria-live="polite"
            className="flex h-6 items-center justify-center gap-2 text-sm text-zinc-400"
          >
            <LoaderCircle className="size-4 animate-spin" aria-hidden="true" />
            <span>Loading documentation...</span>
          </div>
        </div>
        <noscript>
          <a className="text-sm underline" href="/wasmline/en">
            Open Wasmline documentation
          </a>
        </noscript>
      </div>
    </main>
  );
}
