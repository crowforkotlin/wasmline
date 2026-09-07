import { existsSync, readFileSync } from 'node:fs';
import { resolve } from 'node:path';

type AotCompatibilityCatalog = {
  currentWasmlineVersion: string;
  ranges: Array<{
    fromWasmlineVersion: string;
    aotGeneration: number;
    wasmtimeDistributionVersion: string;
    changedBackends: string[];
  }>;
};

type TableLabels = {
  fromVersion: string;
  throughVersion: string;
  generation: string;
  wasmtimeDistribution: string;
  changedBackends: string;
  current: string;
  before: string;
};

function loadCatalog(): AotCompatibilityCatalog {
  const candidates = [
    resolve(process.cwd(), 'aot-compatibility.json'),
    resolve(process.cwd(), '..', 'aot-compatibility.json'),
  ];
  const catalogPath = candidates.find((candidate) => existsSync(candidate));
  if (!catalogPath) {
    throw new Error('The repository AOT compatibility catalog was not found.');
  }
  return JSON.parse(readFileSync(catalogPath, 'utf8')) as AotCompatibilityCatalog;
}

const catalog = loadCatalog();

const defaultLabels: TableLabels = {
  fromVersion: 'From Wasmline version',
  throughVersion: 'Through Wasmline version',
  generation: 'AOT generation',
  wasmtimeDistribution: 'Wasmtime fork distribution',
  changedBackends: 'Changed backends',
  current: 'Current release',
  before: 'Before',
};

type AotCompatibilityTableProps = {
  labels?: Partial<TableLabels>;
};

export function AotCompatibilityTable({
  labels: providedLabels,
}: AotCompatibilityTableProps) {
  const labels = { ...defaultLabels, ...providedLabels };

  return (
    <div className="not-prose my-6 overflow-x-auto rounded-lg border border-fd-border">
      <table className="w-full min-w-[40rem] text-left text-sm">
        <thead className="bg-fd-muted/50 text-fd-foreground">
          <tr>
            <th className="px-4 py-3 font-medium">{labels.fromVersion}</th>
            <th className="px-4 py-3 font-medium">{labels.throughVersion}</th>
            <th className="px-4 py-3 font-medium">{labels.generation}</th>
            <th className="px-4 py-3 font-medium">{labels.wasmtimeDistribution}</th>
            <th className="px-4 py-3 font-medium">{labels.changedBackends}</th>
          </tr>
        </thead>
        <tbody className="divide-y divide-fd-border">
          {catalog.ranges.map((range, index) => {
            const nextRange = catalog.ranges[index + 1];
            const throughVersion = nextRange
              ? `${labels.before} ${nextRange.fromWasmlineVersion}`
              : `${catalog.currentWasmlineVersion} (${labels.current})`;
            return (
              <tr key={`${range.aotGeneration}-${range.fromWasmlineVersion}`}>
                <td className="whitespace-nowrap px-4 py-3 font-mono text-xs">
                  {range.fromWasmlineVersion}
                </td>
                <td className="whitespace-nowrap px-4 py-3 font-mono text-xs">
                  {throughVersion}
                </td>
                <td className="whitespace-nowrap px-4 py-3">{range.aotGeneration}</td>
                <td className="whitespace-nowrap px-4 py-3 font-mono text-xs">
                  {range.wasmtimeDistributionVersion}
                </td>
                <td className="px-4 py-3">{range.changedBackends.join(', ')}</td>
              </tr>
            );
          })}
        </tbody>
      </table>
    </div>
  );
}
