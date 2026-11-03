import { describe, expect, it } from 'vitest';

import {
  collectTaskPayload,
  parseCollectBlacklist,
  parseCollectScope,
} from './task-form';

describe('采集任务编辑契约', () => {
  it('结构化任务保留超长 ID 和库表范围，连接凭据与执行状态不回写', () => {
    const payload = collectTaskPayload(
      {
        id: '9007199254740993',
        datasourceId: '9007199254740995',
        sourceSystemId: '9007199254740997',
        name: ' 库表采集 ',
        collectType: 1,
        scopeType: 2,
        enabled: 1,
        scheduleEnabled: 1,
        runStatus: 2,
        creatorName: '创建人',
        host: '不应回写',
        username: '不应回写',
        executeStrategy: 'SERIAL_WAIT',
      },
      [{ db: 'demo', tables: ['orders'] }],
      ['tmp_*'],
    );
    expect(payload).toMatchObject({
      id: '9007199254740993',
      datasourceId: '9007199254740995',
      sourceSystemId: '9007199254740997',
      name: '库表采集',
      executeStrategy: 'SERIAL_WAIT',
      scopeConfig: '[{"db":"demo","tables":["orders"]}]',
      blacklist: '["tmp_*"]',
    });
    expect(payload).not.toHaveProperty('enabled');
    expect(payload).not.toHaveProperty('runStatus');
    expect(payload).not.toHaveProperty('username');
    expect(payload).not.toHaveProperty('host');
    expect(payload).not.toHaveProperty('creatorName');
  });
  it('文件任务不提交结构化范围，保留明确目录路径', () => {
    const payload = collectTaskPayload(
      {
        collectType: 2,
        filePath: ' /reports ',
        scopeType: 2,
        scopeConfig: '旧范围',
        blacklist: '旧黑名单',
      },
      [{ db: 'demo', tables: [] }],
      ['tmp_*'],
    );
    expect(payload).toMatchObject({
      collectType: 2,
      filePath: '/reports',
      scopeType: 1,
    });
    expect(payload.scopeConfig).toBeUndefined();
    expect(payload.blacklist).toBeUndefined();
  });
  it('空表集合表示采集所选库全部表，读取时保留该语义', () => {
    expect(parseCollectScope('[{"db":"demo","tables":[]}]')).toEqual([
      { db: 'demo', tables: [] },
    ]);
    expect(parseCollectBlacklist('["tmp_*"]')).toEqual(['tmp_*']);
  });
  it.each([
    '{}',
    '[{"db":"demo","tables":"*"}]',
    '[{"db":1,"tables":[]}]',
    '[null]',
    '[{"db":"demo","tables":[5]}]',
  ])('拒绝错误范围形状 %s', (value) => {
    expect(() => parseCollectScope(value)).toThrow(
      '已有采集范围格式不正确，请重新配置',
    );
  });
  it('拒绝混合类型黑名单，避免错误对象被提交成字符串', () => {
    expect(() => parseCollectBlacklist('["tmp",{}]')).toThrow(
      '已有黑名单格式不正确，请重新配置',
    );
  });
});
