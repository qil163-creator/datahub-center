import type { App, SetupContext } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import IndicatorForm from './modules/form.vue';

const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
  modal: {} as {
    onConfirm: () => Promise<void>;
    onOpenChange: (open: boolean) => Promise<void>;
  },
  schema: [] as {
    componentProps?: (() => Record<string, unknown>) | Record<string, unknown>;
    fieldName: string;
  }[],
  formMounted: false,
  values: {} as Record<string, unknown>,
  fields: vi.fn(),
  atoms: vi.fn(),
  save: vi.fn(),
  update: vi.fn(),
  unlock: vi.fn(),
  close: vi.fn(),
  data: {} as { cloneId?: string; id?: string; indicatorType: string },
  detail: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => h('span') }));
vi.mock('./modules/atomic-editor.vue', () => ({ default: () => null }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: typeof harness.modal) => {
    harness.modal = options;
    return [
      (_: unknown, { slots }: SetupContext) => h('section', slots.default?.()),
      {
        getData: () => harness.data,
        setState: vi.fn(),
        lock: vi.fn(),
        unlock: harness.unlock,
        close: harness.close.mockImplementation(() =>
          options.onOpenChange(false),
        ),
      },
    ];
  },
}));
vi.mock('antdv-next', () => ({
  Alert: (props: { message?: string }, { slots }: SetupContext) =>
    h('aside', [props.message, slots.action?.()]),
  Button: (_: unknown, { slots }: SetupContext) =>
    h('button', slots.default?.()),
  message: { success: vi.fn() },
}));
vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [
    () => {
      harness.formMounted = true;
      return h('form');
    },
    {
      setState: (state: { schema: typeof harness.schema }) => {
        harness.schema = state.schema;
      },
      resetForm: async () => {
        harness.values = {};
      },
      setValues: async (values: Record<string, unknown>) => {
        if (!harness.formMounted) throw new Error('表单未挂载');
        Object.assign(harness.values, values);
      },
      setFieldValue: async (field: string, value: unknown) => {
        harness.values[field] = value;
      },
      getValues: async () => harness.values,
      validate: async () => ({ valid: true }),
    },
  ],
}));
vi.mock('#/api/modeling/indicator-model', () => ({
  createIndicator: (...args: unknown[]) => harness.save(...args),
  updateIndicator: (...args: unknown[]) => harness.update(...args),
  getIndicator: (...args: unknown[]) => harness.detail(...args),
  getAtomicIndicatorOptions: () => harness.atoms(),
  getIndicatorSourceFields: (id: string) => harness.fields(id),
}));
vi.mock('#/api/modeling/datadomain', () => ({
  getDataDomainSimpleList: vi
    .fn()
    .mockResolvedValue([{ id: '1', name: '交易', status: 0 }]),
}));
vi.mock('#/api/modeling/subjectdomain', () => ({
  getSubjectDomainList: vi.fn().mockResolvedValue([]),
}));
vi.mock('#/api/system/user', () => ({
  getSimpleUserList: vi.fn().mockResolvedValue([]),
}));
vi.mock('#/api/modeling/tablemodel', () => ({
  getTableModelPage: vi.fn().mockResolvedValue({ list: [], total: 0 }),
}));

let app: App;
let host: HTMLDivElement;
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
function props(field: string) {
  const value = harness.schema.find(
    (item) => item.fieldName === field,
  )!.componentProps;
  return typeof value === 'function' ? value() : value!;
}
async function change(field: string, value: string) {
  await (props(field).onChange as (id: string) => Promise<void>)(value);
}
beforeEach(async () => {
  vi.clearAllMocks();
  harness.schema = [];
  harness.formMounted = false;
  harness.values = {};
  harness.data = { indicatorType: 'DERIVED' };
  harness.detail.mockResolvedValue(undefined);
  harness.save.mockResolvedValue('9007199254740993');
  harness.update.mockResolvedValue(true);
  harness.atoms.mockResolvedValue([
    {
      id: '9007199254740997',
      name: '交易支付',
      code: 'PAYMENT',
      dataDomainId: '1',
      timeFieldUid: 'created-at',
    },
    {
      id: '9007199254740999',
      name: '无时间字段',
      code: 'NO_TIME',
      dataDomainId: '1',
    },
    {
      id: '9007199254741001',
      name: '其他板块',
      code: 'OTHER',
      dataDomainId: '2',
      timeFieldUid: 'created-at',
    },
  ]);
  harness.access = reactive({
    accessToken: 'session-a',
    refreshToken: 'refresh-a',
    accessCodes: ['modeling:model:query'],
  });
  harness.user = reactive({ userInfo: { userId: '1' }, userRoles: ['admin'] });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(IndicatorForm);
  app.mount(host);
  await nextTick();
  await harness.modal.onOpenChange(true);
});
afterEach(() => {
  app.unmount();
  host.remove();
});

