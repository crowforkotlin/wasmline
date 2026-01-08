import defaultMdxComponents from 'fumadocs-ui/mdx';
import type { MDXComponents } from 'mdx/types';

import { 
  File, 
  Folder, 
  Files 
} from 'fumadocs-ui/components/files';
import { Tab, Tabs } from 'fumadocs-ui/components/tabs';
import { Card, Cards } from 'fumadocs-ui/components/card';
import { Step, Steps } from 'fumadocs-ui/components/steps';
import { Accordion, Accordions } from 'fumadocs-ui/components/accordion';
import { ImageZoom } from 'fumadocs-ui/components/image-zoom';
import { AnimatedTab } from '@/components/animated-tab';
import { Callout } from 'fumadocs-ui/components/callout';



export function getMDXComponents(components?: MDXComponents): MDXComponents {
  return {
    ...defaultMdxComponents,
    ...components,
    img: (props) => <ImageZoom {...(props as any)} />,
    Callout,
    Card,
    Cards,
    Tab: AnimatedTab,
    Tabs,
    Step,
    Steps,
    Accordion,
    Accordions,
    File,
    Folder,
    Files,
  };
}