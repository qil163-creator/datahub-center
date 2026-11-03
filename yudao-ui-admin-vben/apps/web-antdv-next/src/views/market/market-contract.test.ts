import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { describe, expect, it } from 'vitest';

import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';

import {
  APPLY_STATUS_OPTIONS,
  optionLabel,
  SHARE_ATTRIBUTE_OPTIONS,
} from './market-labels';
import { formatMarketTime } from './market-time';

const marketDir = dirname(fileURLToPath(import.meta.url));
const marketDataSource = readFileSync(join(marketDir, 'data.ts'), 'utf8');
const commentPanelSource = readFileSync(
  join(marketDir, 'modules/comment-panel.vue'),
  'utf8',
);

describe('market contract helpers', () => {
  it('formats millisecond timestamps without treating them as IDs', () => {
    expect(formatMarketTime(1_700_000_000_000)).toMatch(
      /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/,
    );
    expect(formatMarketTime('1700000000000')).toMatch(
      /^\d{4}-\d{2}-\d{2} \d{2}:\d{2}:\d{2}$/,
    );
    expect(formatMarketTime(undefined)).toBe('-');
  });

  it('keeps supplement and share-attribute labels independent of publish status', () => {
    expect(optionLabel(APPLY_STATUS_OPTIONS, 8)).toBe('待补正');
    expect(optionLabel(SHARE_ATTRIBUTE_OPTIONS, 0)).toBe('待核验');
    expect(optionLabel(SHARE_ATTRIBUTE_OPTIONS, 1)).toBe('无条件共享');
    expect(optionLabel(SHARE_ATTRIBUTE_OPTIONS, 2)).toBe('有条件共享');
  });

  it('keeps apply status options as a local binding for search schemas', () => {
    expect(marketDataSource).toMatch(
      /import \{[\s\S]*APPLY_STATUS_OPTIONS[\s\S]*\} from '\.\/market-labels'/u,
    );
    expect(marketDataSource).not.toMatch(
      /export \{[\s\S]*APPLY_STATUS_OPTIONS[\s\S]*\} from '\.\/market-labels'/u,
    );
  });

  it('formats list and modal timestamps as YYYY-MM-DD HH:mm:ss', () => {
    expect(marketDataSource).toMatch(
      /import \{ formatMarketTime \} from '\.\/market-time'/u,
    );
    expect(marketDataSource).toMatch(
      /field: 'createTime'[\s\S]*formatter:\s*\(\{\s*cellValue[\s\S]*\}\s*\)\s*=>\s*formatMarketTime\(cellValue\)/u,
    );
    expect(marketDataSource).toMatch(
      /field: 'publishTime'[\s\S]*formatter:\s*\(\{\s*cellValue[\s\S]*\}\s*\)\s*=>\s*formatMarketTime\(cellValue\)/u,
    );

    const martSource = readFileSync(
      join(marketDir, '../asset/market/index.vue'),
      'utf8',
    );
    // 旧 market/app/index.vue 已下线；应用授权详情现在是 supply/app/modules/detail.vue。
    const appSource = readFileSync(
      join(marketDir, '../supply/app/modules/detail.vue'),
      'utf8',
    );
    const applyDetailSource = readFileSync(
      join(marketDir, 'modules/apply-detail.vue'),
      'utf8',
    );
    const demandDetailSource = readFileSync(
      join(marketDir, 'modules/demand-detail.vue'),
      'utf8',
    );
    const applyFormSource = readFileSync(
      join(marketDir, 'modules/apply-form.vue'),
      'utf8',
    );
    const demandFormSource = readFileSync(
      join(marketDir, 'modules/demand-form.vue'),
      'utf8',
    );

    expect(martSource).toMatch(/formatMarketTime\(asset\.publishTime\)/u);
    // 应用授权详情用 supply 自己的格式化工具，同样按 VO 的毫秒时间戳渲染，不做有损转换。
    expect(appSource).toMatch(/formatSupplyDateTime\(text\)/u);
    expect(appSource).toMatch(
      /formatSupplyDateTime\(authRecord\(record\)\.createTime\)/u,
    );
    expect(applyDetailSource).toMatch(/formatMarketTime\(item\.createTime\)/u);
    expect(demandDetailSource).toMatch(
      /formatMarketTime\(row\.expectFinishTime\)/u,
    );
    expect(demandDetailSource).toMatch(/formatMarketTime\(item\.createTime\)/u);
    expect(commentPanelSource).toMatch(/formatMarketTime\(item\.createTime\)/u);
    expect(applyFormSource).toMatch(/format="YYYY-MM-DD HH:mm:ss"/u);
    expect(demandFormSource).toMatch(/format="YYYY-MM-DD HH:mm:ss"/u);
  });

  it('reuses full-page consumer details and keeps independently authorized preview in a Vben modal', () => {
    const previewDrawerSource = readFileSync(
      join(marketDir, 'modules/preview-drawer.vue'),
      'utf8',
    );
    const resourceSource = readFileSync(
      join(marketDir, '../asset/market/modules/resource-detail.vue'),
      'utf8',
    );
    expect(previewDrawerSource).toMatch(/class="w-\[960px\]"/u);
    expect(previewDrawerSource).not.toMatch(/<Drawer\b/u);
    expect(resourceSource).toMatch(/useVbenDrawer\(\{/u);
    expect(resourceSource).toMatch(/class: 'w-\[960px\] max-w-full'/u);
    expect(resourceSource).toMatch(/placement: 'right'/u);
    expect(resourceSource).toMatch(/embedded \? 'section' : DetailDrawer/u);
    const fullPageSource = readFileSync(
      join(marketDir, '../asset/market/detail/index.vue'),
      'utf8',
    );
    expect(fullPageSource).toMatch(/<ResourceDetail[\s\S]*embedded/u);
    expect(resourceSource).toMatch(/checkMarketAccess\(id\)/u);
    expect(resourceSource).toMatch(
      /previewAllowed\.value = effective === true/u,
    );
  });

  it('does not import antdv-next List components that are not exported', () => {
    expect(commentPanelSource).not.toMatch(/\bListItem(?:Meta)?\b/u);
    expect(commentPanelSource).not.toMatch(
      /import \{[^}]*\bList\b[^}]*\} from 'antdv-next'/u,
    );
  });

  it('preserves large IDs and latest-wins tickets when switching resources', () => {
    expect(parseResourceId('9223372036854775807')).toBe('9223372036854775807');
    expect(resourceIdKey('9223372036854775807')).toBe('9223372036854775807');
    const guard = createLatestWins();
    const first = guard.next();
    const second = guard.next();
    expect(first.isCurrent()).toBe(false);
    expect(second.isCurrent()).toBe(true);
    guard.invalidate();
    expect(second.isCurrent()).toBe(false);
  });
});
