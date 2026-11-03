import { describe, expect, it } from 'vitest';

import { fileTreeQuery } from './tree-query';

describe('文件元数据目录筛选', () => {
  it('未选择树节点时查询全部来源', () => {
    expect(fileTreeQuery().sourceSystemMissing).toBeUndefined();
  });
  it('未关联来源系统节点必须明确查询空来源', () => {
    expect(
      fileTreeQuery({
        id: 'system:none',
        name: '未关联来源系统',
        type: 'system',
      }).sourceSystemMissing,
    ).toBe(true);
  });
  it('目录继承空来源条件，兼容实际 JSON null 值', () => {
    expect(
      fileTreeQuery({
        id: 'opaque-id',
        name: '目录',
        type: 'directory',
        sourceSystemId: null,
        datasourceId: '9007199254740993',
        path: '/reports',
      }),
    ).toEqual({
      sourceSystemId: null,
      sourceSystemMissing: true,
      datasourceId: '9007199254740993',
      path: '/reports',
    });
  });
  it('有来源的连接保持长 ID 且不按根目录限制嵌套文件', () => {
    expect(
      fileTreeQuery({
        id: 'opaque-connection',
        name: '文件源',
        type: 'connection',
        sourceSystemId: '9007199254740995',
        datasourceId: '9007199254740993',
      }),
    ).toEqual({
      sourceSystemId: '9007199254740995',
      sourceSystemMissing: undefined,
      datasourceId: '9007199254740993',
      path: undefined,
    });
  });
});
