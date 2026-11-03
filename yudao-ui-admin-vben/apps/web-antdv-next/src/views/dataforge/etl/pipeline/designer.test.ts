import type { App, PropType } from 'vue';

import type { DataforgeDataApiApi } from '#/api/dataforge/dataapi';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/etl';

import Designer from './designer.vue';

const harness = vi.hoisted(() => ({
  access: { accessToken: '1', refreshToken: undefined as string | undefined },
  success: vi.fn(),
  warning: vi.fn(),
  error: vi.fn(),
  nodes: [] as any[],
  edges: [] as any[],
  connecting: undefined as undefined | {
    createEdge: (this: { createEdge: (options: Record<string, any>) => unknown }) => unknown;
    validateConnection: (options: Record<string, any>) => boolean;
  },
  keys: new Map<string, () => void>(),
  graphEvents: new Map<string, (event: any) => void>(),
  confirm: vi.fn(),
  versionOpen: vi.fn(),
  versionClose: vi.fn(),
  runParameters: vi.fn(),
  previewGraph: undefined as api.DataforgeEtlApi.GraphRequest | undefined,
}));
vi.mock('vue-router', () => ({ onBeforeRouteLeave: vi.fn() }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/stores', () => ({
  useAccessStore: () => harness.access,
  useUserStore: () => ({ userInfo: { userId: 1 } }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: defineComponent({ render: () => null }),
}));
vi.mock('@antv/x6', () => ({
  Graph: class {
    constructor(options: { connecting: NonNullable<typeof harness.connecting> }) {
      harness.connecting = options.connecting;
    }
    addEdge(edge: any) {
      harness.edges.push({
        id: edge.id,
        getData: () => edge.data,
        getSourceCellId: () => edge.source.cell,
        getTargetCellId: () => edge.target.cell,
        getSourcePortId: () => edge.source.port,
        getTargetPortId: () => edge.target.port,
        getLabels: () => edge.labels ?? [],
        setLabels(labels: unknown[]) {
          edge.labels = labels;
        },
      });
    }
    addNode(node: any) {
      harness.nodes.push({
        id: node.id,
        getData: () => node.data,
        // 与 X6 一致：默认合并，overwrite 时整体替换
        setData(
          data: Record<string, unknown>,
          options?: { overwrite?: boolean },
        ) {
          node.data = options?.overwrite ? data : { ...node.data, ...data };
        },
        position: () => ({ x: 0, y: 0 }),
        isNode: () => true,
      });
    }
    batchUpdate(_name: string, callback: () => void) {
      callback();
    }
    bindKey(keys: string[], callback: () => void) {
      for (const key of keys) harness.keys.set(key, callback);
    }
    centerContent() {}
    clearCells() {
      harness.nodes = [];
      harness.edges = [];
    }
    dispose() {}
    getCellById(id: string) {
      return harness.nodes.find((node) => node.id === id);
    }
    getEdges() {
      return harness.edges;
    }
    getNodes() {
      return harness.nodes;
    }
    on(name: string, callback: (event: any) => void) {
      harness.graphEvents.set(name, callback);
    }
    use() {}
    zoomTo() {}
    zoomToFit() {}
  },
}));
vi.mock('@antv/x6-plugin-dnd', () => ({
  Dnd: vi.fn(),
}));
vi.mock('@antv/x6-plugin-keyboard', () => ({
  Keyboard: vi.fn(),
}));
vi.mock('@antv/x6-plugin-selection', () => ({
  Selection: vi.fn(),
}));
vi.mock('@antv/x6-plugin-snapline', () => ({
  Snapline: vi.fn(),
}));
vi.mock('./x6-config', () => ({
  registerEtlShapes: vi.fn(),
  setupEtlInteraction: vi.fn(),
  getCategoryStyle: () => ({}),
  getNodePorts: () => ({}),
  runtimeEdgeLabel: (text: string, color: string) => ({
    position: 0.5,
    attrs: { label: { text, fill: color } },
  }),
  NODE_HEIGHT: 40,
  NODE_WIDTH: 80,
  NODE_PORTS: {},
}));
vi.mock('./node-editors/registry', async () => {
  const { default: RestApi } = await import('./node-editors/rest-api.vue');
  const { defaultRestApiNodeConfig } = await import('./node-editors/rest-api-config');
  return {
    getNodeEditor: (code: string) => code === 'rest-api' ? RestApi : undefined,
    getDefaultNodeConfig: (code: string, name: string) => code === 'rest-api' ? defaultRestApiNodeConfig(name) : {},
    isNodeSupported: () => true,
  };
});
vi.mock('#/views/dataforge/dataapi/modules/api-config-editor.vue', async () => {
  const { validateApiConfig } = await import('#/views/dataforge/dataapi/modules/api-config');
  return {
    default: defineComponent({
      props: {
        modelValue: { type: Object as PropType<DataforgeDataApiApi.DataApi>, required: true },
        disabled: Boolean,
      },
      emits: ['update:modelValue'],
      setup(props, { emit, expose }) {
        expose({ validate: () => !validateApiConfig(props.modelValue) });
        return () => h('button', {
          disabled: props.disabled,
          onClick: () => emit('update:modelValue', {
            ...props.modelValue,
            dataColumns: [{ fieldName: 'amount', fieldType: 8, displaySize: 20, precisionSize: 4, nullable: false }],
          }),
        }, '获取返回字段');
      },
    }),
  };
});
vi.mock('./history', () => ({
  createEtlHistory: () => ({
    on: vi.fn(),
    clean: vi.fn(),
    canUndo: () => false,
    canRedo: () => false,
  }),
  applyEtlHistory: vi.fn(),
}));
vi.mock('./preview-dialog.vue', () => ({
  default: defineComponent({
    props: ['graph', 'open'],
    setup(props) {
      return () => {
        if (props.open) harness.previewGraph = props.graph;
        return null;
      };
    },
  }),
}));
vi.mock('./template-library.vue', () => ({
  default: defineComponent({
    setup(_, { expose }) {
      expose({ close: vi.fn() });
      return () => null;
    },
  }),
}));
vi.mock('./run-parameters-dialog.vue', () => ({
  default: defineComponent({
    setup(_, { expose }) {
      expose({
        open: harness.runParameters,
        openPreview: async () => ({ parameters: {} }),
        close: vi.fn(),
      });
      return () => null;
    },
  }),
}));
vi.mock('./version-history.vue', () => ({
  default: defineComponent({
    setup(_, { expose }) {
      expose({ open: harness.versionOpen, close: harness.versionClose });
      return () => null;
    },
  }),
}));
vi.mock('#/components/cron-tab', () => ({
  CronTab: defineComponent({ render: () => null }),
}));
vi.mock('../../dev/modules/quality-task-field.vue', () => ({
  default: defineComponent({ render: () => null }),
}));
vi.mock('antdv-next', () => {
  const slot = (tag: string) =>
    defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h(tag, attrs, slots.default?.()),
    });
  return {
    Button: slot('button'),
    Alert: slot('div'),
    Select: defineComponent({
      props: {
        value: { type: [String, Number], default: undefined },
        options: { type: Array, default: () => [] },
        disabled: Boolean,
      },
      setup: (props) => () => h('select', { value: props.value, disabled: props.disabled }),
    }),
    Checkbox: slot('label'),
    Input: slot('input'),
    InputNumber: slot('input'),
    TextArea: slot('textarea'),
    Tooltip: slot('span'),
    RadioGroup: slot('div'),
    Tag: slot('span'),
    Drawer: defineComponent({
      props: ['open'],
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('section', slots.default?.()) : null,
    }),
    Popconfirm: defineComponent({
      setup:
        (_, { attrs, slots }) =>
        () =>
          h('span', { onClick: attrs.onConfirm }, slots.default?.()),
    }),
    Modal: Object.assign(defineComponent({ render: () => null }), {
      confirm: harness.confirm,
    }),
    message: {
      success: harness.success,
      warning: harness.warning,
      error: harness.error,
    },
  };
});
vi.mock('#/api/dataforge/etl', () => ({
  getEtlPipeline: vi.fn(),
  getEtlToolList: vi.fn(),
  getEtlRecordLog: vi.fn(),
  getEtlNodeRecords: vi.fn(),
  resolveEtlSchema: vi.fn(),
  runEtlPipeline: vi.fn(),
  runEtlCommand: vi.fn(),
  saveEtlPipeline: vi.fn(),
  stopEtlRecord: vi.fn(),
  stopEtlCommand: vi.fn(),
  validateEtlPipeline: vi.fn(),
}));
let app: App | undefined;
let host: HTMLDivElement;
let view: { open: (id: string) => Promise<void> };
async function flush() {
  for (let count = 0; count < 6; count++) {
    await Promise.resolve();
    await nextTick();
  }
}
async function mountDesigner() {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(Designer);
  app.directive('access', {});
  view = app.mount(host) as unknown as typeof view;
  await view.open('9007199254740993');
  await flush();
}
function button(label: string) {
  const result = [...host.querySelectorAll('button')].find(
    (item) => item.textContent?.trim() === label,
  );
  if (!result) throw new Error(`Missing button: ${label}`);
  return result;
}
beforeEach(() => {
  vi.clearAllMocks();
  vi.useFakeTimers();
  vi.spyOn(HTMLElement.prototype, 'clientWidth', 'get').mockReturnValue(800);
  vi.spyOn(HTMLElement.prototype, 'clientHeight', 'get').mockReturnValue(600);
  harness.access = reactive({ accessToken: '1', refreshToken: undefined });
  harness.nodes = [];
  harness.edges = [];
  harness.connecting = undefined;
  harness.previewGraph = undefined;
  harness.keys.clear();
  harness.graphEvents.clear();
  harness.runParameters.mockResolvedValue({
    expectedDefinitionRevision: 1,
    parameters: {},
  });
  vi.mocked(api.validateEtlPipeline).mockResolvedValue({
    valid: true,
    issues: [],
  });
  vi.mocked(api.getEtlPipeline).mockResolvedValue({
    id: '9007199254740993',
    name: 'Published flow',
    publishedRevision: 1,
    nodes: [],
    edges: [],
  });
  vi.mocked(api.getEtlToolList).mockResolvedValue([]);
  vi.mocked(api.getEtlNodeRecords).mockResolvedValue([]);
  vi.mocked(api.runEtlPipeline).mockResolvedValue('9007199254740999');
  vi.mocked(api.runEtlCommand).mockResolvedValue({
    recordId: '9007199254740999',
    runStatus: 1,
    stopRequested: false,
    replayed: false,
  });
  vi.mocked(api.getEtlRecordLog).mockResolvedValue({
    id: '9007199254740999',
    runStatus: 3,
    qualityStatus: 'PENDING',
    occupiesSlot: true,
  });
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host?.remove();
  vi.restoreAllMocks();
  vi.useRealTimers();
});

