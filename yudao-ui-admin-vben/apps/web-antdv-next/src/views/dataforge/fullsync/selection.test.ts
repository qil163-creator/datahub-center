import type { FullSyncConfig } from '#/api/dataforge/fullsync';

import { describe, expect, it } from 'vitest';

import { reconcileTableSelection } from './selection';

describe('整库同步来源表重新选择', () => {
  it('配置两张表后移除一张，后续保存仅保留可见表的映射和属性', () => {
    const config: FullSyncConfig = {
      taskName: '选择来源表回归',
      taskType: 2,
      sourceSelectedTables: ['A', 'B'],
      configMap: {
        A: { targetTableName: 'ODS_A' },
        B: { targetTableName: 'ODS_B' },
      },
      tableProperties: {
        A: { targetTable: 'ODS_A', partitionRule: 'A_RULE' },
        B: { targetTable: 'ODS_B', partitionRule: 'B_RULE' },
      },
    };
    const next = reconcileTableSelection(config, ['A']);
    expect(next.sourceSelectedTables).toEqual(['A']);
    expect(next.configMap).toEqual({ A: { targetTableName: 'ODS_A' } });
    expect(next.tableProperties).toEqual({
      A: { targetTable: 'ODS_A', partitionRule: 'A_RULE' },
    });
    expect(config.sourceSelectedTables).toEqual(['A', 'B']);
    const reselected = reconcileTableSelection(next, ['A', 'B']);
    expect(reselected.configMap?.B).toBeUndefined();
    expect(reselected.tableProperties?.B).toBeUndefined();
  });
});
