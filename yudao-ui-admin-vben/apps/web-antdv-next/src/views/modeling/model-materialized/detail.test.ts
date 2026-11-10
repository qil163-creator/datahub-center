/* eslint-disable vue/one-component-per-file -- 回归用例需要为布局、弹窗和流程组件提供独立测试桩。 */
import type { App, SetupContext } from 'vue';

import type { ModelMaterializedApi } from '#/api/modeling/model-materialized';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/modeling/model-materialized';

import Detail from './detail.vue';

const shared = vi.hoisted(() => ({
  access: { accessToken: 'session-a', refreshToken: '' },
  route: {
    params: { modelId: '9007199254740993123' },
    meta: { activePath: '/model/models/model-materialized' },
  },
  push: vi.fn(),
}));
vi.mock('vue-router', () => ({
  useRoute: () => shared.route,
  useRouter: () => ({ push: shared.push }),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => shared.access }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/hooks', () => ({ getDictLabel: () => '' }));
vi.mock('@vben/icons', () => ({ IconifyIcon: () => h('span') }));
vi.mock('@vben/utils', () => ({
  formatDate: String,
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
}));
vi.mock('antdv-next', () => {
  const container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Alert: container,
    Button: (_: unknown, { attrs, slots }: SetupContext) =>
      h('button', attrs, slots.default?.()),
    Checkbox: defineComponent({
      props: { checked: Boolean },
      emits: ['change'],
      setup(props, { emit, slots }) {
        return () => h('label', [
          h('input', {
            type: 'checkbox',
            checked: props.checked,
            onChange: (event: Event) => emit('change', event),
          }),
          slots.default?.(),
        ]);
      },
    }),
    Popover: defineComponent({
      setup: (_, { slots }) => () => h('div', [slots.default?.(), slots.content?.()]),
    }),
    Empty: defineComponent({
      props: { description: { type: String, default: '' } },
      setup: (props) => () => h('span', props.description),
    }),
    Spin: container,
    Modal: defineComponent({
      props: { open: Boolean, title: { type: String, default: '' } },
      setup:
        (props, { slots }) =>
        () =>
          props.open
            ? h('section', { class: 'test-modal' }, [
                h('h2', props.title),
                slots.default?.(),
              ])
            : null,
    }),
  };
});
vi.mock('#/components/list-pagination/index.vue', () => ({
  default: defineComponent({
      props: {
        current: { type: Number, default: 1 },
        pageSize: { type: Number, default: 10 },
        total: { type: Number, default: 0 },
      },
      emits: ['change'],
      setup: (props, { emit }) => () => h('button', {
        'aria-label': '下一页',
        disabled: props.current * props.pageSize >= props.total,
        onClick: () => emit('change', props.current + 1, props.pageSize),
      }, '下一页'),
    }),
}));
vi.mock('#/api/modeling/model-materialized', () => ({
  getModelMaterializedByModel: vi.fn(),
  getModelMaterializedLogPage: vi.fn(),
}));
vi.mock('./modules/publish-modal.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: vi.fn(), openRepublish: vi.fn() });
      return () => null;
    },
  }),
}));
vi.mock('./modules/recovery-modal.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: vi.fn() });
      return () => null;
    },
  }),
}));

let app: App | undefined;
let host: HTMLDivElement;

function model(
  id = shared.route.params.modelId,
): ModelMaterializedApi.Materialized {
  return {
    modelId: id,
    modelAlias: `模型-${id}`,
    modelName: 'dws_snapshot',
    creatorName: '设计者',
    tableType: 'SUMMARY',
    fields: [
      {
        fieldUid: 'stable-field',
        fieldName: 'frozen_snapshot_field',
        cnName: '已发布快照字段',
        pkFlag: true,
        nullableFlag: false,
        sort: 1,
      },
    ],
  };
}
async function flush() {
  for (let index = 0; index < 12; index++) await Promise.resolve();
  await nextTick();
}
function click(text: string) {
  const button = [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === text,
  );
  expect(button).toBeDefined();
  button!.click();
}
async function mount() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Detail);
  app.mount(host);
  await flush();
}