describe('etl 设计器统一校验', () => {
  it('api 节点将内嵌请求和获取后的字段同时用于 schema 推导、预览与保存', async () => {
    const apiConfig: DataforgeDataApiApi.DataApi = {
      requestUrl: 'https://example.test/records',
      requestMethod: 'POST',
      bodyType: 'raw',
      bodyRaw: '{"active":true}',
      requestHeader: [{ name: 'Accept', value: 'application/json' }],
      requestParam: [{ name: 'scope', value: 'current' }],
      ifPage: 1,
      pageKey: 'page',
      pageSizeKey: 'size',
      dataKey: 'data.rows',
      dataColumns: [],
    };
    vi.mocked(api.getEtlPipeline).mockResolvedValue({
      id: '9007199254740993', name: '接口接入流程',
      nodes: [{ id: 'api', properties: { name: '独立接口', toolName: 'API接口', toolCode: 'rest-api', toolType: 1, nodeJson: { apiConfig, fetchSize: 200 } } }],
      edges: [],
    });
    vi.mocked(api.resolveEtlSchema).mockResolvedValue({ nodes: { api: { outputFields: [{ name: 'amount', dataType: 'Decimal', nullable: false, precision: 20, scale: 4 }] } } });
    vi.mocked(api.saveEtlPipeline).mockResolvedValue('9007199254740993');
    await mountDesigner();
    harness.graphEvents.get('node:dblclick')!({ node: { id: 'api' } });
    await flush();
    button('获取返回字段').click();
    await flush();
    button('确定').click();
    await flush();
    await vi.advanceTimersByTimeAsync(300);
    await flush();
    const expectedConfig = {
      fetchSize: 200,
      apiConfig: expect.objectContaining({
        ...apiConfig,
        apiName: '独立接口',
        dataColumns: [{ fieldName: 'amount', fieldType: 8, displaySize: 20, precisionSize: 4, nullable: false }],
      }),
    };
    expect(api.resolveEtlSchema).toHaveBeenLastCalledWith(expect.objectContaining({ nodes: [expect.objectContaining({ properties: expect.objectContaining({ nodeJson: expectedConfig }) })] }));
    button('样本预览').click();
    await flush();
    expect(harness.previewGraph?.nodes[0]?.properties.nodeJson).toEqual(expectedConfig);
    button('保存').click();
    await flush();
    expect(api.saveEtlPipeline).toHaveBeenCalledWith(expect.objectContaining({ nodes: [expect.objectContaining({ properties: expect.objectContaining({ nodeJson: expectedConfig }) })] }));
    expect(apiConfig.dataColumns).toEqual([]);
  });

  it('api 节点请求或字段未配置完整时不能提交节点', async () => {
    vi.mocked(api.getEtlPipeline).mockResolvedValue({
      id: '9007199254740993', name: '未配置接口',
      nodes: [{ id: 'api', properties: { name: '新接口', toolCode: 'rest-api', nodeJson: {} } }],
      edges: [],
    });
    await mountDesigner();
    harness.graphEvents.get('node:dblclick')!({ node: { id: 'api' } });
    await flush();
    button('确定').click();
    await flush();
    expect(harness.nodes[0].getData().nodeJson).toEqual({});
    expect(button('获取返回字段')).toBeDefined();
  });

  it.each(['java-script', 'js-script'])('%s 行脚本以原DATA语义加载校验保存，显式CONTROL不被改写', async (toolCode) => {
    vi.mocked(api.getEtlPipeline).mockResolvedValue({
      id: '9007199254740993', name: '行脚本连接回归', revision: 1,
      nodes: [
        { id: 'source', properties: { name: '生成来源', toolCode: 'generate-records', toolType: 1 } },
        { id: 'script', properties: { name: '行脚本', toolCode, toolType: 4 } },
        { id: 'sink', properties: { name: '输出', toolCode: 'insert-db', toolType: 3 } },
        { id: 'control', properties: { name: '前置SQL', toolCode: 'sql-script', toolType: 4 } },
      ],
      edges: [
        { id: 'input', sourceNodeId: 'source', targetNodeId: 'script', sourcePort: 'main', targetPort: 'in', properties: { edgeType: 'DATA' } },
        { id: 'output', sourceNodeId: 'script', targetNodeId: 'sink' },
        { id: 'dependency', sourceNodeId: 'control', targetNodeId: 'script', properties: { edgeType: 'CONTROL' } },
      ],
    });
    vi.mocked(api.saveEtlPipeline).mockResolvedValue('9007199254740993');
    await mountDesigner();
    const connecting = harness.connecting!;
    const createEdge = vi.fn((options: Record<string, any>) => options);
    expect(connecting.createEdge.call({ createEdge })).toEqual({ shape: 'etl-edge', data: {} });
    const magnet = (group: string) => ({ getAttribute: () => group });
    for (const [source, target, edgeId] of [['source', 'script', 'input'], ['script', 'sink', 'output']]) {
      expect(connecting.validateConnection({ sourceMagnet: magnet('out'), targetMagnet: magnet('in'), sourceCell: { id: source }, targetCell: { id: target }, edge: { id: edgeId } })).toBe(true);
    }
    expect(connecting.validateConnection({ sourceMagnet: magnet('out'), targetMagnet: magnet('in'), sourceCell: { id: 'sink' }, targetCell: { id: 'source' } })).toBe(false);
    button('校验').click();
    await flush();
    button('保存').click();
    await flush();
    const saved = vi.mocked(api.saveEtlPipeline).mock.calls[0]![0];
    expect(saved.nodes?.find((node) => node.id === 'script')?.properties).toMatchObject({ toolCode, toolType: 4 });
    expect(saved.edges).toEqual([
      expect.objectContaining({ sourceNodeId: 'source', targetNodeId: 'script', sourcePort: 'main', targetPort: 'in', properties: { edgeType: 'DATA', outputPort: 'main' } }),
      expect.objectContaining({ sourceNodeId: 'script', targetNodeId: 'sink', sourcePort: 'main', targetPort: 'in', properties: { outputPort: 'main' } }),
      expect.objectContaining({ sourceNodeId: 'control', targetNodeId: 'script', properties: { edgeType: 'CONTROL', outputPort: 'main' } }),
    ]);
    expect(api.validateEtlPipeline).toHaveBeenCalledWith(expect.objectContaining({ nodes: saved.nodes, edges: saved.edges }));
  });
  it('把无损环境编号加入打开预览时的画布快照', async () => {
    vi.mocked(api.getEtlPipeline).mockResolvedValue({
      id: '9007199254740993',
      name: '环境流程',
      environmentId: '9007199254740995',
      nodes: [
        {
          id: 'source',
          properties: {
            name: '源',
            toolCode: 'read-db',
            nodeJson: { ifSql: 0 },
          },
        },
      ],
      edges: [],
    });
    await mountDesigner();
    button('样本预览').click();
    await flush();
    expect(harness.previewGraph).toMatchObject({
      environmentId: '9007199254740995',
    });
  });
  it('有未保存修改时先确认并保存，再打开版本历史', async () => {
    vi.mocked(api.getEtlPipeline).mockResolvedValue({
      id: '9007199254740993',
      name: '记录流程',
      revision: 3,
      nodes: [
        {
          id: 'source',
          properties: { name: '源', toolCode: 'generate-records' },
        },
      ],
      edges: [],
    });
    vi.mocked(api.saveEtlPipeline).mockResolvedValue('9007199254740993');
    await mountDesigner();
    harness.nodes[0].getData().name = '未保存的名字';
    button('版本历史').click();
    await flush();
    expect(harness.versionOpen).not.toHaveBeenCalled();
    expect(harness.confirm).toHaveBeenCalledWith(
      expect.objectContaining({ title: '先保存当前修改' }),
    );
    await harness.confirm.mock.calls[0]![0].onOk();
    await flush();
    expect(api.saveEtlPipeline).toHaveBeenCalledOnce();
    expect(harness.versionOpen).toHaveBeenCalledWith('9007199254740993', 3);
  });

  it('ctrlS保存当前流程，运行期间拒绝快捷键保存', async () => {
    vi.mocked(api.getEtlPipeline).mockResolvedValue({
      id: '9007199254740993',
      name: '记录流程',
      publishedRevision: 1,
      nodes: [
        {
          id: 'source',
          properties: { name: '源', toolCode: 'generate-records' },
        },
      ],
      edges: [],
    });
    vi.mocked(api.saveEtlPipeline).mockResolvedValue('9007199254740993');
    await mountDesigner();
    harness.keys.get('ctrl+s')?.();
    await flush();
    expect(api.saveEtlPipeline).toHaveBeenCalledTimes(1);
    button('运行发布版').click();
    await flush();
    harness.keys.get('ctrl+s')?.();
    await flush();
    expect(api.saveEtlPipeline).toHaveBeenCalledTimes(1);
    expect(button('撤销').disabled).toBe(true);
    expect(button('重做').disabled).toBe(true);
  });

  it('完整校验未通过仍可保存未完成草稿，保留具体字段问题', async () => {
    vi.mocked(api.getEtlPipeline).mockResolvedValue({
      id: '9007199254740993',
      name: '质量流程',
      nodes: [
        {
          id: 'quality',
          properties: { name: '质量检查', toolCode: 'quality-check' },
        },
      ],
      edges: [],
    });
    vi.mocked(api.validateEtlPipeline).mockResolvedValue({
      valid: false,
      issues: [
        {
          nodeId: 'quality',
          field: 'reject',
          severity: 'ERROR',
          code: 'REJECT_UNCONNECTED',
          message: '不合格输出必须连接隔离节点',
        },
      ],
    });
    vi.mocked(api.saveEtlPipeline).mockResolvedValue('9007199254740993');
    await mountDesigner();
    button('校验').click();
    await flush();
    expect(host.textContent).toContain('不合格输出必须连接隔离节点');
    expect(host.textContent).toContain('定位节点');
    button('保存').click();
    await flush();
    expect(api.saveEtlPipeline).toHaveBeenCalledWith(
      expect.objectContaining({
        nodes: [expect.objectContaining({ id: 'quality' })],
      }),
    );
    expect(api.validateEtlPipeline).toHaveBeenCalledTimes(1);
    expect(harness.success).toHaveBeenCalledWith(
      '草稿已保存，发布和运行前仍需通过校验',
    );
  });

  it('允许先保存有名称的空画布草稿', async () => {
    vi.mocked(api.saveEtlPipeline).mockResolvedValue('9007199254740993');
    await mountDesigner();
    button('保存').click();
    await flush();
    expect(api.saveEtlPipeline).toHaveBeenCalledWith(
      expect.objectContaining({ nodes: [], edges: [] }),
    );
    expect(api.validateEtlPipeline).not.toHaveBeenCalled();
  });

  it('校验网络失败不会显示通过，允许随后重试', async () => {
    vi.mocked(api.validateEtlPipeline).mockRejectedValueOnce(
      new Error('unavailable'),
    );
    await mountDesigner();
    button('校验').click();
    await flush();
    expect(host.textContent).toContain('尚未验证通过');
    expect(host.textContent).not.toContain('流程校验通过');
    button('校验').click();
    await flush();
    expect(host.textContent).toContain('流程校验通过');
  });

  it('旧窗口校验响应不得覆盖重新打开后的画布', async () => {
    let resolve!: (value: { issues: []; valid: boolean }) => void;
    vi.mocked(api.validateEtlPipeline).mockImplementationOnce(
      () =>
        new Promise((done) => {
          resolve = done;
        }),
    );
    await mountDesigner();
    button('校验').click();
    await flush();
    await view.open('9007199254740995');
    resolve({ valid: true, issues: [] });
    await flush();
    expect(host.textContent).not.toContain('流程校验通过');
  });
});

