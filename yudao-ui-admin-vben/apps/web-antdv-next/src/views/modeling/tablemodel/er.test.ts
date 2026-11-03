import type { App, PropType } from 'vue';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as layerApi from '#/api/modeling/dwlayer';
import * as api from '#/api/modeling/tablemodel';

import ErPage from './er.vue';

const shared = vi.hoisted(() => ({
  route: { query: {} as Record<string, string> },
  openRelation: vi.fn(),
  leave: undefined as (() => Promise<boolean>) | undefined,
  confirm: vi.fn(),
}));
vi.mock('vue-router', () => ({
  useRoute: () => shared.route,
  useRouter: () => ({ back: vi.fn() }),
  onBeforeRouteLeave: (handler: () => Promise<boolean>) => {
    shared.leave = handler;
  },
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({ accessToken: 1, refreshToken: '' }),
}));
vi.mock('@vben/utils', () => ({ handleTree: (items: unknown) => items }));
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
    Button: plain,
    Tag: plain,
    Drawer: plain,
    TreeSelect: defineComponent({
      props: ['value'],
      emits: ['update:value', 'change'],
      setup:
        (_, { emit }) =>
        () =>
          h('button', {
            class: 'switch-layer',
            onClick: () => {
              emit('update:value', '2');
              emit('change', '2');
            },
          }),
    }),
    message: { success: vi.fn(), warning: vi.fn() },
    Modal: { confirm: shared.confirm },
  };
});
vi.mock('#/components/er-designer', () => ({
  RELATION_TYPE_LABELS: {},
  ErDesigner: defineComponent({
    props: { nodes: Array as PropType<{ title: string }[]> },
    emits: ['connect', 'save-layout', 'dirty-change'],
    setup: (props, { emit, expose }) => {
      expose({ markLayoutSaved: vi.fn() });
      return () =>
        h('div', [
          ...(props.nodes ?? []).map((node) => h('span', node.title)),
          h(
            'button',
            {
              class: 'connect',
              onClick: () =>
                emit('connect', {
                  sourceId: '9007199254740993',
                  targetId: '9007199254740995',
                  sourceField: 'customer_id',
                  targetField: 'id',
                }),
            },
            '关联',
          ),
          h(
            'button',
            {
              class: 'layout',
              onClick: () =>
                emit('save-layout', [{ id: '9007199254740993', x: 10, y: 20 }]),
            },
            '保存布局',
          ),
          h(
            'button',
            {
              class: 'dirty-layout',
              onClick: () => emit('dirty-change', true),
            },
            '调整布局',
          ),
        ]);
    },
  }),
}));
vi.mock('./modules/relation-form.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ open: shared.openRelation });
      return () => h('div');
    },
  }),
}));
vi.mock('./modules/model-editor.vue', () => ({
  default: defineComponent({ setup: () => () => h('div') }),
}));
vi.mock('#/api/modeling/dwlayer', () => ({ getDwLayerList: vi.fn() }));
vi.mock('#/api/modeling/tablemodel', () => ({
  getTableModelDiagram: vi.fn(),
  deleteTableModelRelation: vi.fn(),
  saveTableModelLayout: vi.fn(),
}));

let app: App | undefined;
let host: HTMLDivElement;
const layers = [
  {
    id: '1',
    nodeType: 'LAYER',
    layerCode: 'DWD',
    layerName: '明细',
    ownerUserId: 1,
    description: '',
    remark: '',
  },
  {
    id: '2',
    nodeType: 'LAYER',
    layerCode: 'DIM',
    layerName: '维度',
    ownerUserId: 1,
    description: '',
    remark: '',
  },
];
const diagram = {
  models: [
    {
      id: '9007199254740993',
      name: '订单明细',
      layerId: '1',
      status: 'DRAFT',
      revision: '9007199254740997',
      layoutRevision: '9007199254740999',
      fields: [{ fieldUid: 'customer-fk', fieldName: 'customer_id' }],
    },
    {
      id: '9007199254740995',
      name: '客户维度',
      layerId: '2',
      status: 'DRAFT',
      revision: 2,
      layoutRevision: 3,
      fields: [{ fieldUid: 'customer-pk', fieldName: 'id' }],
    },
  ],
};
async function flush() {
  for (let i = 0; i < 8; i++) await Promise.resolve();
  await nextTick();
}
function mount() {
  app = createApp(ErPage);
  app.mount(host);
}
beforeEach(() => {
  vi.clearAllMocks();
  shared.route.query = {};
  host = document.createElement('div');
  document.body.append(host);
  vi.mocked(layerApi.getDwLayerList).mockResolvedValue(layers);
  vi.mocked(api.getTableModelDiagram).mockResolvedValue(diagram);
  vi.mocked(api.saveTableModelLayout).mockResolvedValue(true);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('unified ER', () => {
  it('defaults to all layers and connects a detail table to a dimension table', async () => {
    mount();
    await flush();
    expect(api.getTableModelDiagram).toHaveBeenCalledWith({
      layerId: undefined,
    });
    expect(host.textContent).toContain('订单明细');
    expect(host.textContent).toContain('客户维度');
    host.querySelector<HTMLButtonElement>('.connect')!.click();
    expect(shared.openRelation).toHaveBeenCalledWith({
      sourceModelId: '9007199254740993',
      targetModelId: '9007199254740995',
      sourceField: 'customer_id',
      targetField: 'id',
    });
  });
  it('saves layout using its own lossless revision instead of the design revision', async () => {
    mount();
    await flush();
    host.querySelector<HTMLButtonElement>('.layout')!.click();
    await flush();
    expect(api.saveTableModelLayout).toHaveBeenCalledWith({
      positions: [
        {
          modelId: '9007199254740993',
          posX: 10,
          posY: 20,
          expectedLayoutRevision: '9007199254740999',
        },
      ],
    });
  });
  it('ignores an old layer response after a new filter is selected', async () => {
    let resolveOld!: (value: typeof diagram) => void;
    vi.mocked(api.getTableModelDiagram)
      .mockReturnValueOnce(
        new Promise((resolve) => {
          resolveOld = resolve;
        }),
      )
      .mockResolvedValueOnce({
        models: [{ id: '2', name: '新分层', revision: 1, layoutRevision: 1 }],
      });
    mount();
    await flush();
    host.querySelector<HTMLButtonElement>('.switch-layer')!.click();
    await flush();
    resolveOld(diagram);
    await flush();
    expect(host.textContent).toContain('新分层');
    expect(host.textContent).not.toContain('订单明细');
  });
  it('does not load a diagram after unmount while layer options are pending', async () => {
    let resolveLayers!: (value: typeof layers) => void;
    vi.mocked(layerApi.getDwLayerList).mockReturnValueOnce(
      new Promise((resolve) => {
        resolveLayers = resolve;
      }),
    );
    mount();
    app!.unmount();
    app = undefined;
    resolveLayers(layers);
    await flush();
    expect(api.getTableModelDiagram).not.toHaveBeenCalled();
  });
  it('keeps unsaved layout when leaving or switching layer is cancelled', async () => {
    shared.confirm.mockImplementation((options: { onCancel: () => void }) =>
      options.onCancel(),
    );
    mount();
    await flush();
    host.querySelector<HTMLButtonElement>('.dirty-layout')!.click();
    await flush();
    expect(await shared.leave!()).toBe(false);
    host.querySelector<HTMLButtonElement>('.switch-layer')!.click();
    await flush();
    expect(api.getTableModelDiagram).toHaveBeenCalledTimes(1);
    expect(host.textContent).toContain('订单明细');
  });
});
