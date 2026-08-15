'use client';

import { Tab as DefaultTab } from 'fumadocs-ui/components/tabs';
import { ComponentProps } from 'react';

// Returning a hidden tab to the layout restarts its CSS entry animation.
export function AnimatedTab({
  children,
  ...props
}: ComponentProps<typeof DefaultTab>) {
  return (
    <DefaultTab {...props}>
      <div className="animate-tab-content w-full h-full">
        {children}
      </div>
    </DefaultTab>
  );
}