describe('派生指标表单保留能力', () => {
  it('克隆重新取详情，清空编码与修订信息并按新建提交', async () => {
    const original = {
      id: '9223372036854775806',
      revision: '9007199254740993',
      indicatorType: 'DERIVED',
      code: 'payment',
      name: '支付',
      status: 'ACTIVE',
      dataDomainId: '1',
      baseIndicatorId: '9007199254740997',
      timePeriod: 'DAY',
      businessCondition: '仅已支付订单',
    };
    harness.detail.mockResolvedValue(original);
    await harness.modal.onOpenChange(false);
    harness.data = { indicatorType: 'DERIVED', cloneId: original.id };
    await harness.modal.onOpenChange(true);
    expect(harness.detail).toHaveBeenCalledWith(original.id);
    expect(harness.values.code).toBe('');
    expect(harness.values.id).toBeUndefined();
    expect(harness.values.expectedRevision).toBeUndefined();
    expect(harness.values.status).toBe('DRAFT');
    Object.assign(harness.values, { code: 'payment_copy' });
    await harness.modal.onConfirm();
    expect(harness.save).toHaveBeenCalledWith(
      expect.objectContaining({
        id: undefined,
        expectedRevision: undefined,
        code: 'payment_copy',
        baseIndicatorId: '9007199254740997',
        businessCondition: '仅已支付订单',
        status: 'DRAFT',
      }),
    );
    expect(original.code).toBe('payment');
    expect(original.revision).toBe('9007199254740993');
  });
  it('关闭重开后，旧提交完成不得解锁新提交', async () => {
    const first = deferred<string>();
    const second = deferred<string>();
    harness.save
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    Object.assign(harness.values, {
      code: 'first',
      name: '第一个',
      dataDomainId: '1',
    });
    const a = harness.modal.onConfirm();
    await nextTick();
    await nextTick();
    await harness.modal.onOpenChange(false);
    await harness.modal.onOpenChange(true);
    Object.assign(harness.values, {
      code: 'second',
      name: '第二个',
      dataDomainId: '1',
    });
    const b = harness.modal.onConfirm();
    await nextTick();
    await nextTick();
    harness.unlock.mockClear();
    first.resolve('1');
    await a;
    expect(harness.unlock).not.toHaveBeenCalled();
    second.resolve('2');
    await b;
    expect(harness.unlock).toHaveBeenCalledOnce();
  });

  it('修改从新读取详情取CAS，并保持派生来源编号为字符串', async () => {
    await harness.modal.onOpenChange(false);
    const detail = {
      id: '9223372036854775806',
      revision: '9007199254740993',
      indicatorType: 'DERIVED',
      code: 'paid_day',
      name: '每日支付',
      status: 'DRAFT',
      dataDomainId: '1',
      baseIndicatorId: '9007199254740997',
      timePeriod: 'DAY',
      businessCondition: '仅已支付订单',
    };
    harness.detail.mockResolvedValue(detail);
    harness.data = { indicatorType: 'DERIVED', id: detail.id };
    await harness.modal.onOpenChange(true);
    Object.assign(harness.values, {
      id: 'wrong-old-id',
      expectedRevision: '1',
    });
    await harness.modal.onConfirm();
    expect(harness.detail).toHaveBeenLastCalledWith(detail.id);
    expect(harness.update).toHaveBeenCalledWith(
      expect.objectContaining({
        id: detail.id,
        expectedRevision: detail.revision,
        baseIndicatorId: '9007199254740997',
        status: 'DRAFT',
      }),
    );
    expect(harness.save).not.toHaveBeenCalled();
  });

  it('选项未就绪时也挂载表单，避免初始化等待表单而死锁', () => {
    expect(harness.formMounted).toBe(true);
    expect(harness.values.timePeriod).toBe('DAY');
    expect(host.textContent).not.toContain('加载失败');
  });

  it('基指标按板块及时间字段筛选，切板块清除旧基指标', async () => {
    await change('dataDomainId', '1');
    expect(props('baseIndicatorId').options).toEqual([
      { value: '9007199254740997', label: '交易支付 (PAYMENT)' },
    ]);
    harness.values.baseIndicatorId = '9007199254740997';
    await change('dataDomainId', '2');
    expect(harness.values.baseIndicatorId).toBeUndefined();
    expect(props('baseIndicatorId').options).toEqual([
      { value: '9007199254741001', label: '其他板块 (OTHER)' },
    ]);
  });

  it('关闭弹窗后，未完成的基指标请求不会重新填充选项', async () => {
    await harness.modal.onOpenChange(false);
    const waiting = deferred<unknown[]>();
    harness.atoms.mockReturnValueOnce(waiting.promise);
    const pending = harness.modal.onOpenChange(true);
    await nextTick();
    await harness.modal.onOpenChange(false);
    waiting.resolve([
      {
        id: 'old',
        name: '旧原子指标',
        dataDomainId: '1',
        timeFieldUid: 'old-time',
      },
    ]);
    await pending;
    expect(props('baseIndicatorId').options).toEqual([]);
    expect(harness.values).toEqual({});
  });

  it('登录权限上下文变化关闭弹窗并拒收旧基指标响应', async () => {
    await harness.modal.onOpenChange(false);
    const waiting = deferred<unknown[]>();
    harness.atoms.mockReturnValueOnce(waiting.promise);
    const pending = harness.modal.onOpenChange(true);
    await nextTick();
    harness.close.mockClear();
    harness.access.accessCodes = [];
    await nextTick();
    expect(harness.close).toHaveBeenCalledOnce();
    waiting.resolve([
      {
        id: 'old',
        name: '旧原子指标',
        dataDomainId: '1',
        timeFieldUid: 'old-time',
      },
    ]);
    await pending;
    expect(props('baseIndicatorId').options).toEqual([]);
    expect(harness.values).toEqual({});
  });
});
