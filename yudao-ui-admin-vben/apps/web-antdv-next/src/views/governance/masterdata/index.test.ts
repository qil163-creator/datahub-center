import type { App, PropType, VNode } from 'vue';

import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/governance/masterdata';
import { getMasterDomainSimpleList } from '#/api/governance/masterdomain';

import MasterDataPage from './index.vue';

interface Action {
  auth?: string[];
  disabled?: boolean;
  ifShow?: boolean;
  label: string;
  onClick?: () => void;
}
interface Confirmation {
  onOk: () => Promise<void>;
  title: string;
}
interface GridColumn {
  fixed?: string;
  showOverflow?: boolean;
  title?: string;
}
interface GridOptions {
  gridEvents: {
    pageChange: (page: { currentPage: number; pageSize: number }) => void;
  };
  gridOptions: {
    columns: GridColumn[];
  };
}
interface TreeNode {
  key: string;
  title: string;
  children?: TreeNode[];
  count: number | string;
}

const harness = vi.hoisted(() => ({
  access: {} as { accessToken: string; refreshToken?: string },
  compact: { value: false },
  columns: [] as GridColumn[],
  permissions: new Set<string>(),
  rows: [] as GovernanceMasterDataApi.Model[],
  confirmations: [] as Confirmation[],
  options: undefined as GridOptions | undefined,
  modalIndex: 0,
  formSetData: vi.fn(),
  detailSetData: vi.fn(),
  detailOpen: vi.fn(),
  clearCheckboxRow: vi.fn(),
  setLoading: vi.fn(),
  success: vi.fn(),
  warning: vi.fn(),
  routePush: vi.fn(),
  errors: [] as unknown[],
}));

