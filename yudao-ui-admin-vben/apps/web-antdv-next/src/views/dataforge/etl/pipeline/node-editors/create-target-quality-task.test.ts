import type { App } from 'vue';

import {
  computed,
  createApp,
  defineComponent,
  h,
  nextTick,
  reactive,
} from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as database from '#/api/dataforge/database';
import * as environment from '#/api/dataforge/etl/environment';
import * as api from '#/api/governance/quality/etl-task';
import * as rules from '#/api/governance/quality/rule';
import * as tasks from '#/api/governance/quality/task';

import { etlEnvironmentKey } from '../environment-context';
import Creator from './create-target-quality-task.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: '', denied: '' },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '7' } }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      !codes.includes(harness.access.denied),
  }),
}));
vi.mock('#/api/dataforge/database', () => ({ getDatabaseSimpleList: vi.fn() }));
vi.mock('#/api/dataforge/etl/environment', () => ({
  getEtlEnvironmentBindings: vi.fn(),
}));
vi.mock('#/api/governance/quality/etl-task', () => ({
  createEtlQualityTask: vi.fn(),
  getEtlQualityTaskCreation: vi.fn(),
}));
vi.mock('#/api/governance/quality/rule', () => ({
  getQualityRulePage: vi.fn(),
  getQualityStrategyList: vi.fn(),
}));
vi.mock('#/api/governance/quality/task', () => ({ getQualityTask: vi.fn() }));
vi.mock('antdv-next', () => {
  const slot = (tag: string) =>
    defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h(tag, attrs, slots.default?.()),
    });
  return {
    Button: slot('button'),
    Checkbox: slot('span'),
    Alert: defineComponent({
      props: ['message'],
      setup: (props) => () => h('p', props.message),
    }),
    Modal: defineComponent({
      props: ['open'],
      setup:
        (props, { slots, attrs }) =>
        () =>
          props.open
            ? h('section', [
                slots.default?.(),
                h('button', { onClick: attrs.onCancel }, '取消弹窗'),
              ])
            : null,
    }),
    Input: defineComponent({
      props: ['value'],
      emits: ['update:value'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h('input', {
            ...attrs,
            value: props.value,
            onInput: (e: Event) =>
              emit('update:value', (e.target as HTMLInputElement).value),
          }),
    }),
    Select: defineComponent({
      props: ['value', 'options'],
      emits: ['update:value'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: props.value?.[0],
              onChange: (e: Event) =>
                emit('update:value', [(e.target as HTMLSelectElement).value]),
            },
            props.options.map((o: { label: string; value: string }) =>
              h('option', { value: o.value }, o.label),
            ),
          ),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
const created = vi.fn();
const receipt = (requestId: string) => ({
  requestId,
  taskId: '9007199254740997',
  checkObjectId: '9007199254740999',
  taskName: '验收',
  taskStatus: 0,
  replayed: true,
});
async function flush() {
  for (let i = 0; i < 12; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
function button(label: string) {
  return [...host.querySelectorAll('button')].find(
    (e) => e.textContent?.trim() === label,
  );
}
async function click(label: string) {
  expect(button(label)).toBeDefined();
  button(label)!.click();
  await flush();
}
async function mount(config?: Record<string, unknown>) {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Creator, {
    config: config ?? { databaseId: '9007199254740993', tableName: '"orders"' },
    onCreated: created,
  });
  app.provide(
    etlEnvironmentKey,
    computed(() => '9007199254740991'),
  );
  app.mount(host);
  await flush();
}
async function chooseRule() {
  const select = host.querySelector('select')!;
  select.value = '9007199254740995';
  select.dispatchEvent(new Event('change'));
  await flush();
}
beforeEach(() => {
  vi.resetAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: '', denied: '' });
  vi.mocked(database.getDatabaseSimpleList).mockResolvedValue([
    { id: '9007199254740993', databaseSchema: 'public' },
  ] as any);
  vi.mocked(environment.getEtlEnvironmentBindings).mockResolvedValue([
    {
      databaseId: '9007199254740993',
      schemaName: 'public',
      logicalKey: 'warehouse',
    },
  ] as any);
  vi.mocked(rules.getQualityStrategyList).mockResolvedValue([
    { strategyKey: 'NOT_NULL' },
  ] as any);
  vi.mocked(rules.getQualityRulePage).mockResolvedValue({
    list: [
      {
        id: '9007199254740995',
        ruleName: '非空',
        strategyKey: 'NOT_NULL',
        status: 0,
      },
      { id: 2, ruleName: '未支持', strategyKey: 'OTHER', status: 0 },
    ],
    total: 2,
  } as any);
  vi.mocked(api.createEtlQualityTask).mockImplementation(async (req) =>
    receipt(req.requestId),
  );
  vi.mocked(tasks.getQualityTask).mockResolvedValue({
    id: '9007199254740997',
    taskStatus: 1,
  } as any);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});
describe('目标独立质量任务生成', () => {
  it('目标变更保留未决请求，旧目标回执只展示编号而不绑定新目标', async () => {
    const config = reactive({
      databaseId: '9007199254740993',
      tableName: '"orders"',
    });
    let done!: (value: ReturnType<typeof receipt>) => void;
    vi.mocked(api.createEtlQualityTask).mockReturnValue(
      new Promise((resolve) => {
        done = resolve;
      }),
    );
    vi.mocked(api.getEtlQualityTaskCreation).mockImplementation(async (id) =>
      receipt(id),
    );
    await mount(config);
    await click('生成独立 SQL 质量任务');
    await chooseRule();
    await click('创建并回填任务');
    const request = vi.mocked(api.createEtlQualityTask).mock.calls[0]![0];
    config.tableName = '"customers"';
    await flush();
    done(receipt(request.requestId));
    await flush();
    expect(created).not.toHaveBeenCalled();
    await click('生成独立 SQL 质量任务');
    expect(api.getEtlQualityTaskCreation).toHaveBeenCalledWith(
      request.requestId,
    );
    expect(host.textContent).toContain(
      '原目标质量任务 #9007199254740997 已创建',
    );
    expect(host.textContent).toContain('当前目标已变化，未回填');
    expect(created).not.toHaveBeenCalled();
    expect(api.createEtlQualityTask).toHaveBeenCalledOnce();
  });
  it('超时后切走又回到原目标，核查仍复用原请求并正确回填', async () => {
    const config = reactive({
      databaseId: '9007199254740993',
      tableName: '"orders"',
    });
    vi.mocked(api.createEtlQualityTask).mockRejectedValue(new Error('timeout'));
    vi.mocked(api.getEtlQualityTaskCreation).mockImplementation(async (id) =>
      receipt(id),
    );
    await mount(config);
    await click('生成独立 SQL 质量任务');
    await chooseRule();
    await click('创建并回填任务');
    const request = vi.mocked(api.createEtlQualityTask).mock.calls[0]![0];
    config.tableName = '"customers"';
    await flush();
    config.tableName = '"orders"';
    await flush();
    await click('生成独立 SQL 质量任务');
    expect(api.getEtlQualityTaskCreation).toHaveBeenCalledWith(
      request.requestId,
    );
    expect(created).toHaveBeenCalledOnce();
    expect(api.createEtlQualityTask).toHaveBeenCalledOnce();
  });
  it('逻辑连接解析、SQL引用和大编号原样提交；按当前状态提示，不将旧回执当上线状态', async () => {
    await mount({ logicalDatabaseKey: 'warehouse', tableName: '"orders"' });
    await click('生成独立 SQL 质量任务');
    expect(environment.getEtlEnvironmentBindings).toHaveBeenCalledWith(
      '9007199254740991',
    );
    expect(host.textContent).toContain('普通小写标识符');
    expect(host.textContent).not.toContain('未支持（OTHER）');
    await chooseRule();
    await click('创建并回填任务');
    expect(api.createEtlQualityTask).toHaveBeenCalledWith(
      expect.objectContaining({
        databaseId: '9007199254740993',
        schemaName: 'public',
        tableName: '"orders"',
        ruleIds: ['9007199254740995'],
        activate: true,
      }),
    );
    expect(created).toHaveBeenCalledOnce();
    expect(tasks.getQualityTask).toHaveBeenCalledWith('9007199254740997');
    expect(host.textContent).toContain('当前任务未上线');
  });
  it('超时保留原payload与请求编号，关闭弹窗后查不到回执也只原样重试', async () => {
    vi.mocked(api.createEtlQualityTask)
      .mockRejectedValueOnce(new Error('timeout'))
      .mockImplementation(async (req) => receipt(req.requestId));
    vi.mocked(api.getEtlQualityTaskCreation).mockResolvedValue(null);
    await mount();
    await click('生成独立 SQL 质量任务');
    await chooseRule();
    await click('创建并回填任务');
    const original = vi.mocked(api.createEtlQualityTask).mock.calls[0]![0];
    expect(created).not.toHaveBeenCalled();
    await click('取消弹窗');
    await click('生成独立 SQL 质量任务');
    expect(api.getEtlQualityTaskCreation).toHaveBeenCalledWith(
      original.requestId,
    );
    expect(host.textContent).toContain('不能据此认定原请求未执行');
    await click('原样重试创建');
    expect(vi.mocked(api.createEtlQualityTask).mock.calls[1]![0]).toEqual(
      original,
    );
  });
  it('查询到成功回执后不重复创建，当前状态查询失败如实保留回填结果', async () => {
    vi.mocked(api.createEtlQualityTask).mockRejectedValue(new Error('timeout'));
    vi.mocked(api.getEtlQualityTaskCreation).mockImplementation(async (id) =>
      receipt(id),
    );
    vi.mocked(tasks.getQualityTask).mockRejectedValue(new Error('denied'));
    await mount();
    await click('生成独立 SQL 质量任务');
    await chooseRule();
    await click('创建并回填任务');
    await click('核查创建结果');
    expect(api.createEtlQualityTask).toHaveBeenCalledOnce();
    expect(created).toHaveBeenCalledOnce();
    expect(host.textContent).toContain('不能据原回执认定当前已上线');
  });
  it('目标非默认schema不创建；四项权限必须同时具备', async () => {
    await mount({
      databaseId: '9007199254740993',
      schemaName: 'other',
      tableName: 'orders',
    });
    await click('生成独立 SQL 质量任务');
    expect(host.textContent).toContain('当前仅支持连接默认 Schema');
    expect(button('创建并回填任务')).toBeUndefined();
    harness.access.denied = 'governance:quality-check-object:create';
    await flush();
    expect(button('生成独立 SQL 质量任务')).toBeUndefined();
  });
  it('租户切换丢弃晚到回执，不回填另一租户任务', async () => {
    let done!: (value: ReturnType<typeof receipt>) => void;
    vi.mocked(api.createEtlQualityTask).mockReturnValue(
      new Promise((resolve) => {
        done = resolve;
      }),
    );
    await mount();
    await click('生成独立 SQL 质量任务');
    await chooseRule();
    await click('创建并回填任务');
    const request = vi.mocked(api.createEtlQualityTask).mock.calls[0]![0];
    harness.access.refreshToken = '2';
    await flush();
    done(receipt(request.requestId));
    await flush();
    expect(created).not.toHaveBeenCalled();
    expect(tasks.getQualityTask).not.toHaveBeenCalled();
  });
});
