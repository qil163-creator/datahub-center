/* eslint-disable vue/one-component-per-file, vue/require-prop-types -- 测试控件替身驱动实际选择、取消与提交边界。 */
import type { App } from 'vue';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import WorkbenchLineagePanel from './WorkbenchLineagePanel.vue';

const mocks = vi.hoisted(() => ({ preview: vi.fn(), tables: vi.fn() }));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'test-session' }),
}));
vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('#/api/dataforge/database', () => ({
  getDatabaseTableList: mocks.tables,
}));
vi.mock('#/api/dataforge/dev', async (importActual) => ({
  ...(await importActual<object>()),
  getDevTaskLineagePreview: mocks.preview,
}));
vi.mock('antdv-next', async () => {
  const { defineComponent, h } = await import('vue');
  return {
    Input: defineComponent({
      props: ['value', 'disabled'],
      setup:
        (props, { attrs }) =>
        () =>
          h('input', {
            ...attrs,
            value: props.value,
            disabled: props.disabled,
          }),
    }),
    Select: defineComponent({
      inheritAttrs: false,
      props: ['value', 'options', 'disabled', 'mode'],
      emits: ['update:value'],
      setup:
        (props, { emit, attrs }) =>
        () =>
          h(
            'select',
            {
              ...attrs,
              value: props.value,
              disabled: props.disabled,
              multiple: props.mode === 'multiple',
              onChange: (event: Event) =>
                emit(
                  'update:value',
                  props.mode === 'multiple'
                    ? [
                        ...(event.target as HTMLSelectElement).selectedOptions,
                      ].map((item) => item.value)
                    : (event.target as HTMLSelectElement).value,
                ),
            },
            (props.options ?? []).map(
              (item: { disabled?: boolean; label: string; value: string }) =>
                h(
                  'option',
                  { value: item.value, disabled: item.disabled },
                  item.label,
                ),
            ),
          ),
    }),
    Button: defineComponent({
      props: ['disabled'],
      setup:
        (props, { slots, attrs }) =>
        () =>
          h(
            'button',
            { ...attrs, disabled: props.disabled },
            slots.default?.(),
          ),
    }),
    Modal: defineComponent({
      props: ['open'],
      setup:
        (props, { slots }) =>
        () =>
          props.open
            ? h('div', { 'data-lineage-dialog': '' }, slots.default?.())
            : null,
    }),
    Tag: defineComponent({
      setup:
        (_props, { slots }) =>
        () =>
          h('span', { 'data-tag': '' }, slots.default?.()),
    }),
  };
});
let app: App | undefined;
let host: HTMLDivElement | undefined;
beforeEach(() => {
  mocks.preview.mockReset();
  mocks.tables.mockReset();
  mocks.tables.mockResolvedValue([
    { tableName: 'source_table' },
    { tableName: 'target_table' },
  ]);
});
afterEach(() => {
  app?.unmount();
  host?.remove();
});
async function flush() {
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
async function mount(
  task: DataforgeDevApi.Task = {},
  extra: Record<string, unknown> = {},
) {
  host = document.createElement('div');
  document.body.append(host);
  const saved = vi.fn();
  const cancel = vi.fn();
  app = createApp(
    defineComponent({
      setup: () => () =>
        h(WorkbenchLineagePanel, {
          task,
          databases: [
            { value: '9007199254740993', label: '受管连接A' },
            { value: '2', label: '受管连接B' },
          ],
          onSave: saved,
          onCancel: cancel,
          ...extra,
        }),
    }),
  );
  app.mount(host);
  await flush();
  return { saved, cancel };
}
function button(label: string) {
  return [...host!.querySelectorAll<HTMLButtonElement>('button')].find(
    (item) => item.textContent?.trim() === label,
  )!;
}
async function select(label: string, value: string) {
  const control = host!.querySelector<HTMLSelectElement>(
    `select[aria-label="${label}"]`,
  )!;
  control.value = value;
  control.dispatchEvent(new Event('change', { bubbles: true }));
  await flush();
}
async function add(direction: '上游' | '下游', table = 'source_table') {
  [...host!.querySelectorAll<HTMLButtonElement>('[role="tab"]')]
    .find((item) => item.textContent?.includes(direction))!
    .click();
  await nextTick();
  button(`添加${direction}节点`).click();
  await nextTick();
  await select('数据连接信息', '9007199254740993');
  await select('对象表名', table);
}

describe('工作台真实表血缘维护', () => {
  it('从受管连接读取真实表清单并保留大ID及已有执行配置', async () => {
    const task: DataforgeDevApi.Task = {
      id: '12',
      name: '任务A',
      ownerName: '责任人A',
      contactNumber: '13800000000',
      executionConfig: { priority: 'HIGH' },
    };
    const { saved } = await mount(task);
    await add('上游');
    button('保存').click();
    expect(mocks.tables).toHaveBeenCalledWith('9007199254740993');
    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({
        executionConfig: expect.objectContaining({
          priority: 'HIGH',
          tableLineage: {
            upstream: [
              { databaseId: '9007199254740993', tableName: 'source_table' },
            ],
            downstream: [],
          },
        }),
      }),
    );
    expect(task.executionConfig!.tableLineage).toBeUndefined();
    expect(
      host!.querySelector<HTMLInputElement>('[aria-label="责任人"]')!.value,
    ).toBe('责任人A');
  });
  it('下游表与任务依赖分别保存，预览和详情只呈现已选关系', async () => {
    const { saved } = await mount(
      { id: '12', name: '任务A', dependencyTaskIds: ['9007199254740995'] },
      { tasks: [{ id: '9007199254740995', name: '前置任务' }] },
    );
    await add('下游', 'target_table');
    button('血缘详情').click();
    await nextTick();
    expect(host!.querySelector('[data-lineage-dialog]')!.textContent).toContain(
      'target_table',
    );
    button('保存').click();
    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({
        dependencyTaskIds: ['9007199254740995'],
        executionConfig: expect.objectContaining({
          tableLineage: {
            upstream: [],
            downstream: [
              { databaseId: '9007199254740993', tableName: 'target_table' },
            ],
          },
        }),
      }),
    );
  });
  it('未选择真实表的节点不能提交，删除节点恢复空关系', async () => {
    const { saved } = await mount();
    button('添加上游节点').click();
    await nextTick();
    button('保存').click();
    await nextTick();
    expect(saved).not.toHaveBeenCalled();
    expect(host!.querySelector('[role="alert"]')!.textContent).toContain(
      '真实数据表',
    );
    host!
      .querySelector<HTMLButtonElement>('[aria-label="删除上游节点 1"]')!
      .click();
    await nextTick();
    button('保存').click();
    expect(saved).toHaveBeenCalledWith(
      expect.objectContaining({
        executionConfig: expect.objectContaining({
          tableLineage: { upstream: [], downstream: [] },
        }),
      }),
    );
  });
  it('未保存的任务提示先保存，不在前端从SQL猜测关系', async () => {
    await mount({ content: 'SELECT * FROM should_not_be_inferred' });
    button('重新解析').click();
    await nextTick();
    expect(host!.querySelector('[role="status"]')!.textContent).toContain(
      '请先保存任务',
    );
    expect(host!.textContent).not.toContain('should_not_be_inferred');
    expect(mocks.preview).not.toHaveBeenCalled();
    expect(mocks.tables).not.toHaveBeenCalled();
  });
  it('重新解析展示后端自动解析结果，不改动手工维护的链路', async () => {
    mocks.preview.mockResolvedValue({
      status: 'PARTIAL',
      statementCount: 1,
      readTables: [{ datasourceId: '2', schemaName: 'ods', tableName: 'orders' }],
      writeTables: [{ datasourceId: '2', schemaName: 'dw', tableName: 'fact' }],
      tableEdges: [],
      columnEdges: [],
      columnEdgeTotal: 3,
      issues: [{ code: 'WILDCARD_UNEXPANDED', message: 'ods.orders.* 缺少表结构', statementIndex: 1 }],
    });
    await mount({ id: '9007199254740993', content: 'INSERT INTO dw.fact SELECT * FROM ods.orders' });
    button('重新解析').click();
    await flush();
    expect(mocks.preview).toHaveBeenCalledWith('9007199254740993', expect.any(AbortSignal));
    const result = host!.querySelector('[aria-label="自动解析结果"]')!;
    expect(result.textContent).toContain('部分解析');
    expect(result.textContent).toContain('ods.orders');
    expect(result.textContent).toContain('dw.fact');
    expect(result.textContent).toContain('3 条');
    expect(result.textContent).toContain('第 1 条语句');
    expect(mocks.tables).not.toHaveBeenCalled();
  });
  it('切换连接后，较早表清单响应不能覆盖当前选择', async () => {
    let resolveA!: (value: { tableName: string }[]) => void;
    mocks.tables.mockImplementation((id: string) =>
      id === '9007199254740993'
        ? new Promise((resolve) => {
            resolveA = resolve;
          })
        : Promise.resolve([{ tableName: 'current_b_table' }]),
    );
    await mount();
    button('添加上游节点').click();
    await nextTick();
    await select('数据连接信息', '9007199254740993');
    await select('数据连接信息', '2');
    resolveA([{ tableName: 'stale_a_table' }]);
    await flush();
    const control = host!.querySelector<HTMLSelectElement>(
      '[aria-label="对象表名"]',
    )!;
    expect(control.textContent).toContain('current_b_table');
    expect(control.textContent).not.toContain('stale_a_table');
  });
  it('上线或只读任务禁止编辑且不会请求外部表清单', async () => {
    const { saved } = await mount({
      status: 1,
      executionConfig: {
        tableLineage: {
          upstream: [
            { databaseId: '9007199254740993', tableName: 'source_table' },
          ],
          downstream: [],
        },
      },
    });
    expect(button('添加上游节点').disabled).toBe(true);
    expect(button('保存').disabled).toBe(true);
    button('保存').click();
    expect(saved).not.toHaveBeenCalled();
    expect(mocks.tables).not.toHaveBeenCalled();
    expect(host!.textContent).toContain('请先停用任务');
  });
  it('取消丢弃局部维护草稿', async () => {
    const { saved, cancel } = await mount({
      executionConfig: { tableLineage: { upstream: [], downstream: [] } },
    });
    await add('上游');
    button('取消').click();
    await flush();
    expect(cancel).toHaveBeenCalledOnce();
    expect(saved).not.toHaveBeenCalled();
    expect(host!.textContent).toContain('上游节点（0）');
  });
});
