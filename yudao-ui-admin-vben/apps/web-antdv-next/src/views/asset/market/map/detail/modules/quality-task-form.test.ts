/* eslint-disable vue/one-component-per-file -- 测试桩集中验证资产到治理的提交边界 */
import type { App, ComponentPublicInstance } from 'vue';

import type { QualityTaskApi } from '#/api/governance/quality/task';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import QualityTaskForm from './quality-task-form.vue';

const mock = vi.hoisted(() => ({
  access: {
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:quality-task:manage', 'governance:quality-task:query'],
  },
  user: { userInfo: { userId: '1' } },
  create: vi.fn(),
  update: vi.fn(),
  catalog: vi.fn(),
  recommend: vi.fn(),
  refresh: vi.fn(),
  warning: vi.fn(),
  success: vi.fn(),
  options: undefined as
    | undefined
    | {
        onConfirm: () => Promise<void>;
        onOpenChange: (value: boolean) => Promise<void>;
      },
  data: {} as Record<string, unknown>,
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => mock.access,
  useUserStore: () => mock.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => mock.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: { render: () => null } }));
vi.mock('#/api/asset/qualitytask', () => ({
  createAssetQualityTask: mock.create,
  updateAssetQualityTask: mock.update,
}));
vi.mock('#/api/governance/quality/task', () => ({
  getQualityTaskRuleCatalog: mock.catalog,
  suggestQualityTaskRules: mock.recommend,
}));
vi.mock('#/views/governance/quality/task/modules/rule-editor.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: vi.fn(), close: vi.fn() });
      return () => null;
    },
  }),
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: NonNullable<typeof mock.options>) => {
    mock.options = options;
    const api = {
      getData: () => mock.data,
      lock: vi.fn(),
      unlock: vi.fn(),
      close: async () => options.onOpenChange(false),
      setState: ({ isOpen }: { isOpen: boolean }) =>
        options.onOpenChange(isOpen),
    };
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('section', slots.default?.()),
      }),
      api,
    ];
  },
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: box,
    Button: box,
    Form: box,
    FormItem: box,
    Input: box,
    Table: box,
    Tag: box,
    message: { success: mock.success, warning: mock.warning },
  };
});
interface State {
  taskName: string;
  evaluations: QualityTaskApi.Evaluation[];
}
let app: App | undefined;
let state: State;
let host: HTMLDivElement;
const evaluation: QualityTaskApi.Evaluation = {
  id: '9007199254740995',
  objectKey: 'untrusted',
  evaluationName: '编码长度',
  ruleCode: 'LENGTH',
  strategyKey: 'LENGTH_VALIDATION',
  qualityDim: 'COMPLETENESS',
  severity: 'CRITICAL',
  status: 0,
  columnNames: ['SECCD'],
  config: { minLength: '1', maxLength: '50' },
};
beforeEach(async () => {
  vi.resetAllMocks();
  mock.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    loginExpired: false,
    accessCodes: ['asset:quality-task:manage', 'governance:quality-task:query'],
  });
  mock.user = reactive({ userInfo: { userId: '1' } });
  mock.data = {
    asset: {
      id: '9223372036854775807',
      datasourceId: 2,
      tableName: 'ODS_SEC',
      assetName: '断面表',
      techAvailable: 1,
    },
  };
  mock.catalog.mockResolvedValue([]);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(QualityTaskForm, { onSuccess: mock.refresh });
  const vm = app.mount(host) as ComponentPublicInstance;
  state = (vm.$ as unknown as { setupState: State }).setupState;
  await mock.options!.onOpenChange(true);
  state.evaluations = [structuredClone(evaluation)];
});
afterEach(() => {
  app?.unmount();
  host.remove();
});
describe('资产内联质量定义', () => {
  it('保持资产ID与规则配置，剥离浏览器的评测ID及对象绑定键', async () => {
    await mock.options!.onConfirm();
    const payload = mock.create.mock.calls[0]![0];
    expect(payload.assetId).toBe('9223372036854775807');
    expect(payload.rules).toEqual([]);
    expect(payload.evaluations[0]).toMatchObject({
      columnNames: ['SECCD'],
      config: { minLength: '1', maxLength: '50' },
    });
    expect(payload.evaluations[0]).not.toHaveProperty('id');
    expect(payload.evaluations[0]).not.toHaveProperty('objectKey');
    expect(payload.enabled).toBe(0);
  });
  it('来源快照缺少真实数据连接时不提交可执行任务', async () => {
    mock.data = {
      asset: {
        id: '9223372036854775807',
        tableName: 'ODS_SEC',
        techAvailable: 0,
      },
    };
    await mock.options!.onOpenChange(true);
    state.evaluations = [evaluation];
    await mock.options!.onConfirm();
    expect(mock.create).not.toHaveBeenCalled();
  });
  it('创建同步未知失败后刷新并关闭创建窗口，提示从原任务重试', async () => {
    mock.create.mockRejectedValue(new Error('sync unknown'));
    await mock.options!.onConfirm();
    expect(mock.refresh).toHaveBeenCalledOnce();
    expect(mock.warning).toHaveBeenCalledWith(
      '任务定义可能已保存，请从原任务编辑或执行重试。',
    );
    await mock.options!.onConfirm();
    expect(mock.create).toHaveBeenCalledOnce();
    expect(mock.success).not.toHaveBeenCalled();
  });
  it('同令牌会话失效后禁止提交旧定义', async () => {
    mock.access.loginExpired = true;
    await nextTick();
    await mock.options!.onConfirm();
    expect(mock.create).not.toHaveBeenCalled();
  });
});
