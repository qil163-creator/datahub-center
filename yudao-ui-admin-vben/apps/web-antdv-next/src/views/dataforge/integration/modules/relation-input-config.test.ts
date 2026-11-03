import { describe, expect, it } from 'vitest';

import {
  createRelationReadConfig,
  relationColumnMetadata,
  relationColumnsFromMetadata,
  relationConnectionMatches,
} from './relation-input-config';

describe('关系输入旧协议适配', () => {
  it('打开旧草稿不迁移键，编辑ID和时间条件仍保留无损参数', () => {
    const config = {
      readMode: 'ID_INCREMENT',
      incrementColumn: 'id',
      incrementStart: '900719925474099312345',
      custom: { keep: true },
    };
    const before = JSON.stringify(config);
    const view = createRelationReadConfig(config);
    expect(view.startValue).toBe('900719925474099312345');
    expect(JSON.stringify(config)).toBe(before);
    view.startValue = '900719925474099312346';
    expect(config.incrementStart).toBe('900719925474099312346');
    expect(config).not.toHaveProperty('startValue');
    view.readMode = 'TIME_INCREMENT';
    expect(config).not.toHaveProperty('incrementStart');
    view.timeFormat = 'yyyy-MM-dd HH:mm:ss';
    expect(view.dateFormat).toBe('yyyy-MM-dd HH:mm:ss');
    view.timeConditions = [
      {
        field: 'changed_at',
        baselineType: 'TIME_RANGE',
        cursorTime: '2026-11-03 00:00:00',
      },
    ];
    view.readMode = 'ID_INCREMENT';
    expect(view.startValue).toBe('900719925474099312346');
    expect(config).not.toHaveProperty('timeConditions');
    view.readMode = 'TIME_INCREMENT';
    expect(view.timeConditions[0].cursorTime).toBe('2026-11-03 00:00:00');
    expect(config.custom.keep).toBe(true);
  });
  it('历史单时间字段可回显为时间范围条件而不隐式改写草稿', () => {
    const config = {
      readMode: 'TIME_INCREMENT',
      dateColumn: 'created_at',
      dateFormat: 'yyyy-MM-dd',
      cursorTime: '2026-11-01',
    };
    const view = createRelationReadConfig(config);
    expect(view.timeConditions).toEqual([
      {
        field: 'created_at',
        baselineType: 'TIME_RANGE',
        cursorTime: '2026-11-01',
      },
    ]);
    expect(config).not.toHaveProperty('timeConditions');
    expect(view.timeFormat).toBe('yyyy-MM-dd');
  });
  it('字段元数据不向原生配置注入非小数字段长度、主键或推测类型', () => {
    const columns = relationColumnsFromMetadata(
      'read-odps',
      [
        {
          fieldName: 'id',
          fieldTypeName: 'BIGINT',
          pk: true,
          length: 19,
          scale: 0,
        },
        { fieldName: 'label', remarks: '名称', length: 100 },
      ],
      [{ name: 'id', type: 'LONG', custom: '保留' }],
    );
    expect(columns).toEqual([
      { name: 'id', type: 'BIGINT', custom: '保留' },
      { name: 'label', comment: '名称' },
    ]);
    expect(
      relationColumnMetadata({ name: 'id', type: 'Long' }).fieldTypeName,
    ).toBe('BIGINT');
  });
  it('连接类型过滤兼容MaxCompute与未知元数据，不把明确的其他产品当ODPS', () => {
    expect(
      relationConnectionMatches('read-odps', {
        id: '9007199254740993',
        productId: 'MaxCompute',
      }),
    ).toBe(true);
    expect(
      relationConnectionMatches('read-odps', {
        id: '2',
        productId: 'POSTGRESQL',
      }),
    ).toBe(false);
    expect(relationConnectionMatches('read-odps', { id: '3' })).toBe(true);
    expect(
      relationConnectionMatches('read-mongodb', {
        id: '4',
        databaseTypeName: 'MongoDB',
      }),
    ).toBe(true);
  });
});
