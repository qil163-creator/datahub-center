/* eslint-disable vue/one-component-per-file, vue/require-default-prop -- 测试替身集中模拟控件，仅声明当前场景需要的属性。 */
import type { App } from 'vue';

import type { BusinessConditionApi } from '#/api/modeling/business-condition';

import { createApp, defineComponent, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ConditionForm from './form.vue';

const ids = {
  condition: '9223372036854775806',
  revision: '9007199254740993',
  domain: '9223372036854775805',
  subject: '9223372036854775800',
  entity: '9223372036854775799',
  owner: '9223372036854775798',
  modelA: '9223372036854775804',
  modelB: '9223372036854775802',
  modelC: '9223372036854775801',
};
const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
  route: {} as { query: Record<string, string> },
  detail: vi.fn(),
  fields: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  push: vi.fn(),
}));
vi.mock('vue-router', () => ({
  useRoute: () => harness.route,
  useRouter: () => ({ push: harness.push }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/hooks', () => ({
  useAntdDesignTokens: () => ({ tokens: { borderRadius: 6 } }),
  getDictOptions: () => [{ label: '天（T+1）', value: '1' }],
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({ setup: () => () => h('span') }),
}));
vi.mock('../logictable/options', () => ({
  loadLogicOptionPages: async (
    fetchPage: (params: {
      pageNo: number;
      pageSize: number;
    }) => Promise<{ list: unknown[] }>,
  ) => {
    const result = await fetchPage({ pageNo: 1, pageSize: 100 });
    return result.list;
  },
}));
vi.mock('#/api/modeling/business-entity', () => ({
  getBusinessEntityPage: async () => ({
    list: [
      {
        id: '9223372036854775799',
        name: '订单',
        code: 'ORDER',
        dataDomainId: '9223372036854775805',
        subjectDomainId: '9223372036854775800',
        status: 0,
        entityType: 'ACTIVITY',
        entitySubtype: 'EVENT',
      },
    ],
    total: 1,
  }),
}));
vi.mock('../indicator-model/modules/entity-selector.vue', () => ({
  default: defineComponent({
    props: { modelValue: [String, Number], entities: Array, disabled: Boolean },
    emits: ['update:modelValue', 'change'],
    setup:
      (props, { emit }) =>
      () =>
        h(
          'select',
          {
            value: props.modelValue,
            disabled: props.disabled,
            onChange: (event: Event) => {
              const value = (event.target as HTMLSelectElement).value;
              emit('update:modelValue', value);
              emit('change', value);
            },
          },
          [
            h('option', { value: '' }),
            ...((props.entities as { id: string; name: string }[]) || []).map(
              (item) => h('option', { value: item.id }, item.name),
            ),
          ],
        ),
  }),
}));
vi.mock('#/components/monaco/sql-editor.vue', () => ({
  default: defineComponent({
    props: { modelValue: String, readonly: Boolean },
    emits: ['update:modelValue'],
    setup: (props, { emit, expose }) => {
      expose({
        format: vi.fn(),
        insertText: (text: string) =>
          emit('update:modelValue', `${props.modelValue || ''}${text}`),
      });
      return () =>
        h('textarea', {
          'aria-label': '计算逻辑',
          value: props.modelValue,
          readonly: props.readonly,
          onInput: (event: Event) =>
            emit(
              'update:modelValue',
              (event.target as HTMLTextAreaElement).value,
            ),
        });
    },
  }),
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
}));
vi.mock('#/api/modeling/business-condition', () => ({
  getBusinessCondition: (id: string) => harness.detail(id),
  getBusinessConditionSourceFields: (id: string) => harness.fields(id),
  createBusinessCondition: (data: unknown) => harness.create(data),
  updateBusinessCondition: (data: unknown) => harness.update(data),
}));
vi.mock('../indicator-model/modules/use-metric-options', () => ({
  useMetricOptions: () => ({
    domains: ref([{ id: '9223372036854775805', name: '交易域' }]),
    subjects: ref([
      {
        id: '9223372036854775800',
        name: '订单主题',
        dataDomainId: '9223372036854775805',
      },
    ]),
    users: ref([
      { id: '9223372036854775798', nickname: '负责人', mobile: '13800000000' },
    ]),
    models: ref(
      ['9223372036854775804', '9223372036854775802', '9223372036854775801'].map(
        (id, index) => ({
          id,
          name: `模型${index}`,
          code: `MODEL_${index}`,
          dataDomainId: '9223372036854775805',
          subjectDomainId: '9223372036854775800',
          entityId: '9223372036854775799',
          enabled: 1,
          tableType: 'DETAIL',
          status: 'PUBLISHED',
          dataTimeliness: '1',
        }),
      ),
    ),
    loading: ref(false),
    error: ref(false),
    reload: vi.fn(),
  }),
}));
vi.mock('antdv-next', () => {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  const input = (tag: string) =>
    defineComponent({
      inheritAttrs: false,
      props: { value: String, disabled: Boolean, placeholder: String },
      emits: ['update:value'],
      setup:
        (props, { attrs, emit }) =>
        () =>
          h(tag, {
            ...attrs,
            value: props.value,
            disabled: props.disabled,
            placeholder: props.placeholder,
            onInput: (event: Event) =>
              emit('update:value', (event.target as HTMLInputElement).value),
          }),
    });
  return {
    ConfigProvider: plain,
    theme: { defaultAlgorithm: () => ({}) },
    Alert: defineComponent({
      props: { message: String },
      setup:
        (props, { slots }) =>
        () =>
          h('aside', [props.message, slots.action?.()]),
    }),
    Button: defineComponent({
      props: { disabled: Boolean, loading: Boolean },
      setup:
        (props, { attrs, slots }) =>
        () =>
          h(
            'button',
            {
              ...attrs,
              disabled: props.disabled || props.loading,
              'data-loading': String(props.loading),
            },
            slots.default?.(),
          ),
    }),
    Form: plain,
    FormItem: defineComponent({
      props: { label: String },
      setup:
        (props, { slots }) =>
        () =>
          h('label', { 'data-label': props.label }, [
            props.label,
            slots.default?.(),
          ]),
    }),
    Input: input('input'),
    TextArea: input('textarea'),
    Select: defineComponent({
      props: {
        value: String,
        disabled: Boolean,
        options: {
          type: Array as () => { label: string; value: string }[],
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
              disabled: props.disabled,
              onChange: (event: Event) => {
                const value = (event.target as HTMLSelectElement).value;
                emit('update:value', value);
                emit('change', value);
              },
            },
            [
              h('option', { value: '' }, '请选择'),
              ...props.options.map((item) =>
                h('option', { value: item.value }, item.label),
              ),
            ],
          ),
    }),
    Spin: plain,
    Radio: plain,
    RadioGroup: plain,
    Table: defineComponent({
      props: {
        dataSource: {
          type: Array as () => { fieldName: string }[],
          default: () => [],
        },
      },
      setup: (props) => () =>
        h(
          'table',
          props.dataSource.map((item) => h('tr', [h('td', item.fieldName)])),
        ),
    }),
    message: { success: vi.fn(), warning: vi.fn() },
  };
});

let app: App | undefined;
let host: HTMLDivElement;
const original = (
  status: BusinessConditionApi.Status = 'DRAFT',
): BusinessConditionApi.Model => ({
  id: ids.condition,
  revision: ids.revision,
  name: '已支付订单',
  code: 'PAID_ORDER',
  dataDomainId: ids.domain,
  subjectDomainId: ids.subject,
  entityId: ids.entity,
  ownerUserId: ids.owner,
  dataFreshness: '1',
  sourceModelId: ids.modelA,
  status,
  configType: 'BASIC',
  logicMode: 'UNIFIED',
  expression: "pay_status = 'PAID'",
});
const field = (name: string) => ({
  fieldUid: `${name}-uid`,
  fieldName: name,
  dataType: 'INTEGER',
});
function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (error: Error) => void;
  const promise = new Promise<T>((done, fail) => {
    resolve = done;
    reject = fail;
  });
  return { promise, resolve, reject };
}
async function flush() {
  await vi.dynamicImportSettled();
  for (let index = 0; index < 8; index++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mount(query: Record<string, string> = {}) {
  app?.unmount();
  harness.route.query = query;
  app = createApp(ConditionForm);
  app.mount(host);
  await flush();
}
function input(label: string) {
  return host.querySelector<HTMLInputElement>(`[data-label="${label}"] input`)!;
}
function select(label: string) {
  return host.querySelector<HTMLSelectElement>(
    `[data-label="${label}"] select`,
  )!;
}
function button(text: string) {
  return [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (item) => item.textContent?.trim() === text,
  )!;
}
async function fill(
  element: HTMLInputElement | HTMLTextAreaElement,
  value: string,
) {
  element.value = value;
  element.dispatchEvent(new Event('input', { bubbles: true }));
  await nextTick();
}
async function choose(label: string, value: string) {
  const element = select(label);
  element.value = value;
  element.dispatchEvent(new Event('change', { bubbles: true }));
  await flush();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({
    accessCodes: ['modeling:model:query'],
    accessToken: 'session-a',
    refreshToken: 'refresh-a',
  });
  harness.user = reactive({ userInfo: { userId: '1' }, userRoles: ['admin'] });
  harness.route = reactive({ query: {} });
  harness.detail.mockResolvedValue(original());
  harness.fields.mockResolvedValue([field('pay_status')]);
  harness.create.mockResolvedValue(ids.condition);
  harness.update.mockResolvedValue(true);
  harness.push.mockResolvedValue(undefined);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('业务限定完整页向导', () => {
  it('创建、编辑、克隆使用字符串来源编号，编辑携带CAS，克隆按新建保存', async () => {
    for (const mode of ['create', 'edit', 'clone'] as const) {
      harness.create.mockClear();
      harness.update.mockClear();
      harness.detail.mockClear();
      harness.fields.mockClear();
      await mount(
        mode === 'create'
          ? {}
          : { [mode === 'edit' ? 'id' : 'cloneId']: ids.condition },
      );
      expect(harness.detail.mock.calls).toEqual(
        mode === 'create' ? [] : [[ids.condition]],
      );
      expect(input('业务限定中文名').value).toBe(
        mode === 'create' ? '' : '已支付订单',
      );
      expect(input('业务限定英文名').value).toBe(
        mode === 'edit' ? 'PAID_ORDER' : '',
      );
      if (mode === 'create') {
        await fill(input('业务限定中文名'), '新订单');
        await fill(input('业务限定英文名'), 'NEW_ORDER');
        await choose('数据板块', ids.domain);
        await choose('主题域', ids.subject);
        await choose('业务实体', ids.entity);
        await choose('负责人', ids.owner);
      } else {
        if (mode === 'clone')
          await fill(input('业务限定英文名'), 'PAID_ORDER_COPY');
      }
      button('下一步').click();
      await flush();
      if (mode === 'create') {
        await choose('数据时效', '1');
        await choose('来源逻辑表', ids.modelA);
      }
      expect(harness.fields).toHaveBeenLastCalledWith(ids.modelA);
      expect(select('来源逻辑表').value).toBe(ids.modelA);
      expect(host.textContent).not.toContain('来源版本');
      await fill(
        host.querySelector<HTMLTextAreaElement>(
          'textarea[aria-label="计算逻辑"]',
        )!,
        "pay_status = 'PAID'",
      );
      button('保存并退出').click();
      await flush();
      const save = mode === 'edit' ? harness.update : harness.create;
      expect(save).toHaveBeenCalledWith(
        expect.objectContaining({
          id: mode === 'edit' ? ids.condition : undefined,
          expectedRevision: mode === 'edit' ? ids.revision : undefined,
          sourceModelId: ids.modelA,
        }),
      );
      expect(save.mock.calls[0]![0].status).toBe('DRAFT');
    }
  });

  it('快速切换来源模型只接收当前设计字段，并清空前一来源的表达式', async () => {
    await mount({ id: ids.condition });
    button('下一步').click();
    await flush();
    const oldFields = deferred<unknown[]>();
    const newFields = deferred<unknown[]>();
    harness.fields.mockImplementation((id: string) =>
      id === ids.modelB ? oldFields.promise : newFields.promise,
    );
    await choose('来源逻辑表', ids.modelB);
    await choose('来源逻辑表', ids.modelC);
    newFields.resolve([field('new_amount')]);
    await flush();
    oldFields.resolve([field('old_amount')]);
    await flush();
    expect(host.querySelector('table')?.textContent).toContain('new_amount');
    expect(host.querySelector('table')?.textContent).not.toContain(
      'old_amount',
    );
    expect(
      host.querySelector<HTMLTextAreaElement>(
        'textarea[aria-label="计算逻辑"]',
      )!.value,
    ).toBe('');
  });

  it('保存失败保留用户计算逻辑，并在finally释放按钮锁定', async () => {
    await mount({ id: ids.condition });
    button('下一步').click();
    await flush();
    const textarea = host.querySelector<HTMLTextAreaElement>(
      'textarea[aria-label="计算逻辑"]',
    )!;
    await fill(textarea, 'amount > 5');
    const pending = deferred<boolean>();
    harness.update.mockReturnValue(pending.promise);
    button('保存并退出').click();
    await nextTick();
    expect(button('保存并退出').disabled).toBe(true);
    expect(button('保存并退出').dataset.loading).toBe('true');
    pending.reject(new Error('版本已变化'));
    await flush();
    expect(textarea.value).toBe('amount > 5');
    expect(button('保存并退出').disabled).toBe(false);
    expect(button('保存并退出').dataset.loading).toBe('false');
    expect(harness.push).not.toHaveBeenCalled();
    button('上一步').click();
    await nextTick();
    expect(input('业务限定中文名').value).toBe('已支付订单');
  });

  it('当前来源字段读取失败可重试，并保留已保存的计算定义', async () => {
    harness.fields.mockRejectedValueOnce(new Error('读取失败'));
    await mount({ id: ids.condition });
    expect(host.textContent).toContain('来源字段加载失败');
    button('下一步').click();
    await flush();
    button('重试来源').click();
    await flush();
    expect(select('来源逻辑表').value).toBe(ids.modelA);
    expect(harness.fields).toHaveBeenLastCalledWith(ids.modelA);
    expect(host.querySelector<HTMLTextAreaElement>('textarea[aria-label="计算逻辑"]')!.value).toBe("pay_status = 'PAID'");
    button('保存并退出').click();
    await flush();
    expect(harness.update).toHaveBeenCalledWith(expect.objectContaining({ expectedRevision: ids.revision, sourceModelId: ids.modelA, expression: "pay_status = 'PAID'" }));
  });

  it('启用态详情拒绝进入编辑并不加载可编辑来源', async () => {
    harness.detail.mockResolvedValue(original('ACTIVE'));
    await mount({ id: ids.condition });
    expect(host.textContent).toContain('启用的业务限定需先禁用后修改');
    expect(host.querySelector('input')).toBeNull();
    expect(button('下一步').disabled).toBe(true);
    expect(harness.fields).not.toHaveBeenCalled();
    expect(harness.update).not.toHaveBeenCalled();
  });
});
