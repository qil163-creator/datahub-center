/* eslint-disable vue/one-component-per-file -- 隔离基础控件，验证真实目录组件的查询和异步生命周期。 */
import type { App, PropType } from 'vue';

import type { QualityCatalogApi } from '#/api/governance/quality/catalog';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getRuleCatalogCategories,
  getRuleCatalogDetail,
  getRuleCatalogPage,
} from '#/api/governance/quality/catalog';

import { catalogTree } from './data';
import Catalog from './index.vue';

interface GridColumn {
  field?: string;
  slots?: { default?: string };
  title: string;
}

const harness = vi.hoisted(() => ({
  access: {
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: ['governance:audit-rule:query', 'governance:clean-rule:query'],
  },
  user: { userInfo: { userId: '9007199254740993' } },
  columnUpdates: 0,
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.every((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/utils', () => ({ formatDate: String }));
vi.mock('../../standardcommon/standard-list-layout.vue', () => ({
  default: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.tree?.(), slots.default?.()]),
  }),
}));
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: (options: {
    formOptions: {
      handleReset: () => Promise<void>;
      handleSubmit: (values: Record<string, unknown>) => void;
      handleValuesChange: (values: Record<string, unknown>) => void;
      schema: {
        componentProps: { placeholder: string };
        fieldName: string;
      }[];
    };
    gridEvents: {
      pageChange: (page: { currentPage: number; pageSize: number }) => void;
      sortChange: (sort: { field: string; order: string }) => void;
    };
    gridOptions: {
      columns: GridColumn[];
      customConfig: { mode: string; storage: boolean };
      data: QualityCatalogApi.Rule[];
      id: string;
      pagerConfig: { currentPage: number; pageSize: number; total: number };
      toolbarConfig: {
        custom: boolean;
        refresh: boolean;
        refreshOptions: { queryMethod: () => Promise<void> };
        search: boolean;
      };
    };
  }) => {
    const state = reactive({ ...options.gridOptions });
    const values = reactive<Record<string, unknown>>({});
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('section', {
              'data-custom': String(options.gridOptions.toolbarConfig.custom),
              'data-search': String(options.gridOptions.toolbarConfig.search),
              'data-refresh': String(options.gridOptions.toolbarConfig.refresh),
              'data-grid-id': options.gridOptions.id,
              'data-custom-storage': String(options.gridOptions.customConfig.storage),
            }, [
              h(
                'form',
                {
                  onSubmit: (event: Event) => {
                    event.preventDefault();
                    options.formOptions.handleSubmit({ ...values });
                  },
                },
                [
                  ...options.formOptions.schema.map((field) =>
                    h('input', {
                      placeholder: field.componentProps.placeholder,
                      value: values[field.fieldName],
                      onInput: (event: Event) => {
                        values[field.fieldName] = (
                          event.target as HTMLInputElement
                        ).value;
                        options.formOptions.handleValuesChange({ ...values });
                      },
                    }),
                  ),
                  h('button', { type: 'submit' }, '查询'),
                  h(
                    'button',
                    { type: 'button', onClick: options.formOptions.handleReset },
                    '重置',
                  ),
                ],
              ),
              h(
                'button',
                {
                  'data-grid-refresh': '',
                  onClick: options.gridOptions.toolbarConfig.refreshOptions.queryMethod,
                },
                '刷新',
              ),
              slots['toolbar-tools']?.(),
              h('table', [
                h(
                  'thead',
                  h(
                    'tr',
                    state.columns.map((column) =>
                      h(
                        'th',
                        { 'data-column': column.field },
                        h(
                          'button',
                          {
                            onClick: () =>
                              options.gridEvents.sortChange({
                                field: column.field ?? '',
                                order: 'asc',
                              }),
                          },
                          column.title,
                        ),
                      ),
                    ),
                  ),
                ),
                h(
                  'tbody',
                  state.data.map((row) =>
                    h(
                      'tr',
                      { 'data-row': row.id },
                      state.columns.map((column) =>
                        h(
                          'td',
                          column.slots?.default
                            ? slots[column.slots.default]?.({ row })
                            : String(row[column.field as keyof typeof row] ?? ''),
                        ),
                      ),
                    ),
                  ),
                ),
              ]),
              h(
                'button',
                {
                  'data-next-page': '',
                  onClick: () =>
                    options.gridEvents.pageChange({
                      currentPage: state.pagerConfig.currentPage + 1,
                      pageSize: state.pagerConfig.pageSize,
                    }),
                },
                '下一页',
              ),
            ]),
      }),
      {
        formApi: {
          reset: async () => {
            for (const field of options.formOptions.schema) {
              values[field.fieldName] = '';
            }
            options.formOptions.handleValuesChange({ ...values });
          },
          setValues: async (next: Record<string, unknown>) => {
            Object.assign(values, next);
            options.formOptions.handleValuesChange({ ...values });
          },
        },
        grid: { sort: vi.fn() },
        setGridOptions: (next: Partial<typeof state>) => {
          if (next.columns) harness.columnUpdates++;
          Object.assign(state, next);
        },
      },
    ];
  },
}));
vi.mock('#/api/governance/quality/catalog', () => ({
  getRuleCatalogCategories: vi.fn(),
  getRuleCatalogDetail: vi.fn(),
  getRuleCatalogPage: vi.fn(),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
  useVbenModal: (options: { onOpenChange?: (open: boolean) => void }) => {
    const visible = ref(false);
    return [
      defineComponent({
        props: { title: { type: String, default: '' } },
        setup:
          (props, { slots }) =>
          () =>
            visible.value
              ? h('section', { 'data-modal': props.title }, [
                  slots.default?.(),
                  slots.footer?.(),
                ])
              : null,
      }),
      {
        open: () => {
          visible.value = true;
          options.onOpenChange?.(true);
        },
        close: () => {
          visible.value = false;
          options.onOpenChange?.(false);
        },
      },
    ];
  },
}));
vi.mock('antdv-next', () => {
  const container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.content?.()]),
  });
  return {
    Spin: container,
    Tooltip: container,
    Popover: container,
    Select: container,
    Tag: container,
    Alert: defineComponent({
      props: { message: { type: String, default: '' } },
      setup:
        (props, { slots }) =>
        () =>
          h('div', { role: 'alert' }, [props.message, slots.action?.()]),
    }),
    Button: defineComponent({
      props: { htmlType: { type: String, default: 'button' } },
      setup:
        (props, { attrs, slots }) =>
        () =>
          h(
            'button',
            { ...attrs, type: props.htmlType || 'button' },
            slots.default?.(),
          ),
    }),
    Input: defineComponent({
      props: { value: { type: String, default: '' } },
      emits: ['update:value'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h('input', {
            ...attrs,
            value: props.value,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          }),
    }),
    Checkbox: defineComponent({
      props: { checked: Boolean, disabled: Boolean },
      emits: ['update:checked'],
      setup:
        (props, { emit, slots }) =>
        () =>
          h('label', [
            h('input', {
              type: 'checkbox',
              checked: props.checked,
              disabled: props.disabled,
              onChange: (event: Event) =>
                emit(
                  'update:checked',
                  (event.target as HTMLInputElement).checked,
                ),
            }),
            slots.default?.(),
          ]),
    }),
    Tree: defineComponent({
      props: {
        treeData: {
          type: Array as PropType<ReturnType<typeof catalogTree>>,
          default: () => [],
        },
      },
      emits: ['select'],
      setup(props, { emit }) {
        const render = (
          rows: ReturnType<typeof catalogTree>,
        ): ReturnType<typeof h>[] =>
          rows.flatMap((row) => [
            h(
              'button',
              {
                'data-category': row.key,
                onClick: () => emit('select', [row.key]),
              },
              row.title,
            ),
            ...render(row.children),
          ]);
        return () => h('div', render(props.treeData));
      },
    }),
  };
});