describe('etl 设计器运行时节点行数', () => {
  it('运行前清除旧运行态，轮询写入读写行数但不进入快照、预览，也不产生未保存修改', async () => {
    vi.mocked(api.getEtlPipeline).mockResolvedValue({
      id: '9007199254740993',
      name: '行数流程',
      publishedRevision: 1,
      revision: 2,
      nodes: [
        {
          id: 'source',
          properties: { name: '源', toolCode: 'generate-records' },
        },
      ],
      edges: [],
    });
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id: '9007199254740999',
      runStatus: 2,
      occupiesSlot: true,
    });
    vi.mocked(api.getEtlNodeRecords).mockResolvedValueOnce([]);
    await mountDesigner();
    // 模拟同一会话内上一次运行遗留的运行态
    harness.nodes[0].setData({ runStatus: 4, readCount: 1, writeCount: 1 });
    button('运行发布版').click();
    await flush();
    expect(harness.nodes[0].getData()).toEqual({
      name: '源',
      toolCode: 'generate-records',
    });
    vi.mocked(api.getEtlNodeRecords).mockResolvedValue([
      {
        id: '1',
        nodeNo: 'source',
        designNodeNo: 'source',
        runStatus: 3,
        startTime: 1000,
        endTime: 3000,
        readCount: 120,
        writeCount: '9007199254740993' as unknown as number,
      },
    ]);
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id: '9007199254740999',
      runStatus: 3,
    });
    await vi.advanceTimersByTimeAsync(2000);
    await flush();
    expect(harness.nodes[0].getData()).toMatchObject({
      name: '源',
      runStatus: 3,
      readCount: 120,
      writeCount: '9007199254740993',
    });
    expect(button('运行发布版').disabled).toBe(false);
    button('样本预览').click();
    await flush();
    expect(harness.previewGraph?.nodes[0]?.properties).toEqual({
      name: '源',
      toolCode: 'generate-records',
    });
    button('版本历史').click();
    await flush();
    expect(harness.confirm).not.toHaveBeenCalled();
    expect(harness.versionOpen).toHaveBeenCalledWith('9007199254740993', 2);
  });

  it('运行观测显示运行中实时计数、连线队列与瓶颈，标签不进入画布数据且下一次运行前清除', async () => {
    vi.mocked(api.getEtlPipeline).mockResolvedValue({
      id: '9007199254740993',
      name: '观测流程',
      publishedRevision: 1,
      revision: 2,
      nodes: [
        {
          id: 'source',
          properties: { name: '源', toolCode: 'generate-records' },
        },
        { id: 'sink', properties: { name: '写入', toolCode: 'insert-db' } },
      ],
      edges: [
        {
          id: 'source-sink',
          sourceNodeId: 'source',
          targetNodeId: 'sink',
          sourcePort: 'main',
          targetPort: 'in',
          properties: {},
        },
      ],
    });
    const runtimeMetrics: api.DataforgeEtlApi.RuntimeMetrics = {
      elapsedMillis: 10_000,
      finished: false,
      transferredRows: 800,
      transferredBytes: 0,
      bufferedBytes: 0,
      nodes: [
        { nodeNo: 'source', readCount: 0, writeCount: 800 },
        { nodeNo: 'sink', readCount: 300, writeCount: 0 },
      ],
      edges: [
        {
          sourceNo: 'source',
          targetNo: 'sink',
          queued: 950,
          capacity: 1000,
          delivered: 800,
          blockedMillis: 6000,
          starvedMillis: 0,
        },
      ],
      series: [],
    };
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id: '9007199254740999',
      runStatus: 2,
      occupiesSlot: true,
      runtimeMetrics,
    });
    vi.mocked(api.getEtlNodeRecords).mockResolvedValue([
      { id: '1', nodeNo: 'source', nodeName: '源', runStatus: 2, startTime: 1000 },
      { id: '2', nodeNo: 'sink', nodeName: '写入', runStatus: 2, startTime: 1000 },
    ]);
    await mountDesigner();
    button('运行发布版').click();
    await flush();
    expect(harness.nodes[0].getData()).toMatchObject({
      runStatus: 2,
      readCount: 0,
      writeCount: 800,
    });
    expect(harness.nodes[1].getData()).toMatchObject({
      runStatus: 2,
      readCount: 300,
      writeCount: 0,
    });
    expect(harness.edges[0].getLabels()[0]?.attrs?.label).toMatchObject({
      text: '队列 950/1000 · 等待 6.0 s',
      fill: '#dc2626',
    });
    expect(host.textContent).toContain('瓶颈：下游【写入】处理较慢');

    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id: '9007199254740999',
      runStatus: 3,
      runtimeMetrics: { ...runtimeMetrics, finished: true, edges: [] },
    });
    await vi.advanceTimersByTimeAsync(2000);
    await flush();
    expect(button('运行发布版').disabled).toBe(false);
    expect(harness.edges[0].getLabels()).toEqual([]);
    expect(host.textContent).not.toContain('瓶颈');
    button('样本预览').click();
    await flush();
    expect(JSON.stringify(harness.previewGraph)).not.toContain('队列');
    expect(harness.previewGraph?.edges[0]).not.toHaveProperty('labels');

    harness.edges[0].setLabels([{ attrs: { label: { text: '上一次运行' } } }]);
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id: '9007199254741000',
      runStatus: 2,
      occupiesSlot: true,
    });
    vi.mocked(api.getEtlNodeRecords).mockResolvedValue([]);
    button('运行发布版').click();
    await flush();
    expect(harness.edges[0].getLabels()).toEqual([]);
  });
});

