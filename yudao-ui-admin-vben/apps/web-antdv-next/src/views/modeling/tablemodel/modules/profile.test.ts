/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 控件替身集中声明本组交互需要的属性。 */
import type { App } from 'vue';

import type { ModelingModelApi } from '#/api/modeling/model';
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/modeling/tablemodel';

import Profile from './profile.vue';

const shared = vi.hoisted(() => ({
  canSave: true,
  confirm: vi.fn(),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => shared.canSave }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 1, refreshToken: '' }),
}));
vi.mock('@vben/hooks', () => ({ getDictLabel: () => '-' }));
vi.mock('@vben/utils', () => ({ formatDateTime: () => '模型审计时间' }));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({ setup: () => () => h('span') }),
}));
vi.mock('../../modelcommon/data', () => ({
  formatBelongingHierarchy: () => '数据明细层 / 客户域',
  getUserMobile: () => '-',
  getUserNickname: () => '模型创建人',
  optionLabel: () => '明细表',
  TABLE_TYPE_OPTIONS: [],
}));
vi.mock('#/api/governance/doc', () => ({
  getDocSimpleList: vi.fn(async () => []),
}));
vi.mock('#/api/governance/standardelement', () => ({
  getStandardElementPage: vi.fn(async () => ({ list: [], total: 0 })),
}));
vi.mock('#/api/modeling/tablemodel', () => ({
  getTableModel: vi.fn(),
  getTableModelDesign: vi.fn(),
  modelSaveData: (model: TableModelApi.Model) => ({
    id: model.id,
    code: model.code,
  }),
  saveTableModelDraft: vi.fn(async () => true),
}));
vi.mock('antdv-next', () => {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.action?.()]),
  });
  const modal = defineComponent({
    props: { open: Boolean, title: String },
    setup:
      (props, { slots }) =>
      () =>
        props.open
          ? h('section', { 'data-modal': props.title }, slots.default?.())
          : null,
  });
  return {
    Alert: defineComponent({
      props: { message: String },
      setup: (props) => () => h('aside', props.message),
    }),
    Button: defineComponent({
      props: { disabled: Boolean },
      setup:
        (props, { attrs, slots }) =>
        () =>
          h(
            'button',
            { ...attrs, disabled: props.disabled },
            slots.default?.(),
          ),
    }),
    Input: defineComponent({
      props: { value: String, disabled: Boolean, placeholder: String },
      emits: ['update:value'],
      setup:
        (props, { emit }) =>
        () =>
          h('input', {
            value: props.value,
            disabled: props.disabled,
            placeholder: props.placeholder,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          }),
    }),
    InputNumber: plain,
    message: { success: vi.fn(), warning: vi.fn() },
    Modal: Object.assign(modal, { confirm: shared.confirm }),
    Radio: plain,
    RadioGroup: plain,
    Select: plain,
    Spin: plain,
    Switch: plain,
    TabPane: plain,
    Tabs: plain,
    Tag: plain,
    TextArea: plain,
    Tooltip: plain,
  };
});

// This adapter double exercises profile actions after a sorted second page;
// actual Vxe rendering and its toolbar are verified separately in Chrome.
vi.mock('#/adapter/vxe-table', () => ({
  useVbenVxeGrid: (options: {
    gridOptions: {
      proxyConfig: {
        ajax: {
          query: (
            params: unknown,
          ) => Promise<{ list: ModelingModelApi.ModelField[] }>;
        };
      };
    };
  }) => {
    const rows = ref<ModelingModelApi.ModelField[]>([]);
    const reload = async () => {
      const page = await options.gridOptions.proxyConfig.ajax.query({
        page: { currentPage: 2, pageSize: 10 },
        sorts: [{ field: 'fieldName', order: 'desc' }],
      });
      rows.value = page.list;
    };
    const grid = defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', [
            slots['toolbar-actions']?.(),
            slots['toolbar-tools']?.(),
            ...rows.value.map((row) =>
              h('div', { 'data-field': row.fieldUid }, [
                h('span', row.cnName),
                slots.action?.({ row }),
              ]),
            ),
          ]),
    });
    return [grid, { reload, setLoading: vi.fn() }];
  },
}));

