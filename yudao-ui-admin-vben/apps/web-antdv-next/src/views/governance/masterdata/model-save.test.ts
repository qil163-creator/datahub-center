import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { describe, expect, it } from 'vitest';

import {
  isMasterModelStructureChanged,
  isMasterModelStructureFrozen,
  masterModelSaveRequest,
} from './model-save';

describe('主数据模型草稿保存', () => {
  it('允许空字段并保留域、数据源和模型大ID，不发送响应状态', () => {
    const values = {
      domainId: '9007199254740993',
      databaseId: '9007199254740995',
      tableName: 'draft_customer',
      tableCn: '客户草稿',
      description: '基本定义',
      approvalEnabled: false,
      status: 1,
      version: 7,
      releaseTime: 1_791_770_400_000,
      publishState: 'UNKNOWN',
      catalogId: '999',
    };
    const request = masterModelSaveRequest(values, '9007199254740997', []);
    expect(request).toEqual({
      id: '9007199254740997',
      domainId: values.domainId,
      databaseId: values.databaseId,
      tableName: values.tableName,
      tableCn: values.tableCn,
      description: values.description,
      approvalEnabled: false,
      approvalProcessKey: undefined,
      columns: [],
    });
  });

  it('未设置主键的完整字段可保存，字段响应ID和UI rowKey不会发送', () => {
    const column = {
      id: '9007199254740999',
      name: 'name',
      commentStr: '姓名',
      fieldType: 2,
      fieldLength: 100,
      nullable: 1,
      pk: 0,
      standardElementId: '9007199254741001',
      rowKey: 'field-1',
      sort: 99,
    };
    const request = masterModelSaveRequest(
      {
        domainId: '1',
        databaseId: '2',
        tableName: 'draft_person',
        tableCn: '人员',
      },
      undefined,
      [column],
    );
    expect(request.columns[0]).toMatchObject({
      name: 'name',
      pk: 0,
      standardElementId: '9007199254741001',
      sort: 0,
    });
    expect(request.columns[0]).not.toHaveProperty('id');
    expect(request.columns[0]).not.toHaveProperty('rowKey');
  });

  it('已发布、下线或有发布时间的模型固定目标表，未发布草稿可修改目标表', () => {
    expect(isMasterModelStructureFrozen()).toBe(false);
    expect(isMasterModelStructureFrozen({ status: 0 })).toBe(false);
    expect(isMasterModelStructureFrozen({ status: 1 })).toBe(true);
    expect(isMasterModelStructureFrozen({ status: 2 })).toBe(true);
    expect(isMasterModelStructureFrozen({ status: 0, releaseTime: 0 })).toBe(
      true,
    );
  });

  it('已发布、下线和历史草稿更新携带详情版本，不发送发布响应状态', () => {
    const values = {
      domainId: '9007199254740993',
      databaseId: '9007199254740995',
      tableName: 'md_customer',
      tableCn: '客户',
      version: 999,
      status: 1,
      releaseTime: 1_791_770_400_000,
    };
    for (const status of [0, 1, 2]) {
      const request = masterModelSaveRequest(values, '9007199254740997', [], {
        status,
        version: 7,
        releaseTime: 1_791_770_400_000,
      });
      expect(request.expectedVersion).toBe(7);
      expect(request.id).toBe('9007199254740997');
      expect(request).not.toHaveProperty('version');
      expect(request).not.toHaveProperty('status');
      expect(request).not.toHaveProperty('releaseTime');
    }
    expect(
      masterModelSaveRequest(values, '1', [], { status: 1 }).expectedVersion,
    ).toBe(1);
    expect(
      masterModelSaveRequest(values, undefined, [], { version: 7 }),
    ).not.toHaveProperty('expectedVersion');
  });
});
describe('主数据模型结构变更', () => {
  const column: GovernanceMasterDataApi.Column = {
    id: '9007199254740993',
    name: 'code',
    commentStr: '编码',
    fieldType: 2,
    fieldLength: 64,
    fieldScale: null,
    nullable: 1,
    pk: 0,
    sort: 0,
    standardElementId: '9007199254740995',
  };

  it('未移动字段时忽略行ID、UI键及可空默认值的表现差异', () => {
    const next = { ...column, id: '9007199254740997', rowKey: 'field-new' };
    expect(isMasterModelStructureChanged({ columns: [column] }, [next])).toBe(
      false,
    );
    expect(
      isMasterModelStructureChanged(
        { columns: [{ name: 'code', fieldType: 2, pk: 1, nullable: 1 }] },
        [
          {
            name: 'code',
            fieldType: 2,
            pk: 1,
            nullable: 0,
            fieldLength: null,
            fieldScale: null,
          },
        ],
      ),
    ).toBe(false);
    expect(
      isMasterModelStructureChanged(
        { columns: [{ name: 'name', fieldType: 2 }] },
        [{ name: 'name', fieldType: 2, pk: 0, nullable: 1 }],
      ),
    ).toBe(false);
    expect(
      isMasterModelStructureChanged(
        { columns: [{ ...column, standardElementId: 1 }] },
        [{ ...column, standardElementId: '1' }],
      ),
    ).toBe(false);
  });

  it.each([
    ['name', 'new_code'],
    ['commentStr', '客户编码'],
    ['fieldType', 6],
    ['fieldLength', 128],
    ['fieldScale', 4],
    ['pk', 1],
    ['nullable', 0],
    ['standardElementId', '9007199254740997'],
  ])('字段属性%s变化应保存为草稿', (key, value) => {
    expect(
      isMasterModelStructureChanged({ columns: [column] }, [
        { ...column, [key]: value },
      ]),
    ).toBe(true);
  });

  it('增删字段及调整字段顺序都构成结构变化', () => {
    const second = { ...column, name: 'name', sort: 1 };
    expect(isMasterModelStructureChanged({ columns: [column] }, [])).toBe(true);
    expect(
      isMasterModelStructureChanged({ columns: [column] }, [column, second]),
    ).toBe(true);
    expect(
      isMasterModelStructureChanged({ columns: [column, second] }, [
        second,
        column,
      ]),
    ).toBe(true);
    expect(isMasterModelStructureChanged(undefined, [])).toBe(false);
  });
});
