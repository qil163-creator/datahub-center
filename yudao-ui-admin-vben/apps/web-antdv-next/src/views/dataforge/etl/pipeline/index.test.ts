import type { App, PropType } from 'vue';

import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/etl';

import PipelinePage from './index.vue';

interface Action {
  label: string;
  popConfirm?: { confirm: () => Promise<void> };
}
const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  row: { id: '9007199254740993', status: 1 } as DataforgeEtlApi.Pipeline,
  actions: [] as Action[],
  push: vi.fn(),
  success: vi.fn(),
  refresh: vi.fn(),
  runParameters: vi.fn(),
}));
vi.mock('vue-router', () => ({ useRouter: () => ({ push: harness.push }) }));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: 1 } }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('#/locales', () => ({ $t: () => '删除' }));
vi.mock('./designer.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
vi.mock('./detail.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
vi.mock('./info-form.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
vi.mock('./run-parameters-dialog.vue', () => ({
  default: defineComponent({
    setup(_, { expose }) {
      expose({ open: harness.runParameters, close: vi.fn() });
      return () => null;
    },
  }),
}));
vi.mock('../data', () => ({
  usePipelineGridColumns: () => [],
  usePipelineGridFormSchema: () => [],
  optionColor: () => '',
  optionLabel: () => '',
  RUN_STATUS_OPTIONS: [],
}));
vi.mock('@vben/common-ui', () => ({
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('main', slots.default?.()),
  }),
  useVbenModal: () => [defineComponent({ render: () => null }), {}],
}));
vi.mock('antdv-next', () => ({
  Alert: defineComponent({ render: () => null }),
  Button: defineComponent({ render: () => null }),
  Tag: defineComponent({ render: () => null }),
  Switch: defineComponent({ render: () => null }),
  Modal: { confirm: vi.fn() },
  message: { success: harness.success, warning: vi.fn() },
}));
vi.mock('#/adapter/vxe-table', () => ({
  ACTION_ICON: {},
  useVbenVxeGrid: () => [
    defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('div', slots.actions?.({ row: harness.row })),
    }),
    { query: harness.refresh },
  ],
  TableAction: defineComponent({
    props: {
      dropDownActions: { type: Array as PropType<Action[]>, default: () => [] },
    },
    setup: (props) => () => {
      harness.actions = props.dropDownActions;
      return h('div');
    },
  }),
}));
vi.mock('#/api/dataforge/etl', () => ({
  cancelEtlPipeline: vi.fn(),
  copyEtlPipeline: vi.fn(),
  deleteEtlPipeline: vi.fn(),
  getEtlPipelinePage: vi.fn(),
  releaseEtlPipeline: vi.fn(),
  runEtlPipeline: vi.fn(),
  updateEtlPipelineSchedule: vi.fn(),
  runEtlCommand: vi.fn(),
}));
let app: App;
let host: HTMLDivElement;
async function flush() {
  await Promise.resolve();
  await nextTick();
  await Promise.resolve();
  await nextTick();
}
async function mountPage() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(PipelinePage);
  app.mount(host);
  await flush();
}
function run() {
  const action = harness.actions.find((item) => item.label === '执行一次');
  if (!action?.popConfirm) throw new Error('Missing run action');
  return action.popConfirm.confirm();
}
beforeEach(() => {
  vi.clearAllMocks();
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.runParameters.mockResolvedValue({
    expectedDefinitionRevision: 1,
    parameters: { amount: '9007199254740993.123456789' },
  });
});
afterEach(() => {
  app?.unmount();
  host?.remove();
});

describe('etl pipeline run entry', () => {
  it('retries one command identity and navigates to the precise lossless record receipt', async () => {
    vi.mocked(api.runEtlCommand)
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValue({
        recordId: '9007199254740999',
        runStatus: 1,
        replayed: true,
        stopRequested: false,
      });
    await mountPage();
    await run();
    await run();
    const [first, retry] = vi.mocked(api.runEtlCommand).mock.calls;
    expect(first?.[0]).toEqual({
      taskId: '9007199254740993',
      expectedDefinitionRevision: 1,
      parameters: { amount: '9007199254740993.123456789' },
      requestId: expect.any(String),
    });
    expect(retry?.[0]).toEqual(first?.[0]);
    expect(harness.push).toHaveBeenCalledWith({
      path: '/develop/etl/record',
      query: { pipelineId: '9007199254740993', recordId: '9007199254740999' },
    });
    expect(api.runEtlPipeline).not.toHaveBeenCalled();
  });

  it('ignores an old session receipt and does not navigate or announce success', async () => {
    let finish!: (value: Awaited<ReturnType<typeof api.runEtlCommand>>) => void;
    vi.mocked(api.runEtlCommand).mockReturnValue(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    await mountPage();
    const pending = run();
    await flush();
    harness.access.refreshToken = '2';
    await flush();
    finish({
      recordId: '9',
      runStatus: 1,
      replayed: false,
      stopRequested: false,
    });
    await pending;
    expect(harness.push).not.toHaveBeenCalled();
    expect(harness.success).not.toHaveBeenCalled();
  });
});