let app: App | undefined;
let host: HTMLDivElement;
const modelId = '9007199254740993';
const revision = '9007199254740997';
const fields = (): ModelingModelApi.ModelField[] =>
  Array.from({ length: 14 }, (_, index) => ({
    fieldUid: `uid-${index + 1}`,
    fieldName: `FIELD${String(index + 1).padStart(2, '0')}`,
    cnName: `字段${index + 1}`,
    dataType: 'VARCHAR',
    sort: index + 21,
  }));
const model = (id = modelId): TableModelApi.Model => ({
  id,
  revision,
  layoutRevision: 1,
  code: 'CUSTOMER',
  name: `客户模型${id}`,
  enabled: 1,
  status: 'DRAFT',
});
const design = (id = modelId, status = 'DRAFT'): TableModelApi.Design => ({
  modelId: id,
  revision,
  status,
  fields: fields(),
  typeConfig: { detail: { grainFields: ['FIELD01'] } },
});
async function flush() {
  for (let index = 0; index < 12; index++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mount(id = modelId) {
  const props = reactive({ modelId: id });
  app = createApp(defineComponent({ setup: () => () => h(Profile, props) }));
  app.mount(host);
  await flush();
  return props;
}
function button(root: Element, label: string) {
  const found = [...root.querySelectorAll('button')].find(
    (element) => element.textContent?.trim() === label,
  );
  if (!found) throw new Error(`Missing button: ${label}`);
  return found;
}

beforeEach(() => {
  vi.clearAllMocks();
  shared.canSave = true;
  host = document.createElement('div');
  document.body.append(host);
  vi.mocked(api.getTableModel).mockImplementation(async (id) =>
    model(String(id)),
  );
  vi.mocked(api.getTableModelDesign).mockImplementation(async (id) =>
    design(String(id)),
  );
  vi.mocked(api.saveTableModelDraft).mockResolvedValue(true);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('model profile field interactions', () => {
  it.each(['DRAFT', 'PUBLISHED'])(
    '%s 设计在排序后第二页按稳定字段标识修改，保留其他字段和持久顺序',
    async (status) => {
      vi.mocked(api.getTableModel).mockResolvedValueOnce({
        ...model(),
        status,
      });
      vi.mocked(api.getTableModelDesign).mockResolvedValueOnce(
        design(modelId, status),
      );
      await mount();
      expect(host.querySelectorAll('[data-field]').length).toBe(4);
      const target = host.querySelector('[data-field="uid-3"]')!;
      button(target, '修改').click();
      await flush();
      const input = host.querySelector<HTMLInputElement>(
        'input[placeholder="请输入中文名称"]',
      )!;
      input.value = '正确的第三个字段';
      input.dispatchEvent(new Event('input', { bubbles: true }));
      await flush();
      button(host, '确定').click();
      await flush();
      const saved = vi.mocked(api.saveTableModelDraft).mock.calls[0]?.[0];
      expect(saved).toMatchObject({
        modelId,
        expectedRevision: revision,
        typeConfig: { detail: { grainFields: ['FIELD01'] } },
      });
      expect(
        saved?.fields.find((field) => field.fieldUid === 'uid-3')?.cnName,
      ).toBe('正确的第三个字段');
      expect(
        saved?.fields.find((field) => field.fieldUid === 'uid-1')?.cnName,
      ).toBe('字段1');
      expect(
        saved?.fields.map((field) => [field.fieldUid, field.sort]),
      ).toEqual(fields().map((field) => [field.fieldUid, field.sort]));
    },
  );

  it('requires confirmation and deletes the selected second-page field by its stable identity', async () => {
    await mount();
    const target = host.querySelector('[data-field="uid-4"]')!;
    button(target, '删除').click();
    expect(api.saveTableModelDraft).not.toHaveBeenCalled();
    expect(shared.confirm).toHaveBeenCalledOnce();
    const confirmation = shared.confirm.mock.calls[0]?.[0];
    expect(confirmation.title).toContain('字段4');
    await confirmation.onOk();
    await flush();
    const saved = vi.mocked(api.saveTableModelDraft).mock.calls[0]?.[0];
    expect(saved?.fields.map((field) => field.fieldUid)).toEqual(
      fields()
        .filter((field) => field.fieldUid !== 'uid-4')
        .map((field) => field.fieldUid),
    );
    expect(
      saved?.fields.find((field) => field.fieldUid === 'uid-1')?.sort,
    ).toBe(21);
  });

  it('keeps field mutations hidden without save permission and shows no fabricated field audit columns', async () => {
    shared.canSave = false;
    await mount();
    const actions = host.querySelector('[data-field="uid-3"]')!;
    expect(actions.textContent).toContain('详情');
    expect(actions.textContent).not.toContain('修改');
    expect(actions.textContent).not.toContain('删除');
    expect(
      [...host.querySelectorAll('button')].some(
        (item) => item.textContent?.trim() === '新增',
      ),
    ).toBe(false);
    expect(api.saveTableModelDraft).not.toHaveBeenCalled();
    button(actions, '详情').click();
    await flush();
    const detail = host.querySelector('[data-modal="逻辑模型属性信息详情"]');
    expect(detail?.textContent).not.toContain('创建人');
    expect(detail?.textContent).not.toContain('创建时间');
    expect(detail?.textContent).not.toContain('更新时间');
  });

  it('discards a previous model response after the lossless route identity changes', async () => {
    let resolvePrevious!: (value: TableModelApi.Model) => void;
    vi.mocked(api.getTableModel).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolvePrevious = resolve;
        }),
    );
    const props = await mount();
    props.modelId = '9007199254740999';
    await flush();
    expect(host.textContent).toContain('客户模型9007199254740999');
    resolvePrevious(model(modelId));
    await flush();
    expect(host.textContent).toContain('客户模型9007199254740999');
    expect(host.textContent).not.toContain(`客户模型${modelId}`);
    expect(vi.mocked(api.getTableModel).mock.calls.map(([id]) => id)).toEqual([
      modelId,
      '9007199254740999',
    ]);
  });

  it.each([{ modelId: '9007199254740994' }, { revision: '9007199254740998' }])(
    '头信息与设计对象或修订不一致时拒绝展示混合字段：%j',
    async (change) => {
      vi.mocked(api.getTableModelDesign).mockResolvedValueOnce({
        ...design(),
        ...change,
      });
      await mount();
      expect(host.textContent).not.toContain('模型详情加载失败');
      expect(host.querySelectorAll('[data-field]')).toHaveLength(0);
      expect(
        [...host.querySelectorAll('button')].some(
          (item) => item.textContent?.trim() === '新增',
        ),
      ).toBe(false);
      expect(api.saveTableModelDraft).not.toHaveBeenCalled();
    },
  );

  it('废弃设计允许查看字段详情并禁用修改和删除', async () => {
    vi.mocked(api.getTableModel).mockResolvedValueOnce({
      ...model(),
      status: 'DISCARDED',
    });
    vi.mocked(api.getTableModelDesign).mockResolvedValueOnce(
      design(modelId, 'DISCARDED'),
    );
    await mount();
    const target = host.querySelector('[data-field="uid-3"]')!;
    expect(target.textContent).toContain('详情');
    expect(button(target, '修改').disabled).toBe(true);
    expect(button(target, '删除').disabled).toBe(true);
    button(target, '修改').click();
    button(target, '删除').click();
    await flush();
    expect(
      host.querySelector('[data-modal="修改逻辑模型属性信息"]'),
    ).toBeNull();
    expect(api.saveTableModelDraft).not.toHaveBeenCalled();
  });

  it('保存返回false时保留字段弹窗和未保存输入，不刷新为服务端旧值', async () => {
    await mount();
    button(host.querySelector('[data-field="uid-3"]')!, '修改').click();
    await flush();
    const input = host.querySelector<HTMLInputElement>(
      'input[placeholder="请输入中文名称"]',
    )!;
    input.value = '尚未保存字段';
    input.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
    vi.mocked(api.saveTableModelDraft).mockResolvedValueOnce(false);
    button(host, '确定').click();
    await flush();
    expect(
      host.querySelector<HTMLInputElement>(
        'input[placeholder="请输入中文名称"]',
      )?.value,
    ).toBe('尚未保存字段');
    expect(api.getTableModelDesign).toHaveBeenCalledOnce();
    expect(api.saveTableModelDraft).toHaveBeenCalledWith(
      expect.objectContaining({ expectedRevision: revision }),
    );
  });
});
