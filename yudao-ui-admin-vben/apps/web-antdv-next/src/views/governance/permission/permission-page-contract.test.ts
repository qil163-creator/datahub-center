import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import { configKey, isConfigForTable } from './data';

const pageDir = dirname(fileURLToPath(import.meta.url));
const indexSource = readFileSync(join(pageDir, 'index.vue'), 'utf8');
const treeSource = readFileSync(
  join(pageDir, 'components/database-tree-panel.vue'),
  'utf8',
);

describe('safety permission page contract', () => {
  it('does not stretch the header card to the full page height', () => {
    expect(indexSource).toMatch(/class="permission-workspace/u);
    expect(indexSource).toMatch(/<Card class="shrink-0" size="small">/u);
    expect(indexSource).toMatch(/\.permission-workspace :deep\(\.ant-card\)/u);
    expect(indexSource).not.toMatch(/^\s*:deep\(\.ant-card\) \{\s*height: 100%;/mu);
  });

  it('does not expand every database when the tree first loads', () => {
    expect(treeSource).toMatch(/expandedKeys\.value = \[\];/u);
    expect(treeSource).not.toMatch(
      /expandedKeys\.value = treeData\.value\.map/u,
    );
    expect(treeSource).toMatch(/silentError: true/u);
  });

  it('keeps config cache keys lossless for mixed id types', () => {
    const key = configKey('9223372036854775807', 'public', 'orders', '20');
    expect(key).toBe('["9223372036854775807","public","orders","20"]');
    expect(isConfigForTable(key, '9223372036854775807', 'public', 'orders')).toBe(true);
    expect(isConfigForTable(key, 1, 'public', 'orders')).toBe(false);
    expect(isConfigForTable(key, '9223372036854775807', 'Other', 'orders')).toBe(false);
  });

  it('never conflates schema, case, or delimiter-bearing physical identities', () => {
    expect(configKey(1, 'Gov.Schema', 'Order::Log', 20)).not.toBe(configKey(1, 'gov.schema', 'Order::Log', 20));
    expect(configKey(1, 'a::b', 'c', 20)).not.toBe(configKey(1, 'a', 'b::c', 20));
    expect(configKey(1, 'public', 'Orders', 20)).not.toBe(configKey(1, 'public', 'orders', 20));
    expect(isConfigForTable('broken-key', 1, 'public', 'orders')).toBe(false);
  });
});