describe('etl designer delayed stop receipts', () => {
  it('keeps the newer confirmed terminal state when an older stop receipt arrives', async () => {
    const id = '9007199254740999';
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id,
      runStatus: 2,
      occupiesSlot: true,
    });
    let acceptStop!: (
      value: Awaited<ReturnType<typeof api.stopEtlCommand>>,
    ) => void;
    vi.mocked(api.stopEtlCommand).mockReturnValueOnce(
      new Promise((resolve) => {
        acceptStop = resolve;
      }),
    );
    await mountDesigner();
    button('运行发布版').click();
    await flush();
    button('停止').click();
    await flush();
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id,
      runStatus: 5,
      occupiesSlot: false,
      stopRequested: true,
    });
    await vi.advanceTimersByTimeAsync(2000);
    await flush();
    expect(host.textContent).toContain('已确认停止');
    acceptStop({
      recordId: id,
      runStatus: 2,
      stopRequested: true,
      replayed: false,
    });
    await flush();
    await vi.advanceTimersByTimeAsync(10_000);
    await flush();
    expect(api.getEtlRecordLog).toHaveBeenCalledTimes(2);
    expect(host.textContent).toContain('已确认停止');
  });

  it('does not mark the next run cancelled when a previous stop receipt arrives', async () => {
    const firstId = '9007199254740999';
    const secondId = '9007199254741001';
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id: firstId,
      runStatus: 2,
      occupiesSlot: true,
    });
    let acceptStop!: (
      value: Awaited<ReturnType<typeof api.stopEtlCommand>>,
    ) => void;
    vi.mocked(api.stopEtlCommand).mockReturnValueOnce(
      new Promise((resolve) => {
        acceptStop = resolve;
      }),
    );
    await mountDesigner();
    button('运行发布版').click();
    await flush();
    button('停止').click();
    await flush();
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id: firstId,
      runStatus: 5,
      occupiesSlot: false,
      stopRequested: true,
    });
    await vi.advanceTimersByTimeAsync(2000);
    await flush();
    expect(button('运行发布版').disabled).toBe(false);
    vi.mocked(api.runEtlCommand).mockResolvedValueOnce({
      recordId: secondId,
      runStatus: 2,
      stopRequested: false,
      replayed: false,
    });
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id: secondId,
      runStatus: 2,
      occupiesSlot: true,
      stopRequested: false,
    });
    button('运行发布版').click();
    await flush();
    expect(host.textContent).toContain('执行中');
    acceptStop({
      recordId: firstId,
      runStatus: 2,
      stopRequested: true,
      replayed: false,
    });
    await flush();
    await vi.advanceTimersByTimeAsync(2000);
    await flush();
    expect(api.getEtlRecordLog).toHaveBeenLastCalledWith(secondId);
    expect(host.textContent).not.toContain('取消已受理 / 待确认');
    expect(button('停止')).toBeDefined();
    vi.mocked(api.stopEtlCommand).mockResolvedValueOnce({
      recordId: secondId,
      runStatus: 2,
      stopRequested: true,
      replayed: false,
    });
    button('停止').click();
    await flush();
    expect(api.stopEtlCommand).toHaveBeenLastCalledWith({
      recordId: secondId,
      requestId: expect.any(String),
    });
  });

  it('preserves a newer unknown state instead of applying the acceptance status snapshot', async () => {
    const id = '9007199254740999';
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id,
      runStatus: 2,
      occupiesSlot: true,
    });
    let acceptStop!: (
      value: Awaited<ReturnType<typeof api.stopEtlCommand>>,
    ) => void;
    vi.mocked(api.stopEtlCommand).mockReturnValueOnce(
      new Promise((resolve) => {
        acceptStop = resolve;
      }),
    );
    await mountDesigner();
    button('运行发布版').click();
    await flush();
    button('停止').click();
    await flush();
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id,
      runStatus: 6,
      occupiesSlot: true,
    });
    await vi.advanceTimersByTimeAsync(2000);
    await flush();
    expect(host.textContent).toContain('结果待核查');
    acceptStop({
      recordId: id,
      runStatus: 2,
      stopRequested: true,
      replayed: false,
    });
    await flush();
    expect(host.textContent).toContain('结果待核查');
  });
});