let app: App;
let host: HTMLDivElement;
async function settle() {
  for (let index = 0; index < 10; index++) await nextTick();
}
async function mount(kind: QualityCatalogApi.Kind = 'audit') {
  app = createApp(Catalog, { kind });
  app.mount(host);
  await settle();
}
function button(label: string) {
  const found = [...host.querySelectorAll('button')].find(
    (entry) => entry.textContent?.trim() === label,
  );
  if (!found) throw new Error(`Missing button: ${label}`);
  return found;
}
function input(placeholder: string, value: string) {
  const field = host.querySelector<HTMLInputElement>(
    `input[placeholder="${placeholder}"]`,
  )!;
  field.value = value;
  field.dispatchEvent(new Event('input'));
}
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.resetAllMocks();
  localStorage.clear();
  harness.columnUpdates = 0;
  harness.access = reactive({
    accessToken: 'actor-a',
    refreshToken: '',
    accessCodes: ['governance:audit-rule:query', 'governance:clean-rule:query'],
  });
  harness.user = reactive({ userInfo: { userId: '9007199254740993' } });
  vi.mocked(getRuleCatalogCategories).mockResolvedValue([
    { id: '9007199254740995', categoryName: '准确性' },
  ]);
  vi.mocked(getRuleCatalogPage).mockResolvedValue({
    list: [{ id: '9007199254740997', name: '字符检查' }],
    total: 25,
  });
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  host.remove();
});

