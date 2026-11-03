import type { App, SetupContext } from 'vue';

import type { FoundationDwLayerApi } from '#/api/modeling/dwlayer';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import DwLayer from './index.vue';

const harness = vi.hoisted(() => ({
  access: {} as {
    accessCodes: string[];
    accessToken: string;
    refreshToken: string;
  },
  user: {} as { userInfo: { userId: string }; userRoles: string[] },
  load: vi.fn(),
  push: vi.fn(),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => harness.user,
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => harness.access.accessCodes.includes(code)),
  }),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: harness.push }) }));
vi.mock('@vben/common-ui', () => {
  const overlay = () => {
    const api = {};
    // 连接组件挂载后才提供方法，避免测试掩盖首次渲染的生命周期约束。
    return [
      () => {
        Object.assign(api, {
          close: vi.fn(),
          open: vi.fn(),
          setData: vi.fn().mockReturnThis(),
        });
        return null;
      },
      api,
    ];
  };
  return {
    Page: (_: unknown, { slots }: SetupContext) => h('main', slots.default?.()),
    useVbenDrawer: overlay,
    useVbenModal: overlay,
    confirm: vi.fn().mockResolvedValue(undefined),
  };
});
vi.mock('@vben/icons', () => ({ IconifyIcon: () => null }));
vi.mock('antdv-next', () => {
  const slot = (_: unknown, { slots }: SetupContext) =>
    h('div', slots.default?.());
  return {
    Alert: (props: { message?: string }, { slots }: SetupContext) =>
      h('aside', [props.message, slots.action?.()]),
    Button: (_: unknown, { slots, attrs }: SetupContext) =>
      h('button', attrs, slots.default?.()),
    Empty: (props: { description?: string }) => h('p', props.description),
    Input: (props: { value?: string }, { attrs }: SetupContext) =>
      h('input', {
        ...attrs,
        value: props.value,
        onInput: (event: Event) =>
          (attrs['onUpdate:value'] as (value: string) => void)(
            (event.target as HTMLInputElement).value,
          ),
      }),
    Select: () => null,
    Dropdown: slot,
    Menu: Object.assign(slot, { Item: slot }),
    Skeleton: () => h('p', '正在加载'),
    Table: () => null,
    Tag: slot,
    message: { success: vi.fn() },
  };
});
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: (props: {
    actions?: {
      auth?: string[];
      ifShow?: boolean;
      label: string;
      onClick?: () => void;
    }[];
  }) =>
    h(
      'div',
      props.actions
        ?.filter(
          (action) =>
            action.ifShow !== false &&
            (!action.auth ||
              action.auth.some((code) =>
                harness.access.accessCodes.includes(code),
              )),
        )
        .map((action) =>
          h('button', { onClick: action.onClick }, action.label),
        ),
    ),
}));
vi.mock('#/api/modeling/dwlayer', () => ({
  getDwLayerList: () => harness.load(),
  deleteDwLayer: vi.fn(),
  updateDwLayerStatus: vi.fn(),
}));
vi.mock('#/views/dashboard/shared/route-resolver', () => ({
  DASHBOARD_TARGETS: { logicModel: {} },
  useDashboardNav: () => ({ resolve: () => '/modeling/table-model' }),
}));
vi.mock('./data', () => ({
  getCategoryCreateDefaults: () => ({}),
  getLayerCreateDefaults: () => ({}),
}));
vi.mock('./modules/form.vue', () => ({ default: () => null }));
vi.mock('./modules/detail.vue', () => ({ default: () => null }));

let app: App | undefined;
let host: HTMLDivElement;

function data(name = '操作数据层'): FoundationDwLayerApi.DwLayer[] {
  return [
    { id: '10', nodeType: 'CATEGORY', layerName: '数据仓库', status: 0 },
    {
      id: '9223372036854775806',
      parentId: '10',
      nodeType: 'LAYER',
      layerName: name,
      layerCode: 'ODS',
      tablePrefix: 'ods_',
      ownerName: '张三',
      logicModelCount: '9007199254740993',
      logicModelCountAvailable: true,
      isBuiltin: 1,
      status: 0,
    },
    {
      id: '12',
      parentId: '10',
      nodeType: 'LAYER',
      layerName: '自定义分层',
      layerCode: 'CUSTOM',
      logicModelCount: 0,
      logicModelCountAvailable: true,
      isBuiltin: 0,
      status: 0,
    },
    {
      id: '13',
      parentId: '10',
      nodeType: 'LAYER',
      layerName: '应用层',
      layerCode: 'ADS',
      logicModelCount: null,
      logicModelCountAvailable: false,
      status: 0,
    },
  ].map((row) => ({ ownerUserId: '1', description: '', remark: '', ...row }));
}
function deferred() {
  let resolve!: (value: FoundationDwLayerApi.DwLayer[]) => void;
  const promise = new Promise<FoundationDwLayerApi.DwLayer[]>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}
