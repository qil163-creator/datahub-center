import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useDatabaseTypeSelect } from './use-database-type-select';

const harness = vi.hoisted(() => ({
  load: vi.fn(),
  access: {} as { accessToken: string; refreshToken?: string },
  categories: [] as { label: string; value: string }[],
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access }));
vi.mock('@vben/hooks', () => ({
  getDictOptions: () => harness.categories,
}));
vi.mock('@vben/constants', () => ({
  DICT_TYPE: { CATEGORY_CODE: 'category' },
}));
vi.mock('#/api/dataforge/databasetype', () => ({
  getDatabaseTypeSimpleList: harness.load,
}));

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

function databaseType(
  data: Partial<FoundationDatabaseTypeApi.DatabaseType>,
): FoundationDatabaseTypeApi.DatabaseType {
  return {
    id: 1,
    categoryCode: 'relational',
    typeCode: 'MYSQL',
    typeName: 'MySQL',
    protocolType: 'JDBC',
    iconPath: '',
    urlTemplates: '',
    portTemplates: '3306',
    validationQuery: 'SELECT 1',
    remark: '',
    capabilities: { METADATA: { reason: '', status: 'DECLARED' } },
    ...data,
  };
}

let app: ReturnType<typeof createApp>;
let target: HTMLDivElement;
let state: ReturnType<typeof useDatabaseTypeSelect>;
beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({ accessToken: '1' });
  harness.categories = reactive([]);
  target = document.createElement('div');
  document.body.append(target);
  app = createApp(
    defineComponent({
      setup() {
        state = useDatabaseTypeSelect();
        return () => h('div');
      },
    }),
  );
  app.mount(target);
});
afterEach(() => {
  app.unmount();
  target.remove();
});

describe('数据库类型的全部分类与搜索', () => {
  const mysql = databaseType({});
  const mongo = databaseType({
    id: 2,
    categoryCode: 'nosql',
    typeCode: 'MONGO',
    typeName: 'MongoDB',
    productId: 'MONGODB',
    description: '文档数据接入',
  });

  it('字典已加载时仍默认加载并展示全部类型，也保留字典未登记的分类', async () => {
    harness.categories.push({ label: '关系型数据库', value: 'relational' });
    harness.load.mockResolvedValue([mysql, mongo]);

    await state.init();

    expect(harness.load).toHaveBeenCalledExactlyOnceWith();
    expect(state.activeCategory.value).toBe('');
    expect(state.categories.value).toEqual([
      { code: '', name: '全部' },
      { code: 'relational', name: '关系型数据库' },
      { code: 'nosql', name: 'nosql' },
    ]);
    expect(state.filteredTypes.value).toEqual([mysql, mongo]);

    harness.categories.push({ label: 'NoSQL', value: 'nosql' });
    expect(state.categories.value[2]).toEqual({ code: 'nosql', name: 'NoSQL' });
  });

  it('搜索覆盖全部类型，清空后恢复当前分类，切换分类无需再次请求', async () => {
    harness.load.mockResolvedValue([mysql, mongo]);
    await state.init();

    await state.selectCategory('relational');
    expect(state.filteredTypes.value).toEqual([mysql]);
    state.searchKeyword.value = ' mongodb ';
    expect(state.filteredTypes.value).toEqual([mongo]);
    state.searchKeyword.value = '文档数据';
    expect(state.filteredTypes.value).toEqual([mongo]);
    state.searchKeyword.value = '';
    expect(state.filteredTypes.value).toEqual([mysql]);
    state.searchKeyword.value = 'mysql';
    await state.selectCategory('nosql');
    expect(state.searchKeyword.value).toBe('');
    expect(state.filteredTypes.value).toEqual([mongo]);
    await state.selectCategory('');
    expect(state.filteredTypes.value).toEqual([mysql, mongo]);
    expect(harness.load).toHaveBeenCalledTimes(1);
  });

  it('侧栏保留字典中的八类连接，尚无连接的分类显示空结果', async () => {
    harness.categories.push(
      { label: '关系型数据库', value: 'RELATIONAL' },
      { label: '分析型数据库/数据仓库', value: 'ANALYTICAL' },
      { label: '文件/对象存储', value: 'FILE_OBJECT_STORAGE' },
      { label: 'NoSQL/缓存', value: 'NOSQL_CACHE' },
      { label: '消息队列', value: 'MESSAGE_QUEUE' },
      { label: '时序数据库', value: 'TIME_SERIES' },
      { label: '半结构化存储', value: 'SEMI_STRUCTURED' },
      { label: '向量型数据库', value: 'VECTOR' },
    );
    const relational = databaseType({ categoryCode: 'RELATIONAL' });
    harness.load.mockResolvedValue([relational]);
    await state.init();

    expect(state.categories.value).toHaveLength(9);
    expect(state.categories.value[0]).toEqual({ code: '', name: '全部' });
    await state.selectCategory('VECTOR');
    expect(state.filteredTypes.value).toEqual([]);
    expect(state.typeList.value).toEqual([relational]);
  });

  it('无可用元数据能力的类型继续展示但不可选，缺少驱动选项不阻止已声明能力的选择', async () => {
    const unverified = databaseType({
      id: 3,
      capabilities: { METADATA: { reason: '', status: 'UNVERIFIED' } },
    });
    harness.load.mockResolvedValue([mysql, unverified]);
    await state.init();

    expect(state.filteredTypes.value).toEqual([mysql, unverified]);
    state.selectType(unverified);
    expect(state.selectedType.value).toBeUndefined();
    state.selectType(mysql);
    expect(state.selectedType.value).toEqual(mysql);
  });
});

describe('数据库类型加载的请求隔离', () => {
  it('多次加载乱序完成时只保留最新结果', async () => {
    const first = deferred<FoundationDatabaseTypeApi.DatabaseType[]>();
    const second = deferred<FoundationDatabaseTypeApi.DatabaseType[]>();
    harness.load
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    const old = state.init();
    const current = state.init();
    second.resolve([]);
    await current;
    first.resolve([databaseType({ typeCode: 'old' })]);
    await old;
    expect(state.typeList.value).toEqual([]);
    expect(state.activeCategory.value).toBe('');
    expect(state.loading.value).toBe(false);
  });

  it('登录会话变化后丢弃进行中的类型响应', async () => {
    const request = deferred<FoundationDatabaseTypeApi.DatabaseType[]>();
    harness.load.mockReturnValue(request.promise);
    const pending = state.init();
    harness.access.refreshToken = '2';
    await nextTick();
    request.resolve([databaseType({})]);
    await pending;
    expect(state.typeList.value).toEqual([]);
    expect(state.activeCategory.value).toBe('');
  });

  it('关闭弹窗后旧响应不能恢复分类或类型', async () => {
    const request = deferred<FoundationDatabaseTypeApi.DatabaseType[]>();
    harness.load.mockReturnValue(request.promise);
    const pending = state.init();
    state.reset();
    request.resolve([databaseType({ categoryCode: 'old' })]);
    await pending;
    expect(state.categories.value).toEqual([]);
    expect(state.typeList.value).toEqual([]);
  });
});
