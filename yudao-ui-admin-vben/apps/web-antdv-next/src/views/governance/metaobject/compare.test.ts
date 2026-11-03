/* eslint-disable vue/one-component-per-file -- 对比页回归使用轻量控件替身。 */
import type { App, PropType } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  compareMetaSnapshots,
  getMetaSnapshot,
  getMetaSnapshotList,
} from '#/api/governance/metaobject';

import Compare from './compare.vue';

const state = vi.hoisted(() => ({
  route: undefined as
    | undefined
    | { name: string; params: { id: string }; query: { snapshotId?: string } },
}));
vi.mock('vue-router', () => {
  state.route = reactive({
    name: 'GovernanceMetaTableCompare',
    params: { id: '9007199254740993' },
    query: { snapshotId: '3' },
  });
  return { useRoute: () => state.route };
});
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: '' }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('./data', () => ({
  formatTime: (value?: number) => String(value ?? '-'),
}));
vi.mock('#/api/governance/metaobject', () => ({
  compareMetaSnapshots: vi.fn(),
  getMetaSnapshot: vi.fn(),
  getMetaSnapshotList: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const slot = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: slot,
    Button: slot,
    Descriptions: slot,
    Spin: slot,
    DescriptionsItem: defineComponent({
      props: { label: { type: String, default: '' } },
      setup:
        (props, { slots }) =>
        () =>
          h('div', [props.label, slots.default?.()]),
    }),
    Input: defineComponent({
      props: { value: { type: String, default: '' } },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            value: props.value,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          }),
    }),
    Select: defineComponent({
      props: {
        value: { type: [String, Number], default: '' },
        options: {
          type: Array as PropType<{ label: string; value: string }[]>,
          default: () => [],
        },
      },
      emits: ['update:value', 'change'],
      setup:
        (props, { emit }) =>
        () =>
          h(
            'select',
            {
              value: props.value,
              onChange(event: Event) {
                const value = (event.target as HTMLSelectElement).value;
                emit('update:value', value);
                emit('change', value);
              },
            },
            props.options.map((option: { label: string; value: string }) =>
              h('option', { value: option.value }, option.label),
            ),
          ),
    }),
    Table: defineComponent({
      props: {
        dataSource: {
          type: Array as PropType<{ columnName: string; comment: string }[]>,
          default: () => [],
        },
      },
      setup: (props) => () =>
        h(
          'div',
          { class: 'rows' },
          props.dataSource.map((row: { columnName: string; comment: string }) =>
            h('div', `${row.columnName}:${row.comment}`),
          ),
        ),
    }),
  };
});
let app: App;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 18; index++) await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  vi.clearAllMocks();
  state.route!.name = 'GovernanceMetaTableCompare';
  state.route!.query = { snapshotId: '3' };
  vi.mocked(getMetaSnapshotList).mockResolvedValue([
    { id: '3', structVersion: 'V1.0.3' },
    { id: '2', structVersion: 'V1.0.2' },
    { id: '1', structVersion: 'V1.0.1' },
  ]);
  vi.mocked(getMetaSnapshot).mockImplementation(async (id) => ({
    id,
    snapshot: JSON.stringify({
      table: { comment: `历史描述${id}` },
      columns: [{ columnName: 'A' }, { columnName: 'B' }],
    }),
  }));
  vi.mocked(compareMetaSnapshots).mockResolvedValue({
    addedCount: 0,
    removedCount: 0,
    modifiedCount: 0,
    tableChanges: [],
    columnChanges: [
      {
        columnName: 'A',
        changeType: 'UNCHANGED',
        changes: [],
        before: { columnName: 'A', comment: '原始注释', nullable: 0 },
        after: { columnName: 'A', comment: '原始注释', nullable: 0 },
      },
      {
        columnName: 'B',
        changeType: 'UNCHANGED',
        changes: [],
        before: { columnName: 'B' },
        after: { columnName: 'B' },
      },
    ],
  });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Compare);
  app.mount(host);
});
afterEach(() => {
  app.unmount();
  host.remove();
});
describe('元数据历史版本整页对比', () => {
  it('读取两侧真实快照，保留无损表 ID，搜索只改变字段展示', async () => {
    await flush();
    expect(compareMetaSnapshots).toHaveBeenCalledWith({
      tableId: '9007199254740993',
      fromSnapshotId: '2',
      toSnapshotId: '3',
    });
    expect(host.textContent).toContain('历史描述2');
    expect(host.textContent).toContain('历史描述3');
    expect(host.textContent).toContain('A:原始注释');
    const input = host.querySelector('input')!;
    input.value = 'A';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
    expect(host.querySelector('.rows')?.textContent).not.toContain('B:');
    expect(host.textContent?.match(/字段数2/g)).toHaveLength(2);
  });
  it('失活路由后不写回延迟到达的对比结果', async () => {
    await flush();
    let resolve!: (
      value: Awaited<ReturnType<typeof compareMetaSnapshots>>,
    ) => void;
    vi.mocked(compareMetaSnapshots).mockImplementationOnce(
      () =>
        new Promise((yes) => {
          resolve = yes;
        }),
    );
    const select = host.querySelector('select')!;
    select.value = '1';
    select.dispatchEvent(new Event('change', { bubbles: true }));
    await flush();
    state.route!.name = 'AnotherPage';
    await flush();
    resolve({
      addedCount: 0,
      removedCount: 0,
      modifiedCount: 0,
      tableChanges: [],
      columnChanges: [
        {
          columnName: '过期字段',
          changeType: 'UNCHANGED',
          changes: [],
          before: { columnName: '过期字段' },
        },
      ],
    });
    await flush();
    expect(host.textContent).not.toContain('过期字段');
  });
});