async function flush() {
  await nextTick();
  await nextTick();
}
async function mount() {
  host = document.createElement('div');
  app = createApp(DwLayer);
  app.directive('access', () => {});
  app.mount(host);
  await flush();
}
function button(label: string) {
  return [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  );
}

beforeEach(() => {
  vi.clearAllMocks();
  localStorage.clear();
  harness.access = reactive({
    accessCodes: ['modeling:model:query', 'modeling:dwlayer:query'],
    accessToken: 'session-a',
    refreshToken: '',
  });
  harness.user = reactive({
    userInfo: { userId: '1' },
    userRoles: ['modeler'],
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('数仓分层卡片与请求隔离', () => {
  it('展示自定义分层与真实零值，权限不足的计数不冒充零，跳转保留 Long ID', async () => {
    harness.load.mockResolvedValue(data());
    await mount();
    expect(host.querySelectorAll('.layer-card')).toHaveLength(3);
    expect(host.textContent).toContain('9,007,199,254,740,993');
    expect(host.textContent).toContain('张三');
    const cards = host.querySelectorAll('.layer-card');
    expect(cards[1]?.textContent).toContain('0 个');
    expect(
      cards[2]?.querySelector('.layer-card__metrics strong')?.textContent,
    ).toBe('—');
    expect(cards[2]?.textContent).not.toContain('查看模型');
    button('查看模型')?.click();
    expect(harness.push).toHaveBeenCalledWith({
      path: '/modeling/table-model',
      query: { layerId: '9223372036854775806' },
    });
    const search = host.querySelector('input')!;
    search.value = 'CUSTOM';
    search.dispatchEvent(new Event('input'));
    await flush();
    expect(host.querySelectorAll('.layer-card')).toHaveLength(1);
    expect(host.querySelector('.layer-card')?.textContent).toContain(
      '自定义分层',
    );
  });

  it('会话 A→B→A 切换时忽略所有旧结果，权限变化立即清空旧卡片', async () => {
    const old = deferred();
    const switched = deferred();
    const current = deferred();
    harness.load
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(switched.promise)
      .mockReturnValueOnce(current.promise);
    await mount();
    harness.access.accessToken = 'session-b';
    harness.access.accessToken = 'session-a';
    current.resolve(data('最新分层'));
    await flush();
    old.resolve(data('过期分层'));
    switched.resolve(data('其他账号分层'));
    await flush();
    expect(host.textContent).toContain('最新分层');
    expect(host.textContent).not.toContain('过期分层');
    expect(host.textContent).not.toContain('其他账号分层');
    harness.load.mockReturnValueOnce(new Promise(() => {}));
    harness.access.accessCodes = [];
    await flush();
    expect(
      host.querySelectorAll('.layer-card:not(.layer-card--loading)'),
    ).toHaveLength(0);
  });

  it('加载失败可重试，空态不展示伪造分层', async () => {
    harness.load.mockRejectedValueOnce(new Error('network'));
    await mount();
    expect(host.textContent).toContain('分层配置加载失败');
    expect(host.querySelectorAll('.layer-card')).toHaveLength(0);
    harness.load.mockResolvedValueOnce([]);
    button('重试')?.click();
    await flush();
    expect(host.textContent).toContain('暂无分层配置');
    expect(host.textContent).not.toContain('分层配置加载失败');
  });

  it('不再挂载顶部说明横幅，架构仍可独立折叠', async () => {
    harness.load.mockResolvedValue(data());
    await mount();
    expect(host.querySelector('.warehouse-help')).toBeNull();
    button('收起')?.click();
    await flush();
    expect(host.querySelector('#warehouse-flow')).toBeNull();
  });
});