beforeEach(() => {
  vi.clearAllMocks();
  shared.access = reactive({ accessToken: 'session-a', refreshToken: '' });
  shared.route = reactive({
    params: { modelId: '9007199254740993123' },
    meta: { activePath: '/model/models/model-materialized' },
  });
  vi.mocked(api.getModelMaterializedByModel).mockImplementation(async (id) =>
    model(String(id)),
  );
  vi.mocked(api.getModelMaterializedLogPage).mockResolvedValue({
    list: [],
    total: 0,
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
});

describe('发布模型详情', () => {
  it('字段列设置与刷新保留原有功能', async () => {
    await mount();
    const option = [...host.querySelectorAll('label')].find((label) =>
      label.textContent?.includes('中文名称'),
    );
    const checkbox = option?.querySelector<HTMLInputElement>('input');
    expect(checkbox?.checked).toBe(true);
    checkbox!.click();
    await flush();
    expect(host.querySelector('.qd-materialized-fields-table')?.textContent).not.toContain('中文名称');
    host.querySelector<HTMLButtonElement>('[aria-label="刷新属性字段"]')!.click();
    await flush();
    expect(api.getModelMaterializedByModel).toHaveBeenCalledTimes(2);
  });

  it('空字段和空日志展示独立空状态，有数据时展示行', async () => {
    vi.mocked(api.getModelMaterializedByModel).mockResolvedValue({ ...model(), fields: [] });
    await mount();
    expect(host.querySelector('.qd-materialized-fields-table.is-empty')).not.toBeNull();
    expect(host.querySelector('.qd-materialized-table-empty')?.textContent).toContain('暂无属性字段');
    click('发布日志');
    await flush();
    expect(host.querySelector('.qd-materialized-logs-table.is-empty')).not.toBeNull();
    expect(host.querySelector('.qd-materialized-table-empty')?.textContent).toContain('暂无发布日志');
    vi.mocked(api.getModelMaterializedLogPage).mockResolvedValue({ list: [{ id: '1', modelName: '已发布' }], total: 1 });
    host.querySelector<HTMLButtonElement>('[aria-label="刷新发布日志"]')!.click();
    await flush();
    expect(host.querySelector('.qd-materialized-logs-table.is-empty')).toBeNull();
    expect(host.querySelector('.qd-materialized-table-empty')).toBeNull();
    expect(host.querySelector('.qd-materialized-logs-table')?.textContent).toContain('已发布');
  });

  it('使用无损模型编号与后端冻结字段，并保留字段详情和返回操作', async () => {
    await mount();
    expect(api.getModelMaterializedByModel).toHaveBeenCalledWith(
      '9007199254740993123',
    );
    expect(host.textContent).toContain('frozen_snapshot_field');
    click('详情');
    await flush();
    expect(host.querySelector('.test-modal')?.textContent).toContain(
      'stable-field',
    );
    expect(host.querySelector('.test-modal')?.textContent).toContain(
      '是否必填是',
    );
    click('返回');
    expect(shared.push).toHaveBeenCalledWith('/model/models/model-materialized');
  });

  it('对象切换后拒绝旧详情响应覆盖新模型', async () => {
    let resolvePrevious: (
      value: ModelMaterializedApi.Materialized,
    ) => void = () => {};
    vi.mocked(api.getModelMaterializedByModel).mockImplementationOnce(
      () =>
        new Promise((resolve) => {
          resolvePrevious = resolve;
        }),
    );
    await mount();
    shared.route.params.modelId = '9007199254740993124';
    await flush();
    resolvePrevious(model('9007199254740993123'));
    await flush();
    expect(host.textContent).toContain('模型-9007199254740993124');
    expect(host.textContent).not.toContain('模型-9007199254740993123');
  });

  it('日志按当前模型在服务端分页，登录会话变化清理旧日志', async () => {
    vi.mocked(api.getModelMaterializedLogPage).mockResolvedValue({
      list: [
        {
          id: '9007199254740993199',
          modelName: 'captured-log',
          status: 'SUCCESS',
        },
      ],
      total: 11,
    });
    await mount();
    click('发布日志');
    await flush();
    expect(api.getModelMaterializedLogPage).toHaveBeenLastCalledWith({
      modelId: '9007199254740993123',
      pageNo: 1,
      pageSize: 10,
    });
    host.querySelector<HTMLButtonElement>('[aria-label="下一页"]')!.click();
    await flush();
    expect(api.getModelMaterializedLogPage).toHaveBeenLastCalledWith({
      modelId: '9007199254740993123',
      pageNo: 2,
      pageSize: 10,
    });
    shared.access.accessToken = 'session-b';
    await flush();
    expect(host.textContent).not.toContain('captured-log');
    expect(
      host.querySelector('[role="tab"][aria-selected="true"]')?.textContent,
    ).toBe('属性字段');
  });
});