vi.mock('vue-router', () => ({
  useRouter: () => ({ push: harness.routePush }),
}));
vi.mock('@vueuse/core', () => ({ useMediaQuery: () => harness.compact }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.permissions.has(code)),
  }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: '99' } }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent(() => () => null),
}));
vi.mock('@vben/utils', () => ({
  formatDate: String,
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
  useVbenModal: () => {
    const detail = harness.modalIndex++ === 1;
    const modalApi = {
      open: detail ? harness.detailOpen : vi.fn(),
      setData: detail ? harness.detailSetData : harness.formSetData,
    };
    modalApi.setData.mockReturnValue(modalApi);
    return [defineComponent(() => () => null), modalApi];
  },
}));
vi.mock('../mastercommon/master-workspace.vue', () => ({
  default: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.sidebar?.(), slots.default?.()]),
  }),
}));
vi.mock('./modules/model-form.vue', () => ({ default: {} }));
vi.mock('./modules/model-detail.vue', () => ({ default: {} }));
vi.mock('#/api/governance/masterdomain', () => ({
  getMasterDomainSimpleList: vi.fn(async () => []),
}));
vi.mock('#/api/governance/masterdata', () => ({
  cancelReleaseMasterModel: vi.fn(),
  deleteMasterModel: vi.fn(),
  deleteMasterModelList: vi.fn(),
  getMasterModelDataCount: vi.fn(),
  getMasterModelPage: vi.fn(),
  getMasterModelStatistics: vi.fn(),
  releaseMasterModel: vi.fn(),
  verifyMasterModelRelease: vi.fn(),
}));
vi.mock('antdv-next', () => {
  const text = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('span', slots.default?.()),
  });
  return {
    Alert: defineComponent({
      props: { message: String },
      setup: (props) => () => h('p', { role: 'alert' }, props.message),
    }),
    Button: defineComponent({
      props: { disabled: Boolean, loading: Boolean },
      setup:
        (props, { attrs, slots }) =>
        () =>
          h(
            'button',
            { ...attrs, disabled: props.disabled || props.loading },
            slots.default?.(),
          ),
    }),
    Input: defineComponent({
      props: { value: { type: String, default: '' } },
      emits: ['update:value'],
      setup: (props, { attrs, emit }) => () =>
        h('input', {
          ...attrs,
          value: props.value,
          onInput: (event: Event) =>
            emit('update:value', (event.target as HTMLInputElement).value),
        }),
    }),
    Modal: {
      confirm: (value: Confirmation) => harness.confirmations.push(value),
    },
    Switch: defineComponent({
      props: { checked: Boolean, disabled: Boolean, loading: Boolean },
      emits: ['change'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h('button', {
            ...attrs,
            role: 'switch',
            'aria-checked': props.checked,
            'data-loading': props.loading,
            disabled: props.disabled || props.loading,
            onClick: () => emit('change', !props.checked),
          }),
    }),
    Tag: text,
    Tooltip: text,
    Tree: defineComponent({
      props: {
        treeData: { type: Array as PropType<TreeNode[]>, default: () => [] },
        selectedKeys: { type: Array as PropType<string[]>, default: () => [] },
        expandedKeys: { type: Array as PropType<string[]>, default: () => [] },
      },
      emits: ['select', 'update:expandedKeys'],
      setup: (props, { slots, emit }) => {
        const renderNode = (node: TreeNode): VNode =>
          h('div', { 'data-tree-node': node.key }, [
            node.children?.length
              ? h('button', {
                  'aria-label': '展开或收起',
                  onClick: () =>
                    emit(
                      'update:expandedKeys',
                      props.expandedKeys.includes(node.key) ? [] : [node.key],
                    ),
                })
              : null,
            h(
              'button',
              {
                'aria-selected': props.selectedKeys.includes(node.key),
                onClick: () => emit('select', [node.key]),
              },
              slots.titleRender?.(node),
            ),
            props.expandedKeys.includes(node.key)
              ? node.children?.map(renderNode)
              : null,
          ]);
        return () => h('div', props.treeData.map(renderNode));
      },
    }),
    message: { success: harness.success, warning: harness.warning },
  };
});
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: defineComponent({
    props: {
      actions: Array as PropType<Action[]>,
      dropDownActions: Array as PropType<Action[]>,
    },
    setup: (props) => () =>
      h(
        'div',
        (['actions', 'dropDownActions'] as const).map((group) =>
          h(
            'div',
            { 'data-action-group': group },
            (props[group] ?? [])
              .filter(
                (action) =>
                  action.ifShow !== false &&
                  (!action.auth ||
                    action.auth.some((code) => harness.permissions.has(code))),
              )
              .map((action) =>
                h(
                  'button',
                  { disabled: action.disabled, onClick: action.onClick },
                  action.label,
                ),
              ),
          ),
        ),
      ),
  }),
  useVbenVxeGrid: (options: GridOptions) => {
    harness.options = options;
    harness.columns = options.gridOptions.columns;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('section', { 'data-grid': '' }, [
              slots['toolbar-tools']?.(),
              ...harness.rows.map((row) =>
                h(
                  'div',
                  { 'data-model-id': String(row.id) },
                  [
                    'modelInfo',
                    'domain',
                    'statistics',
                    'status',
                    'actions',
                  ].map((slot) =>
                    h('div', { 'data-slot': slot }, slots[slot]?.({ row })),
                  ),
                ),
              ),
            ]),
      }),
      {
        setLoading: harness.setLoading,
        setGridOptions: (value: {
          columns?: GridColumn[];
          data?: GovernanceMasterDataApi.Model[];
        }) => {
          if (value.columns) harness.columns = value.columns;
          if (value.data)
            harness.rows.splice(0, harness.rows.length, ...value.data);
        },
        grid: { clearCheckboxRow: harness.clearCheckboxRow },
        formApi: {
          getValues: async () => ({}),
          reset: vi.fn(),
          setFieldValue: vi.fn(),
        },
      },
    ];
  },
}));

let app: App | undefined;
let host: HTMLDivElement;
const model: GovernanceMasterDataApi.Model = {
  id: '9007199254740995',
  tableCn: '人员模型',
  tableName: 'md_person',
  domainName: '组织人员',
  description: '人员模型说明',
  status: 0,
  publishState: 'READY',
  fieldCount: 12,
};

beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1' });
  harness.compact = ref(false);
  harness.permissions = reactive(
    new Set([
      'governance:master-data:query',
      'governance:master-model:delete',
      'governance:master-model:query',
      'governance:master-model:release',
      'governance:master-model:update',
    ]),
  );
  harness.rows = reactive([]);
  harness.confirmations = [];
  harness.modalIndex = 0;
  harness.errors = [];
  vi.mocked(api.getMasterModelPage).mockResolvedValue({
    list: [model],
    total: 1,
  });
  vi.mocked(api.getMasterModelStatistics).mockResolvedValue({
    total: 1,
    released: 0,
    draft: 1,
    offline: 0,
  });
  vi.mocked(api.getMasterModelDataCount).mockResolvedValue({
    available: true,
    dataCount: 0,
  });
  vi.mocked(api.releaseMasterModel).mockResolvedValue(undefined);
  vi.mocked(api.cancelReleaseMasterModel).mockResolvedValue(undefined);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
  if (harness.errors.length > 0) {
    throw new AggregateError(harness.errors, '页面渲染失败');
  }
});

async function flush() {
  for (let index = 0; index < 12; ++index) await Promise.resolve();
  await nextTick();
}
async function mount(row = model) {
  vi.mocked(api.getMasterModelPage).mockResolvedValue({
    list: [row],
    total: 1,
  });
  app = createApp(MasterDataPage);
  app.config.errorHandler = (error) => harness.errors.push(error);
  app.mount(host);
  await flush();
}
function rowElement() {
  return host.querySelector('[data-model-id]')!;
}
function button(label: string, root: Element = rowElement()) {
  return [...root.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  );
}
function modelSwitch() {
  return rowElement().querySelector<HTMLButtonElement>('[role="switch"]')!;
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: Error) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, reject, resolve };
}

