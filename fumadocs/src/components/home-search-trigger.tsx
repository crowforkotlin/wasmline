'use client';

import { Search } from 'lucide-react';
import { useSearchContext } from 'fumadocs-ui/contexts/search';

interface HomeSearchTriggerProps {
  label: string;
  compact?: boolean;
}

export function HomeSearchTrigger({
  label,
  compact = false,
}: HomeSearchTriggerProps) {
  const { enabled, hotKey, setOpenSearch } = useSearchContext();

  if (!enabled) return null;

  return (
    <button
      type="button"
      data-search={compact ? undefined : ''}
      data-search-full={compact ? undefined : ''}
      aria-label={label}
      onClick={() => setOpenSearch(true)}
      className={
        compact
          ? 'inline-flex items-center justify-center rounded-md p-2 text-fd-muted-foreground transition-colors hover:bg-fd-accent hover:text-fd-accent-foreground'
          : 'inline-flex w-full max-w-[240px] items-center gap-2 rounded-full border border-fd-border bg-fd-secondary/50 p-1.5 ps-2 text-sm text-fd-muted-foreground transition-colors hover:bg-fd-accent hover:text-fd-accent-foreground'
      }
    >
      <Search className="size-4" aria-hidden="true" />
      {!compact && <span>{label}</span>}
      {!compact && hotKey.length > 0 && (
        <span className="ms-auto inline-flex gap-0.5">
          {hotKey.map((key, index) => (
            <kbd key={index} className="rounded-md border bg-fd-background px-1.5">
              {key.display}
            </kbd>
          ))}
        </span>
      )}
    </button>
  );
}
