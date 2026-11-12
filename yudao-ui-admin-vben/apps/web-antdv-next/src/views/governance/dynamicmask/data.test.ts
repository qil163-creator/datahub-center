import { describe, expect, it } from 'vitest';

import {
  disableRemarkError,
  MASK_SCENE_OPTIONS,
  reasonView,
  sceneLabel,
  whitelistSceneLabel,
} from './data';

describe('dynamic mask page data', () => {
  it('covers the backend scenes and prefers backend names', () => {
    expect(MASK_SCENE_OPTIONS.map((item) => item.value)).toEqual([
      'DATA_QUERY',
      'ASSET_PREVIEW',
      'MARKET_PREVIEW',
      'DATABASE_PREVIEW',
      'INDICATOR_QUERY',
      'ETL_PREVIEW',
      'DATA_EXPORT',
    ]);
    expect(sceneLabel('DATA_QUERY')).toBe('数据查询');
    expect(sceneLabel('DATA_QUERY', '自定义')).toBe('自定义');
    expect(sceneLabel('UNKNOWN')).toBe('UNKNOWN');
  });

  it('shows unknown codes instead of hiding them', () => {
    expect(whitelistSceneLabel('QUERY')).toBe('查询');
    expect(whitelistSceneLabel('NEW')).toBe('NEW');
    expect(reasonView('UNTRACEABLE').color).toBe('red');
    expect(reasonView('NEW_REASON').label).toBe('NEW_REASON');
  });

  it('requires a reason to disable a scene', () => {
    expect(disableRemarkError('   ')).toBe('关闭动态脱敏必须填写原因');
    expect(disableRemarkError('x'.repeat(501))).toContain('500');
    expect(disableRemarkError('排查问题，临时关闭')).toBeUndefined();
  });
});
