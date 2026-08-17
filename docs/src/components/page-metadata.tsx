import { PageLastUpdate } from 'fumadocs-ui/page';
import { chineseSiteContent } from '@/lib/site-content';

interface PageMetadataProps {
  lang: string;
  createdAt: Date;
  updatedAt: Date;
  wordCount: number;
}

export function PageMetadata({
  lang,
  createdAt,
  updatedAt,
  wordCount,
}: PageMetadataProps) {
  const isChinese = lang === 'zh';
  const locale = isChinese ? 'zh-CN' : 'en-US';
  const createdLabel = isChinese ? chineseSiteContent.ui.createdAt : 'Created on';
  const wordLabel = isChinese ? chineseSiteContent.ui.wordCount : 'words';
  const createdDate = new Intl.DateTimeFormat(locale, {
    dateStyle: 'medium',
  }).format(createdAt);

  return (
    <div className="mt-8 flex flex-wrap items-center gap-x-4 gap-y-2 border-t border-fd-border pt-4 text-sm text-fd-muted-foreground">
      <span>
        {createdLabel} {createdDate}
      </span>
      <PageLastUpdate date={updatedAt} />
      <span>
        {wordCount.toLocaleString(locale)} {wordLabel}
      </span>
    </div>
  );
}