describe('主数据模型列表', () => {
  it('树节点选择域和全部模型时使用无损域编号筛选，搜索与展开不改变筛选', async () => {
    vi.mocked(getMasterDomainSimpleList).mockResolvedValueOnce([
      { id: '9007199254740995', name: '组织人员', code: 'person', status: 0, modelCount: 2 },
      { id: 12, name: '物资', code: 'material', status: 0, modelCount: 3 },
    ]);
    await mount();
    const treeNode = (key: string) =>
      host.querySelector(`[data-tree-node="${key}"]`);
    expect(treeNode('root')?.textContent).toContain('全部模型');
    expect(treeNode('root')?.textContent).toContain('5');
    treeNode('domain:9007199254740995')!.querySelector('button')!.click();
    await flush();
    expect(api.getMasterModelPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ domainId: '9007199254740995', pageNo: 1 }),
    );
    expect(api.getMasterModelStatistics).toHaveBeenLastCalledWith('9007199254740995');
    const input = host.querySelector<HTMLInputElement>('input[aria-label="搜索主数据域"]')!;
    input.value = '物资';
    input.dispatchEvent(new Event('input'));
    await nextTick();
    expect(treeNode('domain:9007199254740995')).toBeNull();
    expect(treeNode('domain:12')).not.toBeNull();
    expect(api.getMasterModelPage).toHaveBeenCalledTimes(2);
    treeNode('root')!.querySelector<HTMLButtonElement>('[aria-label="展开或收起"]')!.click();
    await nextTick();
    expect(treeNode('domain:12')).toBeNull();
    expect(api.getMasterModelPage).toHaveBeenCalledTimes(2);
    treeNode('root')!.querySelector<HTMLButtonElement>('[aria-selected]')!.click();
    await flush();
    expect(api.getMasterModelPage).toHaveBeenLastCalledWith(
      expect.objectContaining({ domainId: undefined, pageNo: 1 }),
    );
    expect(api.getMasterModelStatistics).toHaveBeenLastCalledWith(undefined);
  });

  it('切换窄屏与返回桌面时操作列持续固定，且不触发额外请求', async () => {
    await mount();
    const actionColumn = () =>
      harness.columns.find((column) => column.title === '操作');
    expect(actionColumn()?.fixed).toBe('right');
    harness.compact.value = true;
    await nextTick();
    expect(actionColumn()).toMatchObject({ fixed: 'right', showOverflow: false });
    harness.compact.value = false;
    await nextTick();
    expect(actionColumn()?.fixed).toBe('right');
    expect(api.getMasterModelPage).toHaveBeenCalledOnce();
  });

  it('组合展示模型名称、编码、状态、描述和所属域，缺失字段数不伪装为零', async () => {
    await mount({ ...model, fieldCount: undefined });
    const row = rowElement();
    expect(
      row.querySelector('.master-model-info__title')?.textContent,
    ).toContain('人员模型 (md_person)');
    expect(row.querySelector('.master-model-info__tag')?.textContent).toBe(
      '草稿',
    );
    expect(
      row.querySelector('.master-model-info__description')?.textContent,
    ).toBe('人员模型说明');
    expect(row.querySelector('[data-slot="domain"]')?.textContent).toBe(
      '组织人员',
    );
    expect(
      row.querySelector('[data-slot="statistics"]')?.textContent,
    ).toContain('字段数：- 个');
    expect(api.getMasterModelDataCount).not.toHaveBeenCalled();
  });

  it('名称与详情动作都用原始超大编号打开只读详情', async () => {
    await mount();
    rowElement()
      .querySelector<HTMLButtonElement>('.master-model-info__title')!
      .click();
    button('详情')!.click();
    expect(harness.detailSetData).toHaveBeenNthCalledWith(1, {
      id: '9007199254740995',
    });
    expect(harness.detailSetData).toHaveBeenNthCalledWith(2, {
      id: '9007199254740995',
    });
    expect(harness.detailOpen).toHaveBeenCalledTimes(2);
    expect(harness.formSetData).not.toHaveBeenCalled();
  });

  it.each([0, 1, 2])(
    '状态 %s 的开关在确认后才执行发布或下线',
    async (status) => {
      await mount({ ...model, status });
      expect(modelSwitch().getAttribute('aria-checked')).toBe(
        String(status === 1),
      );
      modelSwitch().click();
      expect(harness.confirmations).toHaveLength(1);
      expect(api.releaseMasterModel).not.toHaveBeenCalled();
      expect(api.cancelReleaseMasterModel).not.toHaveBeenCalled();
      expect(modelSwitch().getAttribute('aria-checked')).toBe(
        String(status === 1),
      );
      await harness.confirmations[0]!.onOk();
      expect(
        status === 1 ? api.cancelReleaseMasterModel : api.releaseMasterModel,
      ).toHaveBeenCalledWith('9007199254740995');
    },
  );

  it.each(['UNKNOWN', 'APPLYING'] as const)(
    '%s 模型禁用开关并提供核查操作',
    async (publishState) => {
      await mount({ ...model, publishState });
      expect(modelSwitch().disabled).toBe(true);
      modelSwitch().click();
      expect(harness.confirmations).toEqual([]);
      expect(button('发布')).toBeUndefined();
      expect(button('核查发布')).toBeDefined();
    },
  );

  it('缺少发布权限时开关禁用且发布操作隐藏', async () => {
    harness.permissions.delete('governance:master-model:release');
    await mount();
    expect(modelSwitch().disabled).toBe(true);
    expect(button('发布')).toBeUndefined();
    modelSwitch().click();
    expect(harness.confirmations).toEqual([]);
  });

  it('发布在途抑制重复写，失败后释放开关并允许重新确认', async () => {
    await mount();
    const pending = deferred<undefined>();
    vi.mocked(api.releaseMasterModel).mockReturnValueOnce(pending.promise);
    modelSwitch().click();
    const write = harness.confirmations[0]!.onOk();
    await flush();
    expect(modelSwitch().disabled).toBe(true);
    await harness.confirmations[0]!.onOk();
    expect(api.releaseMasterModel).toHaveBeenCalledOnce();
    pending.reject(new Error('发布失败'));
    await expect(write).rejects.toThrow('发布失败');
    await flush();
    expect(modelSwitch().disabled).toBe(false);
    expect(harness.success).not.toHaveBeenCalled();
    modelSwitch().click();
    expect(harness.confirmations).toHaveLength(2);
  });

  it('发布和详情保持常用动作，其余操作移入权限控制的更多菜单', async () => {
    await mount({ ...model, status: 1 });
    const row = rowElement();
    const labels = (group: string) =>
      [...row.querySelectorAll(`[data-action-group="${group}"] button`)].map(
        (item) => item.textContent,
      );
    expect(labels('actions')).toEqual(['下线', '详情']);
    expect(labels('dropDownActions')).toEqual(['修改', '删除', '维护数据']);
    harness.permissions.delete('governance:master-model:update');
    harness.permissions.delete('governance:master-model:delete');
    harness.permissions.delete('governance:master-data:query');
    await nextTick();
    expect(labels('actions')).toEqual(['下线', '详情']);
    expect(labels('dropDownActions')).toEqual([]);
  });

  it('按需计数请求去重并完整展示超大十进制字符串', async () => {
    await mount();
    const pending = deferred<GovernanceMasterDataApi.ModelDataCount>();
    vi.mocked(api.getMasterModelDataCount).mockReturnValueOnce(pending.promise);
    const query = button('查询')!;
    query.click();
    query.click();
    expect(api.getMasterModelDataCount).toHaveBeenCalledOnce();
    expect(api.getMasterModelDataCount).toHaveBeenCalledWith(
      '9007199254740995',
    );
    pending.resolve({ available: true, dataCount: '9223372036854775807' });
    await flush();
    expect(
      rowElement().querySelector('[data-slot="statistics"]')?.textContent,
    ).toContain('9223372036854775807 条');
  });

  it.each(['page', 'session', 'unmount'] as const)(
    '%s 变化后丢弃旧计数结果',
    async (change) => {
      await mount();
      const pending = deferred<GovernanceMasterDataApi.ModelDataCount>();
      vi.mocked(api.getMasterModelDataCount).mockReturnValueOnce(
        pending.promise,
      );
      button('查询')!.click();
      if (change === 'page') {
        harness.options!.gridEvents.pageChange({
          currentPage: 2,
          pageSize: 20,
        });
      } else if (change === 'session') {
        harness.access.refreshToken = '2';
      } else {
        app!.unmount();
        app = undefined;
      }
      await flush();
      pending.resolve({ available: true, dataCount: '9223372036854775807' });
      await flush();
      expect(host.textContent).not.toContain('9223372036854775807');
      expect(button('查询', host)?.disabled).toBe(
        change === 'unmount' ? undefined : false,
      );
      expect(api.getMasterModelPage).toHaveBeenCalledTimes(
        change === 'unmount' ? 1 : 2,
      );
    },
  );

  it('计数失败允许重试，缺少主数据查询权限时不发送计数请求', async () => {
    await mount();
    vi.mocked(api.getMasterModelDataCount).mockRejectedValueOnce(
      new Error('查询失败'),
    );
    button('查询')!.click();
    await flush();
    expect(button('重试')?.disabled).toBe(false);
    button('重试')!.click();
    await flush();
    expect(
      rowElement().querySelector('[data-slot="statistics"]')?.textContent,
    ).toContain('0 条');
    harness.permissions.delete('governance:master-data:query');
    harness.options!.gridEvents.pageChange({ currentPage: 2, pageSize: 20 });
    await flush();
    expect(button('查询')).toBeUndefined();
    expect(api.getMasterModelDataCount).toHaveBeenCalledTimes(2);
  });

  it('数据量不可用时展示原因和占位符，不伪装为零或反复重试', async () => {
    await mount();
    vi.mocked(api.getMasterModelDataCount).mockResolvedValueOnce({
      available: false,
      message: '目标表不存在',
    });
    button('查询')!.click();
    await flush();
    const counts = rowElement().querySelector('[data-slot="statistics"]')!;
    expect(counts.textContent).toContain('数据量： -');
    expect(counts.textContent).not.toContain('0 条');
    expect(counts.querySelector('[title="目标表不存在"]')).not.toBeNull();
    expect(button('查询')).toBeUndefined();
    expect(button('重试')).toBeUndefined();
  });

  it.each(['governance:master-model:query', 'governance:master-data:query'])(
    '数据量查询缺少 %s 权限时不发送请求',
    async (permission) => {
      harness.permissions.delete(permission);
      await mount();
      expect(button('查询')).toBeUndefined();
      expect(api.getMasterModelDataCount).not.toHaveBeenCalled();
    },
  );
});
