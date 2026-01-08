// src/components/animated-tab.tsx
'use client';

import { Tab as DefaultTab } from 'fumadocs-ui/components/tabs';
import { ComponentProps } from 'react';

// 这里的原理是：
// 当 Tabs 切换时，Fumadocs 会把非当前 Tab 设置为 display: none
// 当 Tab 变为 display: block 时，浏览器会自动重新播放 .animate-tab-content 的动画
export function AnimatedTab({ children, ...props }: ComponentProps<typeof DefaultTab>) {
  return (
    <DefaultTab {...props}>
      <div className="animate-tab-content w-full h-full">
        {children}
      </div>
    </DefaultTab>
  );
}