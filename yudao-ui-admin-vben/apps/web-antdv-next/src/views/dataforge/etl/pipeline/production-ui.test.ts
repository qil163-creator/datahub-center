import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as databases from '#/api/dataforge/database';
import * as environments from '#/api/dataforge/etl/environment';
import * as lifecycle from '#/api/dataforge/etl/lifecycle';
import * as subflows from '#/api/dataforge/etl/subflow';
import * as targets from '#/api/dataforge/etl/target-batch';
import * as templates from '#/api/dataforge/etl/template';

import TargetBatches from '../record/target-batches.vue';
import EnvironmentField from './environment-field.vue';
import ManagedOutput from './node-editors/managed-db-output.vue';
import ReadDb from './node-editors/read-db.vue';
import SubPipeline from './node-editors/sub-pipeline.vue';
import TemplateLibrary from './template-library.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '1' } }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/utils', () => ({ formatDateTime: () => '2026-10-03' }));
vi.mock('./node-editors/create-target-quality-task.vue', () => ({
  default: { render: () => null },
}));
vi.mock('#/api/dataforge/etl/template', () => ({
  getEtlTemplatePage: vi.fn(),
  getEtlTemplate: vi.fn(),
  createEtlTemplate: vi.fn(),
  deleteEtlTemplate: vi.fn(),
}));
vi.mock('#/api/dataforge/etl/target-batch', () => ({
  getEtlTargetBatches: vi.fn(),
  getEtlReplayStatus: vi.fn(),
  getEtlTargetRows: vi.fn(),
  retryEtlTargetBatch: vi.fn(),
  retryEtlReplayEvidence: vi.fn(),
}));
vi.mock('#/api/dataforge/etl/subflow', () => ({
  getSubflowContract: vi.fn(),
  getSubflowPipelinePage: vi.fn(),
  getSubflowVersionPage: vi.fn(),
}));
vi.mock('#/api/dataforge/database', () => ({
  getDatabaseSimpleList: vi.fn().mockResolvedValue([]),
  getDatabaseTableList: vi.fn().mockResolvedValue([]),
}));
vi.mock('#/api/governance/quality/task', () => ({
  getQualityTaskSimpleList: vi.fn().mockResolvedValue([]),
}));
vi.mock('#/api/dataforge/etl/environment', () => ({
  getEtlEnvironments: vi.fn(),
  getEtlEnvironmentBindings: vi.fn(),
  saveEtlEnvironment: vi.fn(),
  saveEtlEnvironmentBinding: vi.fn(),
  deleteEtlEnvironment: vi.fn(),
  deleteEtlEnvironmentBinding: vi.fn(),
}));
vi.mock('#/api/dataforge/etl/lifecycle', () => ({
  getEtlArchive: vi.fn(),
  archiveEtlRecord: vi.fn(),
  restoreEtlArchive: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const slot = (tag: string) =>
    defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h(tag, attrs, slots.default?.()),
    });
  const input = defineComponent({
    props: ['value'],
    emits: ['update:value'],
    setup:
      (props, { emit, attrs }) =>
      () =>
        h('input', {
          ...attrs,
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
  });
  const dialog = defineComponent({
    props: { open: Boolean },
    setup:
      (props, { attrs, slots }) =>
      () =>
        props.open
          ? h('section', [
              slots.default?.(),
              attrs.onOk ? h('button', { onClick: attrs.onOk }, '确认') : null,
            ])
          : null,
  });
  return {
    Button: slot('button'),
    Tag: slot('span'),
    Input: input,
    TextArea: input,
    InputNumber: input,
    Checkbox: slot('span'),
    RadioGroup: slot('span'),
    Select: defineComponent({
      props: ['value', 'options'],
      emits: ['change', 'update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              onChange: (event: Event) => {
                const value = (event.target as HTMLSelectElement).value;
                emit('change', value);
                emit('update:value', value);
              },
            },
            (props.options ?? []).map((item: any) =>
              h('option', { value: item.value }, item.label),
            ),
          ),
    }),
    Drawer: dialog,
    Modal: dialog,
    Popconfirm: slot('div'),
    Alert: defineComponent({
      props: ['message'],
      setup: (props) => () => h('p', props.message),
    }),
    Table: defineComponent({
      props: { dataSource: { type: Array, default: () => [] } },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'div',
            props.dataSource.map((record) =>
              slots.bodyCell?.({ record, column: { key: 'actions' } }),
            ),
          ),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement;
const template = {
  id: '9007199254740993',
  name: '共享片段',
  nodeCount: 1,
  ownerUserId: '7',
  canDelete: true,
  createTime: 1,
};
const graph = {
  nodes: [{ id: 'a', properties: { toolCode: 'generate-records' } }],
  edges: [],
};
async function flush() {
  for (let i = 0; i < 8; i++) {
    await Promise.resolve();
    await nextTick();
  }
}
function mount(component: any, props: Record<string, any> = {}) {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(component, props);
  return app.mount(host) as any;
}
function button(text: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  )!;
}
function input(index: number, value: string) {
  const element = host.querySelectorAll('input')[index]!;
  element.value = value;
  element.dispatchEvent(new Event('input', { bubbles: true }));
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.mocked(databases.getDatabaseTableList).mockResolvedValue([]);
  vi.mocked(targets.getEtlReplayStatus).mockResolvedValue(null);
  vi.mocked(lifecycle.getEtlArchive).mockResolvedValue(null);

  vi.mocked(environments.getEtlEnvironments).mockResolvedValue([]);
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  vi.mocked(templates.getEtlTemplatePage).mockResolvedValue({
    list: [template],
    total: 1,
  });
});

describe('环境连接修订与租户隔离', () => {
  it('受管 PostgreSQL 元数据表名作为引用保存，保留大小写、点和内部引号', async () => {
    vi.mocked(databases.getDatabaseTableList).mockResolvedValue([
      { tableName: 'Mixed.Table' },
      { tableName: 'A"B' },
    ] as any);
    const config = reactive<Record<string, any>>({ databaseId: '1' });
    mount(ManagedOutput, { config });
    await flush();
    const table = [...host.querySelectorAll('select')].find((item) =>
      [...item.options].some((option) => option.text === 'Mixed.Table'),
    )!;
    expect([...table.options].map((option) => option.value)).toEqual([
      '"Mixed.Table"',
      '"A""B"',
    ]);
    table.value = '"A""B"';
    table.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
    expect(config.tableName).toBe('"A""B"');
  });
  it('逻辑连接只使用整表读取，已有自定义 SQL 不会继续执行', async () => {
    const config = reactive({
      logicalDatabaseKey: 'warehouse',
      ifSql: 1,
      sql: 'SELECT * FROM other.source',
    });
    mount(ReadDb, { config });
    await flush();
    expect(config.ifSql).toBe(0);
    expect(host.textContent).toContain('环境逻辑连接当前支持整表读取');
  });
  it('编辑保留无损环境 ID 与 CAS 修订号，保存失败不伪装为成功', async () => {
    vi.mocked(environments.getEtlEnvironments).mockResolvedValue([
      {
        id: '9007199254740993',
        revision: 7,
        code: 'prod',
        name: '生产',
        enabled: true,
      },
    ]);
    vi.mocked(environments.saveEtlEnvironment).mockRejectedValue(
      new Error('conflict'),
    );
    mount(EnvironmentField);
    await flush();
    button('管理环境').click();
    await flush();
    button('编辑环境').click();
    await flush();
    input(1, '生产更新');
    await flush();
    button('确认').click();
    await flush();
    expect(environments.saveEtlEnvironment).toHaveBeenCalledWith({
      id: '9007199254740993',
      revision: 7,
      code: 'prod',
      name: '生产更新',
      enabled: true,
    });
    expect(host.textContent).toContain('版本已变化');
  });
  it('切租户后清除旧环境选项并忽略迟到的列表', async () => {
    let finish!: (value: any) => void;
    vi.mocked(environments.getEtlEnvironments)
      .mockImplementationOnce(
        () =>
          new Promise((resolve) => {
            finish = resolve;
          }),
      )
      .mockResolvedValue([]);
    mount(EnvironmentField);
    await flush();
    harness.access.refreshToken = '2';
    await flush();
    finish([
      { id: '77', revision: 1, code: 'old', name: '旧会话', enabled: true },
    ]);
    await flush();
    expect(host.textContent).not.toContain('旧会话');
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('服务器模板和子流程交互', () => {
  it('应用模板重新读取授权，租户切换丢弃迟到响应', async () => {
    let resolve!: (value: any) => void;
    vi.mocked(templates.getEtlTemplate).mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const apply = vi.fn();
    const view = mount(TemplateLibrary, { canEdit: true, onApply: apply });
    await view.open();
    await flush();
    button('应用到画布').click();
    await flush();
    expect(templates.getEtlTemplate).toHaveBeenCalledWith(template.id);
    harness.access.refreshToken = '2';
    await flush();
    resolve({ ...template, ...graph });
    await flush();
    expect(apply).not.toHaveBeenCalled();
  });
  it('创建响应不确定时重试相同请求，避免重复模板', async () => {
    vi.mocked(templates.createEtlTemplate)
      .mockRejectedValueOnce(new Error('timeout'))
      .mockResolvedValueOnce(template.id);
    const view = mount(TemplateLibrary, { canEdit: true });
    view.openCreate(graph);
    await flush();
    input(0, '共享模板');
    await flush();
    button('确认').click();
    await flush();
    expect(host.textContent).toContain('保存未确认');
    expect(host.querySelector('input')!.disabled).toBe(true);
    const first = vi.mocked(templates.createEtlTemplate).mock.calls[0]![0];
    button('确认').click();
    await flush();
    expect(templates.createEtlTemplate).toHaveBeenNthCalledWith(2, first);
    expect(host.textContent).toContain('应用到画布');
  });
  it('旧版本契约响应不覆盖新选择，配置保留旧的有效引用', async () => {
    let resolve!: (value: any) => void;
    vi.mocked(subflows.getSubflowContract).mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const config = reactive({
      pipelineId: '7',
      versionId: '99',
      parameters: { value: 'old' },
    });
    mount(SubPipeline, { config });
    await flush();
    input(1, '9007199254740993');
    await flush();
    button('读取契约').click();
    await flush();
    input(1, '9007199254740995');
    await flush();
    resolve({
      pipelineId: '7',
      versionId: '9007199254740993',
      inputs: [],
      outputs: [],
      parameters: [],
      contentHash: 'hash',
    });
    await flush();
    expect(config.versionId).toBe('99');
  });
});

describe('目标批次准入', () => {
  it('归档提交保留原因与天数，刷新确认后立即关闭消费入口', async () => {
    vi.mocked(targets.getEtlTargetBatches).mockResolvedValue([
      { id: '77', commitStatus: 'COMMITTED', qualityStatus: 'PASSED' } as any,
    ]);
    vi.mocked(lifecycle.getEtlArchive)
      .mockResolvedValueOnce(null)
      .mockResolvedValue({
        recordId: '99',
        actorId: '7',
        reason: '已完成整改验收',
        archivedAt: 1,
        evidenceAccessUntil: 2,
      });
    vi.mocked(lifecycle.archiveEtlRecord).mockResolvedValue(true);
    const view = mount(TargetBatches);
    await view.open('99');
    await flush();
    expect(button('查看准入数据').disabled).toBe(false);
    button('归档运行').click();
    await flush();
    input(1, '已完成整改验收');
    await flush();
    button('确认').click();
    await flush();
    expect(lifecycle.archiveEtlRecord).toHaveBeenCalledWith({
      recordId: '99',
      reason: '已完成整改验收',
      retentionDays: 365,
    });
    expect(button('查看准入数据').disabled).toBe(true);
    expect(targets.retryEtlTargetBatch).not.toHaveBeenCalled();
  });
  it('归档后禁用受控读取并保留恢复入口', async () => {
    vi.mocked(targets.getEtlTargetBatches).mockResolvedValue([
      { id: '77', commitStatus: 'COMMITTED', qualityStatus: 'PASSED' } as any,
    ]);
    vi.mocked(lifecycle.getEtlArchive).mockResolvedValue({
      recordId: '99',
      actorId: '7',
      reason: '已结项',
      archivedAt: 1,
      evidenceAccessUntil: 2,
    });
    const view = mount(TargetBatches);
    await view.open('99');
    await flush();
    expect(button('查看准入数据').disabled).toBe(true);
    expect(button('恢复归档')).toBeDefined();
  });
  it('回放证据失败只重报治理关联，既不恢复目标批次也不创建新回放', async () => {
    vi.mocked(targets.getEtlTargetBatches).mockResolvedValue([]);
    vi.mocked(targets.getEtlReplayStatus).mockResolvedValue({
      recordId: '9007199254740993',
      sourceRecordId: '99',
      workOrderId: '11',
      submissionId: '12',
      snapshotMode: 'ORIGINAL',
      attachStatus: 'FAILED',
      attemptCount: 3,
    });
    vi.mocked(targets.retryEtlReplayEvidence).mockResolvedValue(true);
    const view = mount(TargetBatches);
    await view.open('9007199254740993');
    await flush();
    button('重报复检证据').click();
    await flush();
    expect(host.textContent).toContain('不生成新的回放');
    input(0, '治理服务已恢复');
    await flush();
    button('确认').click();
    await flush();
    expect(targets.retryEtlReplayEvidence).toHaveBeenCalledWith({
      recordId: '9007199254740993',
      reason: '治理服务已恢复',
    });
    expect(targets.retryEtlTargetBatch).not.toHaveBeenCalled();
  });
  it('未通过质量不得发起读取，通过批次按无损ID读取', async () => {
    const base = {
      id: '9007199254740993',
      nodeNo: 'out',
      commitStatus: 'COMMITTED',
      qualityStatus: 'FAILED',
    } as any;
    vi.mocked(targets.getEtlTargetBatches).mockResolvedValue([
      base,
      { ...base, id: '9007199254740995', qualityStatus: 'PASSED' },
    ]);
    vi.mocked(targets.getEtlTargetRows).mockResolvedValue({
      rows: [{ id: 'masked' }],
      nextAfterRow: 1,
      hasMore: false,
    });
    const view = mount(TargetBatches);
    await view.open('999');
    await flush();
    const buttons = [...host.querySelectorAll('button')].filter(
      (item) => item.textContent?.trim() === '查看准入数据',
    );
    expect(buttons[0]!.disabled).toBe(true);
    buttons[1]!.click();
    await flush();
    expect(targets.getEtlTargetRows).toHaveBeenCalledWith(
      '9007199254740995',
      0,
    );
    expect(host.textContent).toContain('masked');
  });
  it('恢复要求原因且发送期间去重，原实例语义保持明确', async () => {
    vi.mocked(targets.getEtlTargetBatches).mockResolvedValue([
      { id: '77', commitStatus: 'FAILED', qualityStatus: 'PENDING' } as any,
    ]);
    let resolve!: (value: boolean) => void;
    vi.mocked(targets.retryEtlTargetBatch).mockReturnValue(
      new Promise((done) => {
        resolve = done;
      }),
    );
    const view = mount(TargetBatches);
    await view.open('999');
    await flush();
    button('恢复对账').click();
    await flush();
    button('确认').click();
    await flush();
    expect(targets.retryEtlTargetBatch).not.toHaveBeenCalled();
    input(0, '已核查目标回执');
    await flush();
    button('确认').click();
    button('确认').click();
    await flush();
    expect(targets.retryEtlTargetBatch).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('不重跑 ETL');
    resolve(true);
    await flush();
  });
});
