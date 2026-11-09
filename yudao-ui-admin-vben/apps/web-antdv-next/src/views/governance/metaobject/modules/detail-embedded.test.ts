/* eslint-disable vue/one-component-per-file -- 隔离弹窗子表单和原生分页，验证真实元数据详情交互。 */
import type { App, PropType } from 'vue';

import type { GovernanceMetaObjectApi as Api } from '#/api/governance/metaobject';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getMetaChangePage,
  getMetaColumnList,
  getMetaSnapshot,
  getMetaSnapshotList,
  getMetaTableDetail,
} from '#/api/governance/metaobject';
import {
  bindMetaTags,
  getMetaTagBindingSnapshot,
  getMetaTagSimpleList,
} from '#/api/governance/metatag';

import Detail from './detail.vue';

interface PopupOptions {
  cancelText?: string;
  connectedComponent?: { name?: string };
  onOpenChange?: (open: boolean) => Promise<void> | void;
  showConfirmButton?: boolean;
}
interface Popup {
  close: () => Promise<void>;
  options: PopupOptions;
  state: { data?: unknown; loading: boolean; opened: boolean };
}
const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    loginExpired: boolean;
    refreshToken: string;
    userInfo?: { userId: string };
  },
  errors: [] as unknown[],
  popups: {} as Record<string, Popup>,
  props: {} as {
    embedded: boolean;
    referenceVersion?: string;
    tableId?: Api.Id;
  },
  back: vi.fn(),
  push: vi.fn(),
  success: vi.fn(),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: harness.push }) }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.access,
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vben/utils', () => ({
  formatDate: (value: unknown) => `时间:${value}`,
  formatDateTime: (value: unknown) => `时间:${value}`,
}));
vi.mock('#/views/governance/common/user', () => ({
  getUserNickname: (value: unknown) => String(value ?? '-'),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal(options: PopupOptions) {
    const kind =
      (
        {
          TableBizStub: 'table-biz',
          ColumnBizStub: 'column-biz',
          PreviewStub: 'preview',
        } as Record<string, string>
      )[options.connectedComponent?.name ?? ''] ?? 'detail';
    const state = reactive({
      data: undefined as unknown,
      loading: false,
      opened: false,
    });
    const close = vi.fn(async () => {
      state.opened = false;
      await options.onOpenChange?.(false);
    });
    harness.popups[kind] = { close, options, state };
    const api = {
      close,
      getData: () => state.data,
      lock: vi.fn(),
      open() {
        state.opened = true;
        void options.onOpenChange?.(true);
        return api;
      },
      setData(value: unknown) {
        state.data = value;
        return api;
      },
      setState(value: { loading?: boolean }) {
        Object.assign(state, value);
        return api;
      },
      unlock: vi.fn(),
    };
    return [
      defineComponent({
        props: { title: { type: String, default: '' } },
        emits: ['success'],
        setup(props, { emit, slots }) {
          return () =>
            state.opened
              ? h('section', { 'data-popup': kind }, [
                  props.title,
                  slots.default?.(),
                  kind === 'preview'
                    ? h('pre', String((state.data as { text?: string })?.text))
                    : null,
                  ['column-biz', 'table-biz'].includes(kind)
                    ? h(
                        'button',
                        { onClick: () => emit('success') },
                        '完成属性编辑',
                      )
                    : null,
                ])
              : null;
        },
      }),
      api,
    ];
  },
}));
vi.mock('./table-biz-form.vue', () => ({
  default: defineComponent({ name: 'TableBizStub', render: () => null }),
}));
vi.mock('./column-biz-form.vue', () => ({
  default: defineComponent({ name: 'ColumnBizStub', render: () => null }),
}));
vi.mock('#/views/governance/common/text-preview.vue', () => ({
  default: defineComponent({ name: 'PreviewStub', render: () => null }),
}));
vi.mock('#/api/governance/metaobject', () => ({
  getMetaChangePage: vi.fn(),
  getMetaColumnList: vi.fn(),
  getMetaSnapshot: vi.fn(),
  getMetaSnapshotList: vi.fn(),
  getMetaTableDetail: vi.fn(),
}));
vi.mock('#/api/governance/metatag', () => ({
  bindMetaTags: vi.fn(),
  getMetaTagBindingSnapshot: vi.fn(),
  getMetaTagSimpleList: vi.fn(),
}));
vi.mock('#/components/list-pagination/index.vue', () => ({
  default: defineComponent({
    props: {
      current: { type: Number, default: 1 },
      pageSize: { type: Number, default: 10 },
      total: { type: Number, default: 0 },
      pageSizeOptions: {
        type: Array as PropType<number[]>,
        default: () => [10, 20, 30, 50, 100, 200],
      },
    },
    emits: ['change'],
    setup(props, { emit }) {
      const change = (current: number, pageSize = props.pageSize) =>
        emit('change', current, pageSize);
      return () =>
        h(
          'div',
          {
            'data-native-pager': '',
            'data-current': props.current,
            'data-size': props.pageSize,
            'data-total': props.total,
          },
          [
            h(
              'select',
              {
                'aria-label': '每页条数',
                value: props.pageSize,
                onChange: (event: Event) =>
                  change(
                    props.current,
                    Number((event.target as HTMLSelectElement).value),
                  ),
              },
              props.pageSizeOptions.map((size) =>
                h('option', { value: size }, `${size}条/页`),
              ),
            ),
            h(
              'button',
              {
                'data-page': 'next',
                disabled: props.current * props.pageSize >= props.total,
                onClick: () => change(props.current + 1),
              },
              '下一页',
            ),
          ],
        );
    },
  }),
}));

