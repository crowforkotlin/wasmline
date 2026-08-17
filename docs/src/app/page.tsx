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
      className="flex min-h-svh items-center justify-center bg-[#101513] px-6 text-[#e7eeeb]"
      style={{
        minHeight: '100svh',
        backgroundColor: '#101513',
        color: '#e7eeeb',
      }}
    >
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
            className="flex h-6 items-center justify-center gap-2 text-sm text-[#a9b5b0]"
          >
            <LoaderCircle
              className="size-4 animate-spin text-[#57c6b4]"
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