describe('规则目录交互', () => {
  it.each(['audit', 'clean'] as const)(
    '%s 分类加载失败时隐藏提示并仍可刷新',
    async (kind) => {
      vi.mocked(getRuleCatalogCategories).mockRejectedValueOnce(new Error('失败'));
      await mount(kind);
      expect(host.textContent).not.toContain('分类加载失败');
      expect(host.querySelector('.rule-catalog-tree-error')).toBeNull();
      host.querySelector<HTMLButtonElement>('[aria-label="刷新树"]')!.click();
      await settle();
      expect(getRuleCatalogCategories).toHaveBeenCalledTimes(2);
      expect(host.querySelector('[data-category="9007199254740995"]')).not.toBeNull();
    },
  );
  it.each(['audit', 'clean'] as const)(
    '%s 分类、查询、分页和排序由服务器处理，重置清除全部条件',
    async (kind) => {
      await mount(kind);
      expect(getRuleCatalogPage).toHaveBeenLastCalledWith(
        kind,
        expect.objectContaining({
          pageNo: 1,
          pageSize: 10,
          sortField: 'createTime',
          sortOrder: 'desc',
        }),
      );
      button('准确性').click();
      await settle();
      expect(getRuleCatalogPage).toHaveBeenLastCalledWith(
        kind,
        expect.objectContaining({
          [kind === 'audit' ? 'dimensionId' : 'categoryId']: '9007199254740995',
        }),
      );
      input('请输入规则名称', '字段');
      input('请输入描述', '格式');
      host
        .querySelector('form')!
        .dispatchEvent(new Event('submit', { cancelable: true }));
      await settle();
      button('下一页').click();
      await settle();
      expect(getRuleCatalogPage).toHaveBeenLastCalledWith(
        kind,
        expect.objectContaining({
          pageNo: 2,
          name: '字段',
          description: '格式',
        }),
      );
      button('编号').click();
      await settle();
      expect(getRuleCatalogPage).toHaveBeenLastCalledWith(
        kind,
        expect.objectContaining({
          pageNo: 1,
          sortField: 'id',
          sortOrder: 'asc',
        }),
      );
      button('重置').click();
      await settle();
      expect(getRuleCatalogPage).toHaveBeenLastCalledWith(
        kind,
        expect.objectContaining({
          pageNo: 1,
          name: undefined,
          description: undefined,
          sortField: 'createTime',
          sortOrder: 'desc',
          [kind === 'audit' ? 'dimensionId' : 'categoryId']: undefined,
        }),
      );
    },
  );
  it('迟到的列表响应不会覆盖后发查询', async () => {
    const old = deferred<{ list: QualityCatalogApi.Rule[]; total: number }>();
    vi.mocked(getRuleCatalogPage).mockReturnValueOnce(old.promise);
    await mount();
    host
      .querySelector<HTMLButtonElement>('[data-grid-refresh]')!
      .click();
    await settle();
    old.resolve({ list: [{ id: 1, name: '旧会话结果' }], total: 1 });
    await settle();
    expect(host.textContent).toContain('字符检查');
    expect(host.textContent).not.toContain('旧会话结果');
  });
  it('详情安全显示示例，关闭详情会使未完成请求失效', async () => {
    vi.mocked(getRuleCatalogDetail).mockResolvedValueOnce({
      id: '9007199254740997',
      name: '安全示例',
      example: '<img src=x onerror=alert(1)>',
    });
    await mount();
    button('详情').click();
    await settle();
    expect(getRuleCatalogDetail).toHaveBeenCalledWith(
      'audit',
      '9007199254740997',
    );
    const detail = host.querySelector('[data-testid="catalog-detail"]')!;
    expect(detail.textContent).toContain('<img src=x onerror=alert(1)>');
    expect(detail.querySelector('img')).toBeNull();
    host.querySelector<HTMLButtonElement>('[data-modal] button')!.click();
    const pending = deferred<QualityCatalogApi.Rule>();
    vi.mocked(getRuleCatalogDetail).mockReturnValueOnce(pending.promise);
    button('详情').click();
    await settle();
    host.querySelector<HTMLButtonElement>('[data-modal] button')!.click();
    pending.resolve({ id: 2, name: '迟到详情' });
    await settle();
    expect(host.querySelector('[data-modal]')).toBeNull();
    expect(host.textContent).not.toContain('迟到详情');
  });
  it('身份切换清空详情并重新请求分类，权限撤销时不再查询', async () => {
    await mount();
    vi.mocked(getRuleCatalogDetail).mockResolvedValue({
      id: 1,
      name: '旧用户详情',
    });
    button('详情').click();
    await settle();
    harness.user.userInfo.userId = '9007199254740999';
    harness.access.accessToken = 'actor-b';
    await settle();
    expect(host.querySelector('[data-modal]')).toBeNull();
    expect(
      host.querySelector('[data-category="9007199254740995"]'),
    ).not.toBeNull();
    expect(getRuleCatalogCategories).toHaveBeenCalledTimes(2);
    const requests = vi.mocked(getRuleCatalogPage).mock.calls.length;
    harness.access.accessCodes = [];
    await settle();
    expect(host.textContent).toContain('暂无此规则目录的查询权限');
    expect(getRuleCatalogPage).toHaveBeenCalledTimes(requests);
  });
  it('不再展示产品介绍横幅', async () => {
    await mount();
    expect(host.querySelector('[data-testid="catalog-guide"]')).toBeNull();
  });
  it('筛选树保留父级和完整字符串标识', async () => {
    vi.mocked(getRuleCatalogCategories).mockResolvedValue([
      { id: '9007199254740993', categoryName: '数据转换' },
      {
        id: '9007199254740995',
        parentId: '9007199254740993',
        categoryName: '日期格式',
      },
      { id: 3, categoryName: '其他' },
    ]);
    await mount('clean');
    input('搜索类目', '日期');
    await settle();
    expect(
      host.querySelector('[data-category="9007199254740993"]'),
    ).not.toBeNull();
    expect(
      host.querySelector('[data-category="9007199254740995"]'),
    ).not.toBeNull();
    expect(host.querySelector('[data-category="3"]')).toBeNull();
    expect(getRuleCatalogPage).toHaveBeenCalledTimes(1);
  });
  it.each(['audit', 'clean'] as const)(
    '%s 使用原生工具栏并在刷新时保留列设置',
    async (kind) => {
      await mount(kind);
      expect(host.querySelector('[data-custom="true"][data-search="true"][data-refresh="true"]')).not.toBeNull();
      expect(host.querySelector(`[data-grid-id="quality-rule-catalog-${kind}"][data-custom-storage="true"]`)).not.toBeNull();
      const columnUpdates = harness.columnUpdates;
      host.querySelector<HTMLButtonElement>('[data-grid-refresh]')!.click();
      await settle();
      expect(getRuleCatalogPage).toHaveBeenCalledTimes(2);
      expect(harness.columnUpdates).toBe(columnUpdates);
    },
  );
});