const tableId = '9007199254740993';
const otherId = '9007199254740995';
const table: Api.MetaTable = {
  id: tableId,
  tableName: 'station',
  resourceUrn: 'urn:table:station',
  dbName: 'metadata_db',
  sourceSystemName: '水利系统',
  layer: 'DWD',
  sourceComment: '源端站点注释',
  customComment: '平台站点注释',
  businessName: '站点业务名',
  subjectDomainName: '水文主题',
  dbType: 'PostgreSQL',
  tableType: 'TABLE',
  engine: 'heap',
  columnCount: 25,
  primaryKeys: 'station_id',
  storageSize: 2048,
  masterFlag: 1,
  sensitiveFlag: 1,
  catalogLinked: 1,
  rowCount: 12_345,
  dataUpdateTime: 1_791_939_600_000,
  structChangeCount: 3,
  tbCreateTime: 1_790_939_600_000,
  firstSeenTime: 1_791_039_600_000,
  lastSeenTime: 1_791_839_600_000,
  publishedVersion: 'V1.0.1',
  publishedSnapshotId: '90071992547429930',
  structVersion: 'V1.0.2',
  publishTime: 1_791_239_600_000,
  lastStructChangeTime: 1_791_339_600_000,
  businessDefinition: '站点业务定义',
  publishStatus: 0,
  objectStatus: 1,
  origin: 1,
};
function columns(id = tableId): Api.MetaColumn[] {
  return Array.from({ length: 25 }, (_, index) => ({
    id: `9007199254741993${index}`,
    tableId: id,
    ordinalPosition: index + 1,
    columnName: `field_${String(index + 1).padStart(3, '0')}`,
    sourceComment: `源端字段注释${index + 1}`,
    customComment: index === 0 ? '自定义字段注释' : undefined,
    businessName: `字段业务名${index + 1}`,
    dataType: 'VARCHAR',
    dataLength: 80,
    dataPrecision: 20,
    dataScale: 2,
    nullableFlag: 0,
    pkFlag: index === 0 ? 1 : 0,
    sensitiveFlag: 1,
    standardElementId: index === 0 ? 123 : undefined,
    standardMappingId: index === 0 ? '9007199254745993' : undefined,
    standardElementName: index === 0 ? '站点编码' : undefined,
    standardElementCode: index === 0 ? 'stationCode' : undefined,
  }));
}
function snapshots(id = tableId): Api.Snapshot[] {
  return Array.from({ length: 25 }, (_, index) => ({
    id: `9007199254742993${index}`,
    tableId: id,
    structVersion: `V1.0.${String(index + 1).padStart(3, '0')}`,
    changeSource: 1,
    changeSummary: `快照变更${index + 1}`,
  }));
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
let app: App | undefined;
let host: HTMLDivElement;
async function flush() {
  for (let index = 0; index < 12; index++) await Promise.resolve();
  await vi.dynamicImportSettled();
  await nextTick();
}
async function mount(props: Partial<typeof harness.props> = {}) {
  harness.props = reactive({ embedded: true, tableId, ...props });
  app = createApp(
    defineComponent({
      setup: () => () =>
        h(Detail, {
          ...harness.props,
          onBack: harness.back,
          onSuccess: harness.success,
        }),
    }),
  );
  app.config.errorHandler = (error) => harness.errors.push(error);
  app.mount(host);
  await flush();
}
function button(text: string, root: ParentNode = host) {
  return [...root.querySelectorAll<HTMLButtonElement>('button')].find(
    (node) => node.textContent?.replaceAll(/\s/gu, '') === text,
  );
}
function activePanel() {
  const panel = host.querySelector<HTMLElement>('.ant-tabs-content-active');
  expect(panel).not.toBeNull();
  return panel!;
}
async function selectTab(label: string) {
  const tab = [...host.querySelectorAll<HTMLElement>('[role="tab"]')].find(
    (node) => node.textContent?.includes(label),
  );
  expect(tab).toBeDefined();
  tab!.click();
  await flush();
}
function rows(index = 0) {
  return (
    [...activePanel().querySelectorAll('.ant-table-wrapper')][
      index
    ]?.querySelectorAll<HTMLTableRowElement>('tbody tr[data-row-key]') ?? []
  );
}
function pager(index = 0) {
  const node = activePanel().querySelectorAll<HTMLElement>(
    '[data-native-pager]',
  )[index];
  expect(node).toBeDefined();
  return node!;
}
async function nextPage(index = 0) {
  pager(index).querySelector<HTMLButtonElement>('[data-page="next"]')!.click();
  await flush();
}
async function pageSize(size: number, index = 0) {
  const select = pager(index).querySelector<HTMLSelectElement>('select')!;
  select.value = String(size);
  select.dispatchEvent(new Event('change', { bubbles: true }));
  await flush();
}
async function openDefaultModal() {
  const popup = harness.popups.detail!;
  popup.state.data = { id: tableId };
  popup.state.opened = true;
  await popup.options.onOpenChange!(true);
  await flush();
}

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: [
      'governance:meta-table:query',
      'governance:meta-table:update',
    ],
    accessToken: 'session-a',
    loginExpired: false,
    refreshToken: 'refresh-a',
    userInfo: { userId: '1' },
  });
  harness.errors = [];
  harness.popups = {};
  harness.push.mockResolvedValue(undefined);
  vi.mocked(getMetaTableDetail)
    .mockReset()
    .mockImplementation(async (id) => ({ ...table, id }));
  vi.mocked(getMetaColumnList)
    .mockReset()
    .mockImplementation(async (id) => columns(String(id)));
  vi.mocked(getMetaSnapshotList)
    .mockReset()
    .mockImplementation(async (id) => snapshots(String(id)));
  vi.mocked(getMetaSnapshot)
    .mockReset()
    .mockImplementation(async (id) => ({
      id,
      tableId,
      snapshot: '{"tableName":"station"}',
    }));
  vi.mocked(getMetaChangePage)
    .mockReset()
    .mockImplementation(async (params) => ({
      list: Array.from(
        {
          length: Math.min(
            params.pageSize!,
            25 - (params.pageNo! - 1) * params.pageSize!,
          ),
        },
        (_, index) => ({
          id: `change-${params.pageNo}-${index}`,
          tableId: params.tableId,
          objectType: 3,
          changeType: 2,
          changeSource: 1,
          property: `change_${(params.pageNo! - 1) * params.pageSize! + index + 1}`,
          beforeValue: 'before',
          afterValue: 'after',
          versionFrom: 'V1.0.1',
          versionTo: 'V1.0.2',
        }),
      ),
      total: 25,
    }));
  vi.mocked(getMetaTagBindingSnapshot)
    .mockReset()
    .mockImplementation(async (_type, id) => ({
      objectType: 2,
      objectId: id,
      revision: 5,
      tagIds: ['9007199254744993'],
      tags: [
        { id: '9007199254744993', name: `已绑定标签-${id}`, color: 'blue' },
      ],
    }));
  vi.mocked(getMetaTagSimpleList).mockReset().mockResolvedValue([]);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
  if (harness.errors.length > 0)
    throw new Error('详情组件未捕获错误', { cause: harness.errors[0] });
});

