import type { App } from 'vue';

import type { BusinessConditionApi } from '#/api/modeling/business-condition';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ConditionDetail from './detail.vue';

const state = vi.hoisted(() => ({
  route: {} as { params: { id: string } },
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  canQuery: true,
  get: vi.fn(),
  push: vi.fn(),
}));
vi.mock('vue-router', () => ({
  useRoute: () => state.route,
  useRouter: () => ({ push: state.push }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => state.canQuery }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => state.access,
  useUserStore: () => ({ userInfo: { userId: '1' }, userRoles: ['tester'] }),
}));
vi.mock('@vben/hooks', () => ({
  getDictOptions: () => [{ value: '1', label: '天（T+1）' }],
}));
vi.mock('@vben/utils', () => ({ formatDateTime: () => '2026-10-22 12:00' }));
vi.mock('#/api/modeling/business-condition', () => ({
  getBusinessCondition: (id: string) => state.get(id),
}));
vi.mock('../indicator-model/modules/use-metric-options', () => ({
  useMetricOptions: () => ({
    domainLabel: () => '水利监测',
    subjectLabel: () => '防汛预警',
    userLabel: () => '-',
    userPhone: () => '',
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
vi.mock('antdv-next', () => {
  const plain = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    ConfigProvider: plain,
    Spin: plain,
    TabPane: plain,
    Tag: plain,
    Descriptions: plain,
    DescriptionsItem: plain,
    theme: { defaultAlgorithm: undefined },
    Alert: defineComponent({
      props: { message: String },
      setup:
        (props, { slots }) =>
        () =>
          h('aside', [props.message, slots.action?.()]),
    }),
    Button: defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h('button', attrs, slots.default?.()),
    }),
    Tabs: defineComponent({
      emits: ['update:activeKey'],
      setup:
        (_, { emit }) =>
        () =>
          h(
            'button',
            {
              'data-basic': true,
              onClick: () => emit('update:activeKey', 'basic'),
            },
            '基本信息',
          ),
    }),
  };
});

let app: App | undefined;
let host: HTMLDivElement;
const id = '9007199254740993';
function model(modelId = id): BusinessConditionApi.Model {
  return {
    id: modelId,
    revision: '9007199254740997',
    code: `RED_${modelId}`,
    name: `红色预警${modelId}`,
    status: 'DRAFT',
    dataDomainId: '11',
    subjectDomainId: '12',
    sourceModelId: '13',
    configType: 'BASIC',
    logicMode: 'UNIFIED',
    expression: "warning_level_code = 'RED'",
    dataFreshness: '1',
    sourceModelCode: 'DIM_FLOOD_WARNING',
    ownerUserName: '负责人',
    ownerUserPhone: '13800000000',
    creatorName: '创建人',
    creatorPhone: '13800000001',
    updaterName: '修改人',
    updaterPhone: '13800000002',
  };
}
async function flush() {
  for (let count = 0; count < 10; count++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mount() {
  app = createApp(ConditionDetail);
  app.mount(host);
  await flush();
}
beforeEach(() => {
  vi.clearAllMocks();
  state.canQuery = true;
  state.route = reactive({ params: { id } });
  state.access = reactive({
    accessToken: 'session',
    refreshToken: '',
    accessCodes: [],
  });
  state.get.mockImplementation(async (modelId: string) => model(modelId));
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('业务限定详情', () => {
  it('按真实字段回显基本定义及联系方式，时效响应字典', async () => {
    await mount();
    expect(host.textContent).toContain('功能建设中');
    host.querySelector<HTMLButtonElement>('[data-basic]')?.click();
    await flush();
    for (const value of [
      'DIM_FLOOD_WARNING',
      '天（T+1）',
      "warning_level_code = 'RED'",
      '负责人',
      '13800000000',
      '创建人',
      '13800000001',
      '修改人',
      '13800000002',
    ])
      expect(host.textContent).toContain(value);
  });
  it('切换对象后丢弃旧详情迟到响应，保留完整字符串编号', async () => {
    let complete: (value: BusinessConditionApi.Model) => void = () => {};
    state.get.mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          complete = resolve;
        }),
    );
    await mount();
    state.route.params.id = '9007199254741001';
    await flush();
    complete(model(id));
    await flush();
    expect(state.get).toHaveBeenLastCalledWith('9007199254741001');
    expect(host.textContent).toContain('红色预警9007199254741001');
    expect(host.textContent).not.toContain(`红色预警${id}`);
  });
  it('无权限时不请求详情，失败后可重新读取', async () => {
    state.canQuery = false;
    await mount();
    expect(state.get).not.toHaveBeenCalled();
    expect(host.textContent).toContain('暂无业务限定查询权限');
    state.canQuery = true;
    state.get.mockRejectedValueOnce(new Error('offline'));
    [...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.includes('重试'))
      ?.click();
    await flush();
    expect(host.textContent).toContain('详情加载失败');
    [...host.querySelectorAll<HTMLButtonElement>('button')]
      .find((button) => button.textContent?.includes('重试'))
      ?.click();
    await flush();
    expect(host.textContent).toContain(`红色预警${id}`);
  });
});
