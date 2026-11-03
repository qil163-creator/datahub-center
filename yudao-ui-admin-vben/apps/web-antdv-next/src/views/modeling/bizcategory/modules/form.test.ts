import type { App } from 'vue';

import { createApp } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import Form from './form.vue';

const state = vi.hoisted(() => ({
  lifecycle: undefined as undefined | {
    onConfirm: () => Promise<void>;
    onOpenChange: (open: boolean) => Promise<void>;
  },
  data: {} as { id?: number | string },
  values: {} as Record<string, unknown>,
  get: vi.fn(),
  create: vi.fn(),
  update: vi.fn(),
  unlock: vi.fn(),
  warning: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 'session', refreshToken: '' }),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: NonNullable<typeof state.lifecycle>) => {
    state.lifecycle = options;
    return [() => null, { getData: () => state.data, lock: vi.fn(), unlock: state.unlock, close: vi.fn() }];
  },
}));
vi.mock('#/adapter/form', () => ({
  useVbenForm: () => [() => null, {
    validate: vi.fn(async () => ({ valid: true })),
    getValues: async () => ({ ...state.values }),
    resetForm: vi.fn(),
    setFieldValue: vi.fn(),
    setValues: async (values: Record<string, unknown>) => { state.values = { ...values }; },
  }],
}));
vi.mock('#/api/modeling/bizcategory', () => ({
  getBizCategory: (...args: unknown[]) => state.get(...args),
  createBizCategory: (...args: unknown[]) => state.create(...args),
  updateBizCategory: (...args: unknown[]) => state.update(...args),
}));
vi.mock('antdv-next', () => ({ message: { warning: state.warning, success: vi.fn() } }));
vi.mock('#/locales', () => ({ $t: String }));
vi.mock('../data', () => ({ getUserMobile: () => '', useFormSchema: () => [] }));

let app: App | undefined;
beforeEach(() => {
  vi.clearAllMocks();
  state.data = {};
  state.values = {};
  state.get.mockResolvedValue({ id: '9007199254740997', dataDomainIds: ['9007199254740993', '9007199254740995'] });
  state.create.mockResolvedValue('9007199254740999');
  state.update.mockResolvedValue(true);
  app = createApp(Form);
  app.mount(document.createElement('div'));
});
afterEach(() => {
  app?.unmount();
  app = undefined;
});

describe('业务分类关联数据域无损提交', () => {
  it('新建将混合安全数字和大整数字符串按原顺序提交为十进制字符串', async () => {
    await state.lifecycle!.onOpenChange(true);
    state.values = { name: '业务分类', code: 'business', dataDomainIds: ['9007199254740993', 7, '9007199254740995'] };
    await state.lifecycle!.onConfirm();
    expect(state.create).toHaveBeenCalledWith(expect.objectContaining({ dataDomainIds: ['9007199254740993', '7', '9007199254740995'] }));
    expect(state.update).not.toHaveBeenCalled();
  });

  it('修改回显的大整数域编号直接保存仍保持原值', async () => {
    state.data = { id: '9007199254740997' };
    await state.lifecycle!.onOpenChange(true);
    await state.lifecycle!.onConfirm();
    expect(state.get).toHaveBeenCalledWith('9007199254740997');
    expect(state.update).toHaveBeenCalledWith(expect.objectContaining({ id: '9007199254740997', dataDomainIds: ['9007199254740993', '9007199254740995'] }));
    expect(state.create).not.toHaveBeenCalled();
  });

  it.each([9_007_199_254_740_992, '1e3', '1.5', '', null])('包含无效或已损精度的编号 %s 时整单拒绝，不静默改变关联集合', async (invalid) => {
    await state.lifecycle!.onOpenChange(true);
    state.values = { dataDomainIds: ['9007199254740993', invalid] };
    await state.lifecycle!.onConfirm();
    expect(state.create).not.toHaveBeenCalled();
    expect(state.update).not.toHaveBeenCalled();
    expect(state.warning).toHaveBeenCalledWith('关联数据域编号无效，请重新选择');
    expect(state.unlock).toHaveBeenCalled();
  });
});
