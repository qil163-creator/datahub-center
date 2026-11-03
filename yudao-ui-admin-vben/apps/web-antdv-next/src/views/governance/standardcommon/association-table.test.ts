/* eslint-disable vue/one-component-per-file -- 隔离控件展示以验证关联列表的空态、错误与异步边界。 */
import type { App, PropType } from 'vue';

import type { StandardAssociation } from '#/api/governance/standardcommon/association';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { getStandardAssociations } from '#/api/governance/standardcommon/association';

import AssociationTable from './association-table.vue';

const harness = vi.hoisted(() => ({
  access: undefined as
    | undefined
    | { accessCodes: string[]; accessToken: string; refreshToken: string },
}));

vi.mock('@vben/stores', () => {
  harness.access = reactive({
    accessToken: 'one',
    refreshToken: '',
    accessCodes: ['governance:standard-element:query'],
  });
  return { useAccessStore: () => harness.access };
});
vi.mock('@vben/utils', () => ({ formatDate: String }));
vi.mock('#/api/governance/standardcommon/association', () => ({
  getStandardAssociations: vi.fn(),
}));
vi.mock('antdv-next', () => ({
  Alert: defineComponent({
    props: { message: { type: String, default: '' } },
    setup: (props) => () => h('div', { role: 'alert' }, props.message),
  }),
  Table: defineComponent({
    props: {
      dataSource: {
        type: Array as PropType<StandardAssociation[]>,
        default: () => [],
      },
      loading: Boolean,
    },
    setup: (props, { slots }) => () =>
      h('div', { 'data-loading': String(props.loading) }, [
        props.dataSource.length > 0
          ? props.dataSource.map((row) =>
              h('div', { 'data-row-id': String(row.id) }, row.assetName),
            )
          : slots.emptyText?.(),
      ]),
  }),
}));
vi.mock('./standard-toolbar.vue', () => ({
  default: defineComponent({
    emits: ['refresh'],
    setup: (_, { emit }) => () =>
      h('button', { onClick: () => emit('refresh') }, '刷新'),
  }),
}));
vi.mock('./standard-pagination.vue', () => ({
  default: defineComponent({
    props: { total: { type: Number, default: 0 } },
    setup: (props) => () => h('div', `共 ${props.total} 条`),
  }),
}));

type AssociationPage = Awaited<ReturnType<typeof getStandardAssociations>>;

let app: App | undefined;
let host: HTMLDivElement;
let props: { kind: 'codeset' | 'element'; ownerId: number | string };

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}

function associationPage(assetName: string): AssociationPage {
  return {
    list: [
      {
        id: '9007199254740993',
        assetName,
        tableName: 'sample_table',
        columnNames: ['sample_column'],
      },
    ],
    total: 1,
  };
}

function mount() {
  app = createApp({ render: () => h(AssociationTable, props) });
  app.mount(host);
}

async function settle() {
  await Promise.resolve();
  await nextTick();
}

beforeEach(() => {
  vi.clearAllMocks();
  Object.assign(harness.access!, { accessToken: 'one', refreshToken: '' });
  props = reactive({ kind: 'element', ownerId: 1 });
  host = document.createElement('div');
  document.body.append(host);
});

afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('标准关联列表', () => {
  it('无关联时展示暂无记录和零条分页，不显示加载失败', async () => {
    vi.mocked(getStandardAssociations).mockResolvedValue({ list: [], total: 0 });
    mount();
    await settle();

    expect(host.textContent).toContain('暂无记录');
    expect(host.textContent).toContain('共 0 条');
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.querySelector<HTMLElement>('[data-loading]')?.dataset.loading).toBe(
      'false',
    );
  });

  it('真实请求失败保留错误提示，刷新成功后恢复正常空态', async () => {
    vi.mocked(getStandardAssociations)
      .mockRejectedValueOnce(new Error('服务不可用'))
      .mockResolvedValueOnce({ list: [], total: 0 });
    mount();
    await settle();

    expect(host.querySelector('[role="alert"]')?.textContent).toBe(
      '关联信息加载失败，请刷新重试',
    );
    host.querySelector('button')!.click();
    await settle();

    expect(getStandardAssociations).toHaveBeenCalledTimes(2);
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.textContent).toContain('暂无记录');
    expect(host.textContent).toContain('共 0 条');
  });

  it('对象切换后旧成功响应不能覆盖新对象的空态或提前结束加载', async () => {
    const old = deferred<AssociationPage>();
    const current = deferred<AssociationPage>();
    vi.mocked(getStandardAssociations)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(current.promise);
    mount();
    props.ownerId = 2;
    await nextTick();

    old.resolve(associationPage('旧资产'));
    await settle();
    expect(host.textContent).not.toContain('旧资产');
    expect(host.querySelector<HTMLElement>('[data-loading]')?.dataset.loading).toBe(
      'true',
    );

    current.resolve({ list: [], total: 0 });
    await settle();
    expect(host.textContent).toContain('暂无记录');
    expect(host.textContent).toContain('共 0 条');
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.querySelector<HTMLElement>('[data-loading]')?.dataset.loading).toBe(
      'false',
    );
  });

  it('登录切换后旧请求失败不能覆盖新会话数据或错误状态', async () => {
    const old = deferred<AssociationPage>();
    vi.mocked(getStandardAssociations)
      .mockReturnValueOnce(old.promise)
      .mockResolvedValueOnce(associationPage('当前资产'));
    mount();
    harness.access!.accessToken = 'two';
    await settle();

    old.reject(new Error('旧会话失败'));
    await settle();

    expect(host.textContent).toContain('当前资产');
    expect(host.textContent).toContain('共 1 条');
    expect(host.querySelector('[role="alert"]')).toBeNull();
    expect(host.querySelector<HTMLElement>('[data-row-id]')?.dataset.rowId).toBe(
      '9007199254740993',
    );
  });
});
