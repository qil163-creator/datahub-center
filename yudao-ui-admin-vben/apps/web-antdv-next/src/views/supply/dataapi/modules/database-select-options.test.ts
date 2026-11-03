import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { SupplyDataApiApi } from '#/api/supply/dataapi';

import { describe, expect, it } from 'vitest';

import { buildDatabaseSelectGroups } from './database-select-options';

const catalog = [
  { id: 3, typeName: 'Oracle SID', categoryCode: 'RELATIONAL' },
  { id: 4, typeName: 'Oracle Service', categoryCode: 'RELATIONAL' },
  { id: 9, typeName: 'MySQL', categoryCode: 'RELATIONAL' },
] as FoundationDatabaseTypeApi.DatabaseType[];
const categories = [
  { code: 'RELATIONAL', name: '关系型数据库' },
  { code: 'VECTOR', name: '向量型数据库' },
];

describe('数据 API 可选连接的三级目录', () => {
  it('按模板 ID 分开 Oracle 模式，类型目录和空分类不增加可选连接', () => {
    const groups = buildDatabaseSelectGroups(
      [
        { id: 1, name: 'SID 源', productType: 'ORACLE', databaseType: 3 },
        { id: 2, name: 'Service 源', productType: 'ORACLE', databaseType: '4' },
      ],
      catalog,
      categories,
    );
    expect(
      groups[0]?.types.map((type) => [
        type.name,
        type.databases.map((item) => item.id),
      ]),
    ).toEqual([
      ['Oracle SID', [1]],
      ['Oracle Service', [2]],
      ['MySQL', []],
    ]);
    expect(groups[0]?.count).toBe(2);
    expect(groups[1]).toMatchObject({ code: 'VECTOR', count: 0, types: [] });
  });

  it('保留缺少目录信息的历史连接，JSON null 不变成模板 null，ID 全程无损', () => {
    const groups = buildDatabaseSelectGroups(
      [
        {
          id: '9223372036854775806',
          name: '历史连接',
          databaseType: 777,
          categoryCode: null,
        },
        {
          id: 0,
          name: '旧产品连接',
          productType: 'ORACLE',
          databaseType: null,
        },
        { id: 2, name: '数字 ID' },
        { id: '2', name: '重复 ID' },
        { name: '无 ID 的不可选记录' },
      ],
      catalog,
      categories,
    );
    const historical = groups.find((group) => group.code === '')!;
    expect(historical.name).toBe('未分类');
    expect(historical.count).toBe(3);
    expect(historical.types.map((type) => type.name)).toEqual([
      '类型 777',
      'ORACLE',
      '未知类型',
    ]);
    expect(
      historical.types.flatMap((type) => type.databases.map((item) => item.id)),
    ).toEqual(['9223372036854775806', 0, 2]);
    expect(JSON.stringify(groups)).not.toContain('type:null');
  });

  it('全局名称搜索只减少叶子与计数，未知分类可见且不修改原列表', () => {
    const databases: SupplyDataApiApi.DatabaseSimple[] = [
      { id: 1, name: 'Production Oracle', databaseType: 3 },
      {
        id: '9007199254740993',
        name: 'Production Custom',
        databaseType: 99,
        databaseTypeName: '自定义类型',
        categoryCode: 'CUSTOM',
      },
      { id: 3, name: 'Test Custom', databaseType: 99, categoryCode: 'CUSTOM' },
    ];
    const groups = buildDatabaseSelectGroups(
      databases,
      catalog,
      categories,
      '  PRODUCTION  ',
    );
    expect(groups.map((group) => [group.name, group.count])).toEqual([
      ['关系型数据库', 1],
      ['向量型数据库', 0],
      ['CUSTOM', 1],
    ]);
    expect(groups.at(-1)?.types[0]?.databases[0]?.id).toBe('9007199254740993');
    expect(databases).toHaveLength(3);
    expect(
      buildDatabaseSelectGroups(databases, catalog, categories).reduce(
        (sum, group) => sum + group.count,
        0,
      ),
    ).toBe(3);
  });
});