describe('etl designer execution lifecycle', () => {
  it('does not replace current session tools with an earlier open response', async () => {
    await mountDesigner();
    let finish!: (
      value: Awaited<ReturnType<typeof api.getEtlToolList>>,
    ) => void;
    vi.mocked(api.getEtlToolList).mockReturnValueOnce(
      new Promise((resolve) => {
        finish = resolve;
      }),
    );
    const pending = view.open('9007199254740993');
    harness.access.refreshToken = '2';
    await flush();
    vi.mocked(api.getEtlToolList).mockResolvedValueOnce([
      { id: '1', type: -1, name: 'Current tools' },
      { id: '2', parentId: '1', type: 1, name: 'Current connector' },
    ]);
    await view.open('9007199254740995');
    finish([
      { id: '1', type: -1, name: 'Previous tools' },
      { id: '2', parentId: '1', type: 1, name: 'Previous connector' },
    ]);
    await pending;
    await flush();
    expect(host.textContent).toContain('Current connector');
    expect(host.textContent).not.toContain('Previous connector');
  });

  it('does not let an earlier run failure unlock a reopened active execution', async () => {
    await mountDesigner();
    let reject!: (error: Error) => void;
    vi.mocked(api.runEtlCommand).mockReturnValueOnce(
      new Promise((_, fail) => {
        reject = fail;
      }),
    );
    button('运行发布版').click();
    await flush();
    harness.access.refreshToken = '2';
    await flush();
    await view.open('9007199254740995');
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id: '9007199254740999',
      runStatus: 2,
      occupiesSlot: true,
    });
    button('运行发布版').click();
    await flush();
    reject(new Error('previous request failed'));
    await flush();
    expect(button('运行发布版').disabled).toBe(true);
    expect(host.textContent).toContain('执行中');
  });

  it('uses a durable run receipt and keeps polling success awaiting quality', async () => {
    await mountDesigner();
    button('运行发布版').click();
    await flush();
    expect(api.runEtlCommand).toHaveBeenCalledWith({
      taskId: '9007199254740993',
      expectedDefinitionRevision: 1,
      parameters: {},
      requestId: expect.any(String),
    });
    expect(button('运行发布版').disabled).toBe(true);
    expect(host.textContent).toContain('质量检查中');
    await vi.advanceTimersByTimeAsync(2000);
    await flush();
    expect(api.getEtlRecordLog).toHaveBeenCalledTimes(2);
    expect(harness.success).not.toHaveBeenCalledWith(
      expect.stringContaining('执行成功'),
    );
  });

  it('does not release a held partial run and cancels polling on unmount', async () => {
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id: '9007199254740999',
      runStatus: 4,
      occupiesSlot: true,
      reviewRequired: true,
      rowVersion: '3',
    });
    await mountDesigner();
    button('运行发布版').click();
    await flush();
    expect(button('运行发布版').disabled).toBe(true);
    expect(host.textContent).toContain('结果待核查');
    await vi.advanceTimersByTimeAsync(5000);
    await flush();
    expect(api.getEtlRecordLog).toHaveBeenCalledTimes(2);
    app?.unmount();
    app = undefined;
    await vi.advanceTimersByTimeAsync(10_000);
    expect(api.getEtlRecordLog).toHaveBeenCalledTimes(2);
  });

  it('stops the precise record and waits for observed termination', async () => {
    vi.mocked(api.getEtlRecordLog).mockResolvedValue({
      id: '9007199254740999',
      runStatus: 2,
      occupiesSlot: true,
    });
    vi.mocked(api.stopEtlCommand).mockResolvedValue({
      recordId: '9007199254740999',
      runStatus: 2,
      stopRequested: true,
      replayed: false,
    });
    await mountDesigner();
    button('运行发布版').click();
    await flush();
    button('停止').click();
    await flush();
    expect(api.stopEtlCommand).toHaveBeenCalledWith({
      recordId: '9007199254740999',
      requestId: expect.any(String),
    });
    expect(button('运行发布版').disabled).toBe(true);
    expect(host.textContent).toContain('待确认');
  });
});
