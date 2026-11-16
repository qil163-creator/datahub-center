import { describe, expect, it, vi } from 'vitest';

import {
  COLUMN_FAILURE_RETRY_MS,
  columnTableReferences,
  identifierSegments,
  loadCompletionColumns,
  qualifierBeforeDot,
  quoteIdentifier,
  quoteTableReference,
  resolveTable,
  tablesInSchema,
} from './sql-completion';

const table = (text: string, alias?: string, isAccessible = true) => ({
  entityContextType: 'table',
  text,
  isAccessible,
  _alias: alias ? { text: alias } : null,
});

describe('SQL 标识符解析', () => {
  it('按未加引号的点拆分并去掉各种引号', () => {
    expect(identifierSegments('"fill-center"."a.b"')).toEqual([
      { raw: '"fill-center"', value: 'fill-center', quoted: true },
      { raw: '"a.b"', value: 'a.b', quoted: true },
    ]);
    expect(
      identifierSegments('`db`.orders').map((segment) => segment.value),
    ).toEqual(['db', 'orders']);
    expect(identifierSegments('[dbo].[Order]').map((s) => s.value)).toEqual([
      'dbo',
      'Order',
    ]);
    expect(identifierSegments('"say ""hi"""')[0]?.value).toBe('say "hi"');
    expect(identifierSegments('public . orders').map((s) => s.value)).toEqual([
      'public',
      'orders',
    ]);
  });

  it('光标前的限定名取最后一个点之前的部分', () => {
    expect(qualifierBeforeDot(['r', '.'])).toBe('r');
    expect(qualifierBeforeDot(['r', '.', 'st'])).toBe('r');
    expect(qualifierBeforeDot(['"fill-center"', '.'])).toBe('"fill-center"');
    expect(qualifierBeforeDot(['s', '.', 't', '.'])).toBe('s.t');
    expect(qualifierBeforeDot([])).toBeUndefined();
    expect(qualifierBeforeDot(['.'])).toBeUndefined();
    expect(qualifierBeforeDot(['status'])).toBeUndefined();
  });
});

describe('表引用与元数据目录对齐', () => {
  const tables = [
    { name: '"fill-center"."orders"' },
    { name: '"fill-center"."Users"' },
    { name: 'public.orders' },
    { name: 'dim_area' },
  ];

  it('引号写法、大小写不同也能找到目录里的原样表名', () => {
    expect(resolveTable(tables, '"fill-center".orders')?.name).toBe(
      '"fill-center"."orders"',
    );
    expect(resolveTable(tables, '"fill-center"."users"')?.name).toBe(
      '"fill-center"."Users"',
    );
    expect(resolveTable(tables, 'PUBLIC.ORDERS')?.name).toBe('public.orders');
  });

  it('省略 schema 时唯一匹配才采用，有歧义时不猜', () => {
    expect(resolveTable(tables, 'users')?.name).toBe('"fill-center"."Users"');
    expect(resolveTable(tables, 'orders')).toBeUndefined();
  });

  it('语句多写了 schema 时也能对上只有表名的目录', () => {
    expect(resolveTable(tables, 'ods.dim_area')?.name).toBe('dim_area');
    expect(resolveTable(tables, 'missing')).toBeUndefined();
    expect(resolveTable(tables, '')).toBeUndefined();
  });

  it('“schema.”之后只补该 schema 的表并保留原引号', () => {
    expect(tablesInSchema(tables, '"fill-center"')).toEqual([
      {
        table: tables[0],
        insertText: '"orders"',
        filterText: 'orders',
      },
      { table: tables[1], insertText: '"Users"', filterText: 'Users' },
    ]);
    expect(tablesInSchema(tables, 'PUBLIC').map((m) => m.insertText)).toEqual([
      'orders',
    ]);
    expect(tablesInSchema(tables, 'nope')).toEqual([]);
  });
});

