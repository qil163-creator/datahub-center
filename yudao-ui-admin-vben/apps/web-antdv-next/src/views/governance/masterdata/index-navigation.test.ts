import type { App, SetupContext } from 'vue';

import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { createApp, h, nextTick, reactive, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ModelList from './index.vue';

interface Action {
  auth?: string[];
  label: string;
  onClick: () => void;
}
const state = vi.hoisted(() => ({
  access: {} as { accessCodes: string[]; accessToken: string },
  model: {} as GovernanceMasterDataApi.Model,
  push: vi.fn(),
  modal: vi.fn(),
  detailAction: undefined as Action | undefined,
}));
const container = (_: unknown, { slots }: SetupContext) =>
  h('div', slots.default?.());

vi.mock('vue-router', () => ({ useRouter: () => ({ push: state.push }) }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => state.access.accessCodes.includes(code)),
  }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => state.access,
  useUserStore: () => ({ userInfo: { userId: '1' } }),
}));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('@vueuse/core', () => ({ useMediaQuery: () => ref(false) }));
vi.mock('@vben/common-ui', () => ({
  Page: (props: unknown, context: SetupContext) => container(props, context),
  useVbenModal: (options: unknown) => {
    state.modal(options);
    return [() => null, {}];
  },
}));
vi.mock('antdv-next', () => ({
  Alert: () => null,
  Button: () => null,
  Input: () => null,
  message: { success: vi.fn(), warning: vi.fn() },
  Modal: { confirm: vi.fn() },
  Switch: () => null,
  Tag: (props: unknown, context: SetupContext) => container(props, context),
  Tooltip: (props: unknown, context: SetupContext) => container(props, context),
  Tree: () => null,
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: Object.assign(
    (props: { actions: Action[] }) => {
      state.detailAction = props.actions.find(
        (action) => action.label === '详情',
      );
      return props.actions
        .filter(
          (action) =>
            action.label === '详情' &&
            action.auth?.some((code) =>
              state.access.accessCodes.includes(code),
            ),
        )
        .map((action) =>
          h(
            'button',
            { 'data-action': action.label, onClick: action.onClick },
            action.label,
          ),
        );
    },
    { props: ['actions'] },
  ),
  useVbenVxeGrid: () => [
    (_: unknown, { slots }: SetupContext) =>
      h('section', [
        slots.modelInfo?.({ row: state.model }),
        slots.actions?.({ row: state.model }),
      ]),
    {
      formApi: { getValues: async () => ({}) },
      setGridOptions: vi.fn(),
      setLoading: vi.fn(),
      grid: { clearCheckboxRow: vi.fn() },
    },
  ],
}));
vi.mock('#/api/governance/masterdomain', () => ({
  getMasterDomainSimpleList: async () => [],
}));
vi.mock('#/api/governance/masterdata', () => ({
  cancelReleaseMasterModel: vi.fn(),
  deleteMasterModel: vi.fn(),
  deleteMasterModelList: vi.fn(),
  getMasterModelDataCount: vi.fn(),
  getMasterModelPage: async () => ({ list: [state.model], total: 1 }),
  getMasterModelStatistics: async () => ({
    total: 1,
    released: 1,
    draft: 0,
    offline: 0,
  }),
  releaseMasterModel: vi.fn(),
  verifyMasterModelRelease: vi.fn(),
}));
vi.mock('#/components/text-ellipsis-cell', () => ({
  TextEllipsisCell: () => null,
}));
vi.mock('#/components/tree-toolbar/index.vue', () => ({ default: () => null }));
vi.mock('../mastercommon/master-workspace.vue', () => ({
  default: (props: unknown, context: SetupContext) => container(props, context),
}));
vi.mock('./modules/model-form.vue', () => ({ default: () => null }));

let app: App | undefined;
let host: HTMLDivElement;
async function mount() {
  host = document.createElement('div');
  app = createApp(ModelList);
  app.mount(host);
  for (let index = 0; index < 8; index++) await Promise.resolve();
  await nextTick();
}
function titleButton() {
  return host.querySelector<HTMLButtonElement>('.master-model-info__title')!;
}
beforeEach(() => {
  vi.clearAllMocks();
  state.access = reactive({
    accessToken: 'current-session',
    accessCodes: ['governance:master-model:query'],
  });
  state.model = reactive({
    id: '9007199254740993123',
    tableCn: '人员模型',
    tableName: 'md_person',
    status: 1,
  });
  state.detailAction = undefined;
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('主数据模型详情入口', () => {
  it('模型名称与详情操作都进入独立页面，保留无损编号及编辑弹窗', async () => {
    await mount();
    titleButton().click();
    host.querySelector<HTMLButtonElement>('[data-action="详情"]')!.click();
    expect(state.push).toHaveBeenCalledTimes(2);
    expect(state.push).toHaveBeenNthCalledWith(1, {
      name: 'GovernanceMasterModelDetail',
      params: { id: '9007199254740993123' },
    });
    expect(state.push).toHaveBeenNthCalledWith(2, {
      name: 'GovernanceMasterModelDetail',
      params: { id: '9007199254740993123' },
    });
    expect(state.modal).toHaveBeenCalledOnce();
  });

  it.each([undefined, '01', '1.5', 9_007_199_254_740_992])(
    '拒绝无效模型编号 %j，避免打开错误对象',
    async (id) => {
      state.model.id = id;
      await mount();
      titleButton().click();
      state.detailAction!.onClick();
      expect(state.push).not.toHaveBeenCalled();
    },
  );

  it('权限撤销后禁用模型名称并拒绝旧操作回调', async () => {
    await mount();
    const staleAction = state.detailAction!;
    state.access.accessCodes = ['governance:master-data:query'];
    await nextTick();
    expect(titleButton().disabled).toBe(true);
    expect(host.querySelector('[data-action="详情"]')).toBeNull();
    staleAction.onClick();
    expect(state.push).not.toHaveBeenCalled();
  });
});
