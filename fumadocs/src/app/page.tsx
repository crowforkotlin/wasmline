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
    <main className="wasmline-root-redirect flex min-h-svh items-center justify-center px-6">
      <div className="flex flex-col items-center gap-5 text-center">
        <Image
          src={wasmlineIcon}
          alt=""
          width={88}
          height={88}
          priority
          className="size-20"
        />
        <div className="space-y-2">
          <p className="text-xl font-bold">wasmline</p>
          <div
            role="status"
            aria-live="polite"
            className="flex h-6 items-center justify-center gap-2 text-sm text-[color:var(--color-fd-muted-foreground)]"
          >
            <LoaderCircle
              className="size-4 animate-spin text-[color:var(--color-fd-primary)]"
              aria-hidden="true"
            />
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