describe('字段补全读取哪些表', () => {
  const entities = [
    table('"fill-center"."orders"', 'r'),
    table('users', 'u'),
    table('dim_area'),
    table('hidden', undefined, false),
    { entityContextType: 'queryResult', text: '*', _alias: null },
  ];

  it('没有限定名时取当前语句可访问的全部表', () => {
    expect(columnTableReferences(entities)).toEqual([
      '"fill-center"."orders"',
      'users',
      'dim_area',
    ]);
    expect(columnTableReferences(entities, undefined, 2)).toHaveLength(2);
  });

  it('“别名.”只取该别名对应的表，别名不区分大小写', () => {
    expect(columnTableReferences(entities, 'r')).toEqual([
      '"fill-center"."orders"',
    ]);
    expect(columnTableReferences(entities, 'U')).toEqual(['users']);
  });

  it('“表名.”优先匹配未起别名的表，未知限定名不返回任何表', () => {
    expect(columnTableReferences(entities, 'dim_area')).toEqual(['dim_area']);
    expect(columnTableReferences(entities, '"fill-center"."orders"')).toEqual([
      '"fill-center"."orders"',
    ]);
    expect(columnTableReferences(entities, 'x')).toEqual([]);
    expect(columnTableReferences(entities, 'hidden')).toEqual([]);
  });
});

describe('按方言加引号', () => {
  it('PostgreSQL 系对大写、特殊字符加双引号，MySQL 系用反引号', () => {
    expect(quoteIdentifier('order_id', 'pgsql')).toBe('order_id');
    expect(quoteIdentifier('OrderId', 'pgsql')).toBe('"OrderId"');
    expect(quoteIdentifier('fill-center', 'pgsql')).toBe('"fill-center"');
    expect(quoteIdentifier('a"b', 'pgsql')).toBe('a"b');
    expect(quoteIdentifier('OrderId', 'mysql')).toBe('OrderId');
    expect(quoteIdentifier('订单 编号', 'mysql')).toBe('`订单 编号`');
    expect(quoteIdentifier('a`b c', 'sparksql')).toBe('a`b c');
    expect(quoteIdentifier('1st', 'mysql')).toBe('`1st`');
  });

  it('表引用逐段处理，已带引号的原样插入', () => {
    expect(quoteTableReference('Sales.Orders', 'pgsql')).toBe(
      '"Sales"."Orders"',
    );
    expect(quoteTableReference('"fill-center"."orders"', 'pgsql')).toBe(
      '"fill-center"."orders"',
    );
    expect(quoteTableReference('ods.orders', 'mysql')).toBe('ods.orders');
  });
});

describe('字段缓存', () => {
  it('成功结果复用，并发请求合并', async () => {
    const loadColumns = vi.fn(async () => [{ name: 'id' }]);
    const source = { tables: [], loadColumns };
    const [first, second] = await Promise.all([
      loadCompletionColumns(source, 'orders'),
      loadCompletionColumns(source, 'orders'),
    ]);
    expect(first).toEqual([{ name: 'id' }]);
    expect(second).toBe(first);
    await loadCompletionColumns(source, 'orders');
    expect(loadColumns).toHaveBeenCalledTimes(1);
    await loadCompletionColumns({ tables: [], loadColumns }, 'orders');
    expect(loadColumns).toHaveBeenCalledTimes(2);
  });

  it('失败返回空并在重试间隔后重新读取，不会永久缓存失败', async () => {
    let now = 1000;
    const loadColumns = vi
      .fn<(table: string) => Promise<{ name: string }[]>>()
      .mockRejectedValueOnce(new Error('表引用无法解析'))
      .mockResolvedValue([{ name: 'id' }]);
    const source = { tables: [], loadColumns };
    expect(await loadCompletionColumns(source, 'orders', () => now)).toEqual(
      [],
    );
    now += COLUMN_FAILURE_RETRY_MS - 1;
    expect(await loadCompletionColumns(source, 'orders', () => now)).toEqual(
      [],
    );
    expect(loadColumns).toHaveBeenCalledTimes(1);
    now += 1;
    expect(await loadCompletionColumns(source, 'orders', () => now)).toEqual([
      { name: 'id' },
    ]);
    expect(loadColumns).toHaveBeenCalledTimes(2);
  });

  it('同步抛错与没有加载器时返回空', async () => {
    const source = {
      tables: [],
      loadColumns: () => {
        throw new Error('boom');
      },
    };
    expect(await loadCompletionColumns(source, 't')).toEqual([]);
    expect(await loadCompletionColumns({ tables: [] }, 't')).toEqual([]);
  });
});
