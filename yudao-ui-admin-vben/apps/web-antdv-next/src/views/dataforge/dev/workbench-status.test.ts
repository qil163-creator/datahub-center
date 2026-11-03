import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { describe, expect, it } from 'vitest';

import {
  resolveWorkbenchDirectoryPath,
  resolveWorkbenchRuntimeLabel,
} from './workbench-status';

describe('工作台底栏目录路径', () => {
  it('按真实父级从根到叶排列，兼容数字与字符串编号', () => {
    expect(
      resolveWorkbenchDirectoryPath(
        [
          { id: 3, name: '清洗', parentId: '2' },
          { id: '1', name: '自定义数据域', parentId: 0 },
          { id: 2, name: '业务目录', parentId: 1 },
        ],
        '3',
      ),
    ).toEqual(['自定义数据域', '业务目录', '清洗']);
  });

  it('大编号字符串不经过数值转换，相邻编号不会串目录', () => {
    const directories: DataforgeDevApi.Dir[] = [
      { id: '9007199254740992', name: '目录甲', parentId: 0 },
      { id: '9007199254740993', name: '目录乙', parentId: 0 },
      { id: '9007199254740994', name: '子目录', parentId: '9007199254740993' },
    ];
    expect(
      resolveWorkbenchDirectoryPath(directories, '9007199254740994'),
    ).toEqual(['目录乙', '子目录']);
    expect(
      resolveWorkbenchDirectoryPath(directories, Number.MAX_SAFE_INTEGER + 1),
    ).toEqual([]);
  });

  it('树形 children 可以补足父级，但显式根编号零始终终止', () => {
    expect(
      resolveWorkbenchDirectoryPath(
        [
          {
            id: 1,
            name: '树根',
            parentId: 0,
            children: [
              { id: 2, name: '推导子级' },
              { id: 3, name: '独立根', parentId: '0' },
            ],
          },
        ],
        2,
      ),
    ).toEqual(['树根', '推导子级']);
    expect(
      resolveWorkbenchDirectoryPath(
        [
          {
            id: 1,
            name: '不应出现在路径',
            children: [{ id: 3, name: '根', parentId: 0 }],
          },
        ],
        3,
      ),
    ).toEqual(['根']);
  });

  it('缺失目录或父级时不虚构业务名称', () => {
    const directories = [{ id: 7, name: '可确认目录', parentId: 99 }];
    expect(resolveWorkbenchDirectoryPath(directories, 7)).toEqual([
      '可确认目录',
    ]);
    expect(resolveWorkbenchDirectoryPath(directories, 99)).toEqual([]);
    expect(resolveWorkbenchDirectoryPath(directories, 0)).toEqual([]);
    expect(resolveWorkbenchDirectoryPath(directories, null)).toEqual([]);
  });

  it('父级成环和 children 对象成环均有限终止且不重复节点', () => {
    const first: DataforgeDevApi.Dir = { id: 1, name: '环甲', parentId: 2 };
    const second: DataforgeDevApi.Dir = { id: 2, name: '环乙', parentId: 1 };
    first.children = [second];
    second.children = [first];
    expect(resolveWorkbenchDirectoryPath([first], 1)).toEqual(['环乙', '环甲']);
    expect(
      resolveWorkbenchDirectoryPath([{ id: 3, name: '自身', parentId: 3 }], 3),
    ).toEqual(['自身']);
  });
});

describe('工作台底栏运行类型', () => {
  it('按传入模式展示 Flink SQL，数据库信息不覆盖原生引擎', () => {
    expect(resolveWorkbenchRuntimeLabel('FLINK_SQL', 'MYSQL', 'BATCH')).toBe(
      'Flink批',
    );
    expect(resolveWorkbenchRuntimeLabel('flink-sql', 'HIVE', 'STREAMING')).toBe(
      'Flink流',
    );
    expect(resolveWorkbenchRuntimeLabel('FLINK_SQL')).toBe('Flink批');
  });

  it('实时页面和离线页面的 Spark SQL 都使用参考名称', () => {
    expect(resolveWorkbenchRuntimeLabel('SPARK_SQL', undefined, 'BATCH')).toBe(
      'SparkSql',
    );
    expect(
      resolveWorkbenchRuntimeLabel('SPARK_SQL', undefined, 'STREAMING'),
    ).toBe('SparkSql');
  });

  it.each([
    ['SPARK_JAR', 'Spark流'],
    ['PYSPARK', 'Spark流'],
    ['FLINK_JAR', 'Flink流'],
    ['PYFLINK', 'Flink流'],
  ])('原生类型 %s 保留既有流模式文案 %s', (taskType, expected) => {
    expect(resolveWorkbenchRuntimeLabel(taskType, 'MYSQL', 'STREAMING')).toBe(
      expected,
    );
  });

  it.each([
    ['mysql', 'MySQL'],
    ['POSTGRESQL', 'PostgreSQL'],
    ['pg', 'PostgreSQL'],
    ['Apache Hive', 'Hive'],
    ['HIVE', 'Hive'],
    ['dameng', 'DM8'],
    ['DM8', 'DM8'],
    ['DORIS', 'Apache Doris'],
  ])('数据库类型 %s 使用产品文案 %s', (dbType, expected) => {
    expect(resolveWorkbenchRuntimeLabel('SQL', dbType, 'STREAMING')).toBe(
      expected,
    );
  });

  it('其它程序与未知连接沿用现有产品映射', () => {
    expect(resolveWorkbenchRuntimeLabel('PYFLINK', 'MYSQL')).toBe('Flink批');
    expect(resolveWorkbenchRuntimeLabel('SPARK_JAR', 'POSTGRESQL')).toBe(
      'Spark批',
    );
    expect(resolveWorkbenchRuntimeLabel('SHELL', 'MYSQL')).toBe('Shell');
    expect(resolveWorkbenchRuntimeLabel('PROCEDURE', 'ORACLE')).toBe('Oracle');
    expect(resolveWorkbenchRuntimeLabel('SQL', 'Custom Database')).toBe(
      'Custom Database',
    );
    expect(resolveWorkbenchRuntimeLabel('SQL')).toBe('数据库任务');
  });
});