describe('元数据检索详情的独立页面模式', () => {
  it('embedded 按无损编号读取并保留全部基本信息、标签和返回入口', async () => {
    await mount();
    expect(getMetaTableDetail).toHaveBeenCalledExactlyOnceWith(tableId);
    expect(getMetaColumnList).toHaveBeenCalledExactlyOnceWith(tableId);
    expect(getMetaSnapshotList).toHaveBeenCalledExactlyOnceWith(tableId);
    expect(host.querySelector('[data-popup="detail"]')).toBeNull();
    expect(
      [...activePanel().querySelectorAll('.ant-descriptions-item-label')].map(
        (node) => node.textContent,
      ),
    ).toEqual([
      '所属库',
      '来源系统',
      '所属分层',
      '源库注释',
      '平台注释',
      '业务名称',
      '主题域',
      '数据库类型',
      '表类型',
      '存储引擎',
      '字段数量',
      '主键',
      '存储大小',
      '是否主表',
      '是否敏感表',
      '目录挂接',
      '行数（估算）',
      '数据更新时间',
      '结构变更次数',
      '源表创建时间',
      '首次发现',
      '最近确认',
      '定版版本',
      '发布时间',
      '最近结构变更',
      '业务定义',
    ]);
    for (const text of [
      'metadata_db',
      '水利系统',
      'DWD',
      '平台站点注释',
      '水文主题',
      'heap',
      '2.0KB',
      '站点业务定义',
    ]) {
      expect(activePanel().textContent).toContain(text);
    }
    expect(getMetaTagBindingSnapshot).toHaveBeenCalledWith(2, tableId);
    expect(host.textContent).toContain(`已绑定标签-${tableId}`);
    expect(bindMetaTags).not.toHaveBeenCalled();
    button('返回')!.click();
    await flush();
    expect(harness.back).toHaveBeenCalledOnce();
    expect(harness.popups.detail!.close).not.toHaveBeenCalled();
  });

  it('字段保留治理列并按10条本地分页，容量变化回第一页', async () => {
    await mount();
    await selectTab('字段列表');
    expect(rows()).toHaveLength(10);
    expect(rows()[0]?.dataset.rowKey).toBe('90071992547419930');
    for (const text of [
      'field_001',
      '自定义字段注释',
      '字段业务名1',
      'VARCHAR',
      '80',
      '20',
      '2',
      '否',
      '主键',
      '敏感',
      '站点编码',
      'stationCode',
      '编辑',
    ]) {
      expect(rows()[0]?.textContent).toContain(text);
    }
    expect(pager().dataset.size).toBe('10');
    await nextPage();
    expect(rows()[0]?.textContent).toContain('field_011');
    await pageSize(20);
    expect(pager().dataset.current).toBe('1');
    expect(rows()).toHaveLength(20);
    await nextPage();
    expect(rows()).toHaveLength(5);
    expect(getMetaColumnList).toHaveBeenCalledTimes(1);
  });

  it('快照本地分页、变更明细服务端分页分别保持正确总数和请求', async () => {
    await mount();
    await selectTab('版本与变更');
    expect(rows(0)).toHaveLength(10);
    expect(rows(1)).toHaveLength(10);
    await nextPage(0);
    expect(rows(0)[0]?.textContent).toContain('V1.0.011');
    expect(getMetaSnapshotList).toHaveBeenCalledTimes(1);
    await nextPage(1);
    expect(getMetaChangePage).toHaveBeenLastCalledWith({
      tableId,
      pageNo: 2,
      pageSize: 10,
    });
    expect(rows(1)[0]?.textContent).toContain('change_11');
    await pageSize(20, 1);
    expect(getMetaChangePage).toHaveBeenLastCalledWith({
      tableId,
      pageNo: 1,
      pageSize: 20,
    });
    expect(pager(1).dataset.current).toBe('1');
    expect(rows(1)).toHaveLength(20);
    expect(pager(0).dataset.total).toBe('25');
    expect(pager(1).dataset.total).toBe('25');
  });

  it.each(['table-biz', 'column-biz'] as const)(
    '%s 编辑成功刷新当前详情且发出原 success',
    async (kind) => {
      await mount();
      if (kind === 'column-biz') await selectTab('字段列表');
      button(
        kind === 'table-biz' ? '编辑业务属性' : '编辑',
        kind === 'table-biz' ? host : rows()[0]!,
      )!.click();
      await flush();
      expect(harness.popups[kind]!.state.data).toMatchObject({
        id: kind === 'table-biz' ? tableId : '90071992547419930',
      });
      vi.mocked(getMetaTableDetail).mockResolvedValueOnce({
        ...table,
        businessName: '保存后业务名称',
      });
      button(
        '完成属性编辑',
        host.querySelector(`[data-popup="${kind}"]`)!,
      )!.click();
      await flush();
      expect(getMetaTableDetail).toHaveBeenCalledTimes(2);
      expect(getMetaColumnList).toHaveBeenCalledTimes(2);
      expect(harness.success).toHaveBeenCalledOnce();
      await selectTab('基本信息');
      expect(activePanel().textContent).toContain('保存后业务名称');
    },
  );

  it('快照查看和 referenceVersion 引用保留原文本预览', async () => {
    await mount({ referenceVersion: '90071992547429930' });
    expect(
      host.querySelector('[role="tab"][aria-selected="true"]')?.textContent,
    ).toContain('版本与变更');
    expect(getMetaSnapshot).toHaveBeenCalledWith('90071992547429930');
    expect(harness.popups.preview!.state.data).toMatchObject({
      title: '结构快照 - V1.0.001',
    });
    expect(
      host.querySelector('[data-popup="preview"] pre')?.textContent,
    ).toContain('"tableName": "station"');
    await harness.popups.preview!.close();
    button('查看快照', rows(0)[1]!)!.click();
    await flush();
    expect(getMetaSnapshot).toHaveBeenLastCalledWith('90071992547429931');
    expect(harness.popups.preview!.state.opened).toBe(true);
  });

  it('独立详情打开标准保留本页，默认弹窗打开标准仍关闭外层', async () => {
    await mount();
    await selectTab('字段列表');
    button('站点编码', rows()[0]!)!.click();
    await flush();
    expect(harness.push).toHaveBeenCalledWith({
      name: 'GovernanceStandardElementDetail',
      params: { id: '123' },
    });
    expect(harness.popups.detail!.close).not.toHaveBeenCalled();
    app!.unmount();
    app = undefined;
    await mount({ embedded: false, tableId: undefined });
    expect(harness.popups.detail!.options).toMatchObject({
      showConfirmButton: false,
      cancelText: '关闭',
    });
    await openDefaultModal();
    await selectTab('字段列表');
    button('站点编码', rows()[0]!)!.click();
    await flush();
    expect(harness.popups.detail!.close).toHaveBeenCalledOnce();
  });

  it('对象切换后旧详情响应不覆盖新对象且本地分页回首页', async () => {
    const old = deferred<Api.MetaTable>();
    const latest = deferred<Api.MetaTable>();
    vi.mocked(getMetaTableDetail)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    await mount();
    harness.props.tableId = otherId;
    await flush();
    old.resolve({ ...table, tableName: '旧对象敏感信息' });
    await flush();
    expect(host.textContent).not.toContain('旧对象敏感信息');
    latest.resolve({ ...table, id: otherId, tableName: '当前对象' });
    await flush();
    expect(host.textContent).toContain('当前对象');
    await selectTab('字段列表');
    expect(pager().dataset.current).toBe('1');
    expect(pager().dataset.size).toBe('10');
    expect(rows()).toHaveLength(10);
  });

  it('对象切换后迟到的快照预览不能打开新对象的弹窗', async () => {
    const pending = deferred<Api.Snapshot>();
    vi.mocked(getMetaSnapshot).mockReturnValueOnce(pending.promise);
    await mount();
    await selectTab('版本与变更');
    button('查看快照', rows(0)[0]!)!.click();
    await flush();
    harness.props.tableId = otherId;
    await flush();
    pending.resolve({
      id: '90071992547429930',
      tableId,
      snapshot: '旧对象快照',
    });
    await flush();
    expect(harness.popups.preview!.state.opened).toBe(false);
    expect(host.textContent).not.toContain('旧对象快照');
  });

  it.each(['permission', 'session', 'user', 'logout', 'unmount'] as const)(
    '%s 后旧请求不回写',
    async (change) => {
      const old = deferred<Api.MetaTable>();
      const latest = deferred<Api.MetaTable>();
      vi.mocked(getMetaTableDetail)
        .mockReturnValueOnce(old.promise)
        .mockReturnValueOnce(latest.promise);
      await mount();
      if (change === 'permission') harness.access.accessCodes = [];
      if (change === 'session') harness.access.refreshToken = 'refresh-b';
      if (change === 'user') harness.access.userInfo = { userId: '2' };
      if (change === 'logout') {
        harness.access.accessToken = '';
        harness.access.userInfo = undefined;
      }
      if (change === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      await flush();
      old.resolve({ ...table, tableName: '已失效会话详情' });
      await flush();
      expect(host.textContent).not.toContain('已失效会话详情');
      expect(host.querySelector('.detail-field-form')).toBeNull();
    },
  );

  it('没有查询权限或响应编号不匹配时不展示对象内容', async () => {
    harness.access.accessCodes = [];
    await mount();
    expect(getMetaTableDetail).not.toHaveBeenCalled();
    harness.access.accessCodes = ['governance:meta-table:query'];
    vi.mocked(getMetaTableDetail).mockResolvedValueOnce({
      ...table,
      id: otherId,
      tableName: '错误对象内容',
    });
    await flush();
    expect(host.textContent).not.toContain('错误对象内容');
    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    expect(button('返回')).toBeDefined();
  });
});
