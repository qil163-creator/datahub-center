/* eslint-disable vue/one-component-per-file -- 测试使用组件桩模拟交互。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getDatabaseSimpleList, getDatabaseTableList } from '#/api/dataforge/database';
import { getRulePage } from '#/api/governance/rule';
import { createSafetyStaticTask, getSafetyStaticTaskColumnList } from '#/api/governance/statictask';

import Form from './form.vue';

const harness = vi.hoisted(() => ({ options: undefined as any, error: vi.fn() }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: unknown) => {
    harness.options = options;
    return [defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }), {
      lock: vi.fn(), unlock: vi.fn(), close: vi.fn(), getData: () => null,
    }];
  },
}));
vi.mock('@vben/hooks', () => ({ getDictLabel: () => '' }));
vi.mock('@vben/icons', () => ({ IconifyIcon: defineComponent({}) }));
vi.mock('#/components/cron-tab', () => ({ CronTab: defineComponent({}) }));
vi.mock('#/api/dataforge/database', () => ({ getDatabaseSimpleList: vi.fn(), getDatabaseTableList: vi.fn() }));
vi.mock('#/api/governance/rule', () => ({ getRulePage: vi.fn() }));
vi.mock('#/api/governance/statictask', () => ({
  createSafetyStaticTask: vi.fn(), getSafetyStaticTask: vi.fn(),
  getSafetyStaticTaskColumnList: vi.fn(), updateSafetyStaticTask: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const component = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  return { Button: component, Input: component, InputNumber: component, RadioGroup: component,
    Select: component, TextArea: component, message: { error: harness.error, success: vi.fn() } };
});

let app: App;
let host: HTMLDivElement;
let state: any;
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => { resolve = done; });
  return { promise, resolve };
}
beforeEach(async () => {
  vi.resetAllMocks();
  vi.mocked(getDatabaseSimpleList).mockResolvedValue([]);
  vi.mocked(getRulePage).mockResolvedValue({ list: [], total: 0 });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Form);
  const instance = app.mount(host) as any;
  state = instance.$.setupState;
  await harness.options.onOpenChange(true);
});
afterEach(() => { app.unmount(); host.remove(); });

describe('静态脱敏表单', () => {
  it('保留大数据源编号，并忽略先前数据源的迟到响应', async () => {
    const old = deferred<any[]>();
    const current = deferred<any[]>();
    vi.mocked(getDatabaseTableList).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    state.handleSourceDatabaseChange('9007199254740993');
    state.handleSourceDatabaseChange('9007199254740995');
    expect(getDatabaseTableList).toHaveBeenNthCalledWith(1, '9007199254740993');
    current.resolve([{ tableName: 'current' }]);
    await current.promise;
    await nextTick();
    old.resolve([{ tableName: 'stale' }]);
    await old.promise;
    await nextTick();
    expect(state.sourceTables).toEqual([{ tableName: 'current' }]);
    expect(state.sourceTablesLoading).toBe(false);
  });

  it('切换源表后不会用旧字段覆盖当前字段', async () => {
    const old = deferred<any[]>();
    const current = deferred<any[]>();
    vi.mocked(getSafetyStaticTaskColumnList).mockReturnValueOnce(old.promise).mockReturnValueOnce(current.promise);
    state.model.sourceDatabaseId = '9007199254740993';
    state.handleSourceTableChange('old');
    state.handleSourceTableChange('current');
    current.resolve([{ columnName: 'current' }]);
    await current.promise;
    await nextTick();
    old.resolve([{ columnName: 'stale' }]);
    await old.promise;
    await nextTick();
    expect(state.sourceColumns).toEqual([{ columnName: 'current' }]);
  });

  it('拒绝未填完整的脱敏字段，避免静默忽略配置', async () => {
    Object.assign(state.model, { taskName: '测试', sourceDatabaseId: '9007199254740993',
      sourceTableName: 'source', targetDatabaseId: '9007199254740995', targetTableName: 'target', writeMode: 'APPEND' });
    state.fieldRules = [{ columnName: 'phone', ruleId: 1 }, { columnName: 'identity', ruleId: undefined }];
    await harness.options.onConfirm();
    expect(harness.error).toHaveBeenCalledWith('请为每一行选择字段和脱敏规则，或删除未完成的行');
    expect(createSafetyStaticTask).not.toHaveBeenCalled();
  });

  it('提交时保持数据源和脱敏规则的大编号不变', async () => {
    Object.assign(state.model, { taskName: '测试', sourceDatabaseId: '9007199254740993',
      sourceTableName: 'source', targetDatabaseId: '9007199254740995', targetTableName: 'target', writeMode: 'APPEND' });
    state.fieldRules = [{ columnName: 'phone', ruleId: '9007199254740997' }];
    await harness.options.onConfirm();
    expect(createSafetyStaticTask).toHaveBeenCalledWith(expect.objectContaining({
      sourceDatabaseId: '9007199254740993', targetDatabaseId: '9007199254740995',
      fieldRules: [{ columnName: 'phone', ruleId: '9007199254740997' }],
    }));
  });

  it('关闭弹窗后忽略迟到的字段请求', async () => {
    const request = deferred<any[]>();
    vi.mocked(getSafetyStaticTaskColumnList).mockReturnValueOnce(request.promise);
    state.model.sourceDatabaseId = 1;
    state.handleSourceTableChange('old');
    await harness.options.onOpenChange(false);
    request.resolve([{ columnName: 'stale' }]);
    await request.promise;
    await nextTick();
    expect(state.sourceColumns).toEqual([]);
  });


  it('规则超过一页时继续加载，避免只能选择前 100 条', async () => {
    const first = Array.from({ length: 100 }, (_, i) => ({ id: i + 1, currentVerId: 1 }));
    vi.mocked(getRulePage).mockResolvedValueOnce({ list: first as any, total: 101 })
      .mockResolvedValueOnce({ list: [{ id: 101, currentVerId: 1 }] as any, total: 101 });
    await harness.options.onOpenChange(true);
    expect(getRulePage).toHaveBeenLastCalledWith({ pageNo: 2, pageSize: 100, status: 0 });
    expect(state.rules).toHaveLength(101);
  });


  it('异常分页返回会提示并停止加载', async () => {
    vi.mocked(getRulePage).mockResolvedValueOnce({ list: [], total: 101 });
    await harness.options.onOpenChange(true);
    expect(harness.error).toHaveBeenCalledWith('脱敏规则列表未能完整读取，请重新打开表单重试');
    expect(state.rules).toEqual([]);
    expect(getRulePage).toHaveBeenCalledTimes(2);
  });

  it('规则数量超出上限时提示而不继续请求', async () => {
    vi.mocked(getRulePage).mockResolvedValueOnce({ list: [], total: 10_001 });
    await harness.options.onOpenChange(true);
    expect(harness.error).toHaveBeenCalledWith('脱敏规则数量异常或超过 10000 条，请联系管理员');
    expect(getRulePage).toHaveBeenCalledTimes(2);
  });

});
