import type { App } from 'vue';

import type { IntegrationApi as Api } from '#/api/dataforge/integration';
import type { IntegrationComponentCapabilities } from '#/api/dataforge/integration/component-capabilities';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';
import { onBeforeRouteLeave } from 'vue-router';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as api from '#/api/dataforge/integration';
import * as capabilityApi from '#/api/dataforge/integration/component-capabilities';

import { integrationComponents } from './component-catalog';
import Designer from './designer.vue';

const harness = vi.hoisted(() => ({
  route: {} as { params: { id: string } },
  access: {} as { accessCodes: string[]; accessToken: string; },
  update: true,
  graphs: [] as any[],
  editorConfig: {} as Record<string, any>,
  editorValid: true,
  confirm: vi.fn(), success: vi.fn(), warning: vi.fn(), error: vi.fn(), drag: vi.fn(), push: vi.fn(),
}));
vi.mock('vue-router', () => ({
  useRoute: () => harness.route, useRouter: () => ({ push: harness.push }), onBeforeRouteLeave: vi.fn(),
}));
vi.mock('@vben/access', () => ({ useAccess: () => ({ hasAccessByCodes: (codes: string[]) => !codes.includes('dataforge:integration:update') || harness.update }) }));
vi.mock('@vben/stores', () => ({ useAccessStore: () => harness.access, useUserStore: () => ({ userInfo: { userId: '9007199254740999' } }) }));
vi.mock('@vben/common-ui', () => ({ confirm: harness.confirm, Page: defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) }) }));
vi.mock('@antv/x6', () => ({
  Graph: class {
    disposed = false; edges: any[] = []; events = new Map<string, (value: any) => void>(); nodes: any[] = [];
    constructor(public options: any) { harness.graphs.push(this); }
    addNode(raw: any) { const node = this.node(raw); this.nodes.push(node); this.events.get('cell:added')?.({}); return node; }
    bindKey() {}
    centerContent() {}
    clientToLocal(x: number, y: number) { return { x, y }; } createNode(raw: any) { return this.node(raw); }
    dispose() { this.disposed = true; }
    edge(raw: any) {
      return { id: raw.id, isNode: () => false, getData: () => raw.data,
        setData: (data: any) => { raw.data = data; this.events.get('cell:change:*')?.({}); },
        getSourceCellId: () => raw.source.cell, getTargetCellId: () => raw.target.cell,
        getSourcePortId: () => raw.source.port, getTargetPortId: () => raw.target.port,
        remove: () => { this.edges = this.edges.filter((edge) => edge.id !== raw.id); },
      };
    }
    fromJSON(data: any) { this.nodes = data.nodes.map((item: any) => this.node(item)); this.edges = data.edges.map((item: any) => this.edge(item)); }
    getCellById(id: string) { return this.nodes.find((node) => node.id === id); }
    getConnectedEdges(node: any) { return this.edges.filter((edge) => edge.getSourceCellId() === node.id || edge.getTargetCellId() === node.id); }
    getEdges() { return this.edges; }
    getIncomingEdges(id: string) { return this.edges.filter((edge) => edge.getTargetCellId() === id); }
    getNodes() { return this.nodes; }
    getOutgoingEdges(node: any) { return this.edges.filter((edge) => edge.getSourceCellId() === (node.id ?? node)); } node(raw: any) {
      return { id: raw.id, isNode: () => true, getData: () => raw.data,
        setData: (data: any) => { raw.data = data; this.events.get('cell:change:*')?.({}); },
        position: () => ({ x: raw.x, y: raw.y }), attr: vi.fn(), prop: vi.fn(),
        getPort: (id: string) => raw.ports?.items.find((item: any) => item.id === id),
        remove: () => { this.nodes = this.nodes.filter((node) => node.id !== raw.id); },
      };
    } on(name: string, handler: (value: any) => void) { this.events.set(name, handler); } use() {} zoomTo() {}
  },
}));
vi.mock('@antv/x6-plugin-dnd', () => ({ Dnd: class { start = harness.drag; dispose() {} } }));
vi.mock('@antv/x6-plugin-export', () => ({ Export: vi.fn() }));
vi.mock('@antv/x6-plugin-keyboard', () => ({ Keyboard: vi.fn() }));
vi.mock('@antv/x6-plugin-selection', () => ({ Selection: vi.fn() }));
vi.mock('@antv/x6-plugin-snapline', () => ({ Snapline: vi.fn() }));
vi.mock('../etl/pipeline/x6-config', () => ({ getNodePorts: (_code: string, config: any) => ({ items: [...(config.inputs ?? []).map((item: any) => ({ id: item.name, group: 'in' })), ...(config.outputs ?? []).map((item: any) => ({ id: item.name, group: 'out' }))] }) }));
vi.mock('../etl/pipeline/node-editors/registry', () => ({
  getDefaultNodeConfig: () => ({}),
  getNodeEditor: () => defineComponent({
    props: { config: { type: Object, required: true } },
    setup(props, { expose }) { harness.editorConfig = props.config; expose({ validate: () => harness.editorValid }); return () => null; },
  }),
}));
vi.mock('./modules/database-node-editor.vue', () => ({ default: defineComponent({
  props: { config: { type: Object, required: true } },
  setup(props) { harness.editorConfig = props.config; return () => null; },
}) }));
vi.mock('./modules/connector-node-editor.vue', () => ({ default: defineComponent({ render: () => null }) }));
vi.mock('./modules/transform-node-editor.vue', () => ({ default: defineComponent({ render: () => null }) }));
vi.mock('./modules/native-script-editor.vue', () => ({
  default: defineComponent({
    props: { config: { type: Object, required: true } }, emits: ['requestCheck'],
    setup(props, { expose, emit }) {
      harness.editorConfig = props.config;
      expose({ validate: () => harness.editorValid });
      return () => h('button', { onClick: () => emit('requestCheck') }, '编译');
    },
  }),
}));
vi.mock('./modules/task-form.vue', () => ({ default: defineComponent({ render: () => null }) }));
vi.mock('./modules/reference-flow-editor.vue', () => ({
  default: defineComponent({
    props: { config: { type: Object, required: true } },
    setup(props, { expose }) {
      harness.editorConfig = props.config;
      expose({ validate: () => harness.editorValid });
      return () => null;
    },
  }),
}));
vi.mock('antdv-next', () => {
  const Container = defineComponent({ setup: (_, { slots }) => () => h('div', slots.default?.()) });
  const Field = defineComponent({ props: { value: { type: String, default: '' }, disabled: Boolean }, emits: ['update:value'], setup: (props, { emit }) => () => h('input', { value: props.value, disabled: props.disabled, onInput: (event: Event) => emit('update:value', (event.target as HTMLInputElement).value) }) });
  const Modal = Object.assign(defineComponent({ props: { open: Boolean }, setup: (props, { slots }) => () => props.open ? h('section', { 'data-modal': true }, [slots.default?.(), slots.footer?.()]) : null }), { confirm: ({ onOk }: { onOk: () => void }) => onOk() });
  return { theme: { defaultAlgorithm: {} }, Alert: Container, ConfigProvider: Container, Spin: Container, Tooltip: Container, Input: Field, TextArea: Field, Modal,
    Button: defineComponent({ props: { disabled: Boolean, loading: Boolean }, setup: (props, { slots }) => () => h('button', { disabled: props.disabled || props.loading }, slots.default?.()) }),
    message: { success: harness.success, warning: harness.warning, error: harness.error },
  };
});
vi.mock('#/api/dataforge/integration', () => ({ getIntegration: vi.fn(), resolveIntegrationSchema: vi.fn(), saveIntegrationFlow: vi.fn(), checkIntegrationFlow: vi.fn(), getIntegrationCategories: vi.fn(async () => []), getIntegrationOwners: vi.fn(async () => []) }));
vi.mock('#/api/dataforge/integration/component-capabilities', () => ({ getIntegrationComponentCapabilities: vi.fn() }));

let app: App | undefined;
let host: HTMLDivElement;
async function flush() { for (let count = 0; count < 8; count++) { await Promise.resolve(); await nextTick(); } }
async function mount() { host = document.createElement('div'); document.body.append(host); app = createApp(Designer); app.mount(host); await flush(); }
function button(label: string) { const found = [...host.querySelectorAll('button')].find((item) => item.textContent?.trim() === label); if (!found) throw new Error(`按钮不存在：${label}`); return found; }
const node = (id: string, code: string, config: Record<string, unknown> = {}) => ({ id, type: 'integration-node', x: 100, y: 100, properties: { componentCode: code, toolCode: code, name: id, nodeJson: config } });
function task(id = '9007199254740993', name = '当前任务', flow?: Record<string, unknown>) {
  return { id, name, engine: 'SPARK', mode: 'OFFLINE', revision: 3, status: 0, flowJson: JSON.stringify(flow ?? { nodes: [], edges: [] }) } as Api.Task;
}
function deferred<T>() { let resolve!: (value: T) => void; const promise = new Promise<T>((done) => { resolve = done; }); return { promise, resolve }; }
beforeEach(() => {
  vi.clearAllMocks(); harness.confirm.mockResolvedValue(undefined); harness.graphs = []; harness.update = true; harness.editorValid = true;
  harness.route = reactive({ params: { id: '9007199254740993' } });
  harness.access = reactive({ accessToken: 'session', accessCodes: ['query', 'update'] });
  vi.mocked(api.getIntegration).mockResolvedValue(task());
  vi.mocked(api.saveIntegrationFlow).mockImplementation(async (payload) => ({ ...task(String(payload.id)), revision: 4, flowJson: payload.flowJson }));
  vi.mocked(api.checkIntegrationFlow).mockResolvedValue({ valid: true, errors: [] });
  vi.mocked(api.resolveIntegrationSchema).mockResolvedValue({ nodes: {} });
  vi.mocked(capabilityApi.getIntegrationComponentCapabilities).mockImplementation(async ({ engine, mode }) => ({ engine, mode, components: integrationComponents.map((component) => ({ componentCode: component.code, status: 'SUPPORTED', reason: '' })) }));
});
afterEach(() => { app?.unmount(); app = undefined; host?.remove(); });

describe('集成流程设计器交互', () => {
  it('离开未保存流程使用有明确按钮的通用确认，取消保留画布并且确认才放行', async () => {
    await mount();
    const leave = vi.mocked(onBeforeRouteLeave).mock.calls.at(-1)![0] as () => Promise<boolean>;
    expect(await leave()).toBe(true);
    expect(harness.confirm).not.toHaveBeenCalled();
    host.querySelector<HTMLButtonElement>('[data-component-code="read-db"]')!.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    await flush();
    const instance = harness.graphs[0];
    const before = instance.nodes.length;
    harness.confirm.mockRejectedValueOnce(new Error('dialog cancelled'));
    expect(await leave()).toBe(false);
    expect(harness.confirm).toHaveBeenLastCalledWith({ title: '流程有未保存的修改', content: '确认丢弃未保存的节点与配置？', confirmText: '丢弃修改', cancelText: '继续编辑', icon: 'warning' });
    expect(instance.nodes).toHaveLength(before);
    expect(await leave()).toBe(true);
  });

  it('新建实时输出和认证节点使用各自执行语义，旧追加输出仍保持原快照', async () => {
    vi.mocked(api.getIntegration).mockResolvedValue({ ...task(undefined, undefined, { nodes: [node('legacy', 'insert-db', { dataSyncType: 1 })], edges: [] }), engine: 'FLINK', mode: 'REALTIME' });
    await mount();
    const instance = harness.graphs[0];
    for (const code of ['insert-db', 'custom-token']) {
      host.querySelector<HTMLButtonElement>(`[data-component-code="${code}"]`)!.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
      await flush();
      expect(harness.editorConfig).toMatchObject(code === 'insert-db' ? { dataSyncType: 2 } : { batchPolicy: 'PARTITION', workerState: 'BATCH' });
    }
    expect(instance.getCellById('legacy').getData().properties.nodeJson).toEqual({ dataSyncType: 1 });
  });
  it('组件状态统一显示可用和不可用，部分能力计入可用', async () => {
    vi.mocked(api.getIntegration).mockResolvedValue({ ...task(), engine: 'FLINK', mode: 'REALTIME' });
    vi.mocked(capabilityApi.getIntegrationComponentCapabilities).mockResolvedValue({ engine: 'FLINK', mode: 'REALTIME', components: [
      { componentCode: 'field-split', status: 'SUPPORTED', reason: '可拆分字段。' },
      { componentCode: 'read-db', status: 'PARTIAL', reason: '仅支持全量读取。' },
      { componentCode: 'read-kafka', status: 'UNAVAILABLE', reason: '实时订阅尚未就绪。' },
    ] });
    await mount();
    const palette = (code: string) => host.querySelector<HTMLButtonElement>(`[data-component-code="${code}"]`)!;
    expect(palette('field-split').disabled).toBe(false);
    expect(palette('read-db').disabled).toBe(false);
    expect(palette('read-db').title).toContain('可用：仅支持全量读取。');
    expect(palette('read-db').querySelector('.qi-palette-status')?.textContent).toBe('可用');
    expect(palette('field-split').querySelector('.qi-palette-status')?.textContent).toBe('可用');
    expect(palette('read-kafka').querySelector('.qi-palette-status')?.textContent).toBe('不可用');
    expect(palette('read-kafka').disabled).toBe(true);
    expect(palette('read-kafka').title).toContain('实时订阅尚未就绪。');
    palette('read-kafka').dispatchEvent(new MouseEvent('dblclick', { bubbles: true })); await flush();
    expect(harness.graphs[0].getNodes()).toHaveLength(0);
    expect(host.querySelector('.qi-capability-summary')?.textContent).toBe('可用 2 · 不可用 47');
  });
  it('能力请求失败时关闭新增并可重试，不删除已有配置', async () => {
    vi.mocked(api.getIntegration).mockResolvedValue(task(undefined, undefined, { nodes: [node('existing', 'multi-join', { joins: [] })], edges: [] }));
    vi.mocked(capabilityApi.getIntegrationComponentCapabilities).mockRejectedValueOnce(new Error('offline'));
    await mount();
    expect(host.querySelector('.qi-capability-error')?.textContent).toContain('组件可用性加载失败');
    expect([...host.querySelectorAll<HTMLButtonElement>('.qi-palette-item')].every((item) => item.disabled)).toBe(true);
    const instance = harness.graphs[0];
    instance.events.get('node:dblclick')({ node: instance.getCellById('existing') }); await flush();
    harness.editorConfig.joins.push({ id: 'retained' }); button('保存').click(); await flush();
    expect(instance.getCellById('existing').getData().properties.nodeJson.joins).toEqual([{ id: 'retained' }]);
    button('重试').click(); await flush();
    expect(host.querySelector('.qi-capability-error')).toBeNull();
    expect(host.querySelector<HTMLButtonElement>('[data-component-code="read-db"]')?.disabled).toBe(false);
  });
  it('切换任务和引擎后迟到的能力响应不能开放新任务组件', async () => {
    const old = deferred<IntegrationComponentCapabilities>();
    vi.mocked(capabilityApi.getIntegrationComponentCapabilities).mockReturnValueOnce(old.promise).mockResolvedValueOnce({ engine: 'FLINK', mode: 'REALTIME', components: [] });
    await mount();
    expect(host.querySelector<HTMLButtonElement>('[data-component-code="read-db"]')?.disabled).toBe(true);
    harness.route.params.id = '9007199254740997';
    vi.mocked(api.getIntegration).mockResolvedValue({ ...task('9007199254740997', '实时任务'), engine: 'FLINK', mode: 'REALTIME' }); await flush();
    old.resolve({ engine: 'SPARK', mode: 'OFFLINE', components: [{ componentCode: 'read-db', status: 'SUPPORTED', reason: '' }] }); await flush();
    expect(capabilityApi.getIntegrationComponentCapabilities).toHaveBeenLastCalledWith({ engine: 'FLINK', mode: 'REALTIME' });
    expect(host.querySelector<HTMLButtonElement>('[data-component-code="read-db"]')?.disabled).toBe(true);
    expect(host.querySelector('.qi-capability-summary')?.textContent).toBe('可用 0 · 不可用 49');
  });
  it('全部组件都能查看输出字段，只有非输入组件展示输入字段菜单', async () => {
    vi.mocked(api.getIntegration).mockResolvedValue(task(undefined, undefined, { nodes: integrationComponents.map((component) => node(component.code, component.code)), edges: [] }));
    await mount();
    const instance = harness.graphs[0];
    for (const component of integrationComponents) {
      instance.events.get('node:contextmenu')({ node: instance.getCellById(component.code), e: { preventDefault: vi.fn(), clientX: 100, clientY: 100 } });
      await flush();
      const labels = [...host.querySelectorAll('.qi-node-context button')].map((element) => element.textContent?.trim());
      expect(labels).toEqual(['删除节点', '编辑节点', ...(component.group === 'input' ? [] : ['显示输入字段']), '显示输出字段']);
    }
  });
  it('只读任务可查询真实输入输出元数据且不保存或运行任务', async () => {
    harness.update = false;
    vi.mocked(api.getIntegration).mockResolvedValue(task(undefined, undefined, { nodes: [node('source', 'read-db'), node('sink', 'insert-db')], edges: [{ sourceNodeId: 'source', targetNodeId: 'sink' }] }));
    vi.mocked(api.resolveIntegrationSchema).mockResolvedValue({ nodes: { sink: { complete: true, inputFields: [{ name: 'amount', dataType: 'decimal', precision: 30, scale: 10, length: '9007199254740993', sourceName: '表输入组件' }], outputFields: [{ name: 'total', dataType: 'decimal', precision: 30, scale: 0, sourceName: '表输出组件' }] } } });
    await mount();
    const instance = harness.graphs[0];
    const openMenu = () => instance.events.get('node:contextmenu')({ node: instance.getCellById('sink'), e: { preventDefault: vi.fn(), clientX: 100, clientY: 100 } });
    openMenu(); await flush();
    expect(button('删除节点').disabled).toBe(true);
    button('显示输入字段').click(); await flush();
    expect(host.querySelector('table[aria-label="输入字段"]')?.textContent).toContain('9007199254740993');
    expect(host.querySelector('table[aria-label="输入字段"]')?.textContent).toContain('表输入组件');
    expect(vi.mocked(api.resolveIntegrationSchema).mock.calls[0]?.[0]).toMatchObject({ id: '9007199254740993', revision: 3 });
    button('关闭').click(); await flush();
    openMenu(); await flush(); button('显示输出字段').click(); await flush();
    const cells = [...host.querySelectorAll('table[aria-label="输出字段"] tbody td')].map((cell) => cell.textContent?.trim());
    expect(cells).toContain('total'); expect(cells).toContain('0'); expect(cells).toContain('表输出组件');
    expect(api.saveIntegrationFlow).not.toHaveBeenCalled(); expect(api.checkIntegrationFlow).not.toHaveBeenCalled();
  });
  it('切换任务后迟到的字段响应不会打开旧弹窗或泄露旧字段', async () => {
    const old = deferred<Api.SchemaResult>();
    vi.mocked(api.getIntegration).mockResolvedValue(task(undefined, undefined, { nodes: [node('source', 'read-db')], edges: [] }));
    vi.mocked(api.resolveIntegrationSchema).mockReturnValueOnce(old.promise);
    await mount();
    const instance = harness.graphs[0];
    instance.events.get('node:contextmenu')({ node: instance.getCellById('source'), e: { preventDefault: vi.fn(), clientX: 100, clientY: 100 } });
    await flush(); button('显示输出字段').click(); await flush();
    harness.route.params.id = '9007199254740997';
    vi.mocked(api.getIntegration).mockResolvedValue(task('9007199254740997', '新任务'));
    await flush(); old.resolve({ nodes: { source: { complete: true, outputFields: [{ name: '旧敏感字段' }] } } }); await flush();
    expect(host.querySelector('table[aria-label="输出字段"]')).toBeNull(); expect(host.textContent).not.toContain('旧敏感字段');
  });
  it('保存条件分支执行端口并无损保留任务/节点编号及未知属性', async () => {
    const graph = { nodes: [node('branch', 'data-condition', { branches: [{ id: 'high', targetNodeNo: '9007199254740995' }] }), node('9007199254740995', 'insert-db')], edges: [{ id: 'edge', sourceNodeId: 'branch', targetNodeId: '9007199254740995', properties: { outputPort: 'main', vendor: 42 } }], vendorRoot: 'keep' };
    vi.mocked(api.getIntegration).mockResolvedValue(task(undefined, undefined, graph));
    await mount();
    const instance = harness.graphs[0]; instance.events.get('node:dblclick')({ node: instance.getCellById('branch') }); await flush();
    harness.editorConfig.branches[0].id = 'alert';
    button('保存').click(); await flush(); button('任务保存').click(); await flush();
    const payload = vi.mocked(api.saveIntegrationFlow).mock.calls[0]![0];
    expect(payload.id).toBe('9007199254740993');
    const saved = JSON.parse(payload.flowJson);
    expect(saved.vendorRoot).toBe('keep'); expect(saved.nodes[1].id).toBe('9007199254740995');
    expect(saved.edges[0]).toMatchObject({ sourcePort: 'alert', targetPort: 'in', properties: { outputPort: 'alert', vendor: 42 } });
  });
  it('没有更新权限时键盘/双击/拖拽均不能新增节点', async () => {
    harness.update = false; await mount();
    const palette = host.querySelector<HTMLButtonElement>('.qi-palette-item')!;
    expect(palette.disabled).toBe(true); expect(button('任务保存').disabled).toBe(true);
    palette.dispatchEvent(new MouseEvent('dblclick', { bubbles: true }));
    palette.dispatchEvent(new KeyboardEvent('keydown', { key: 'Enter', bubbles: true }));
    palette.dispatchEvent(new MouseEvent('mousedown', { bubbles: true, button: 0 })); await flush();
    expect(harness.graphs[0].getNodes()).toHaveLength(0); expect(harness.drag).not.toHaveBeenCalled();
    expect(harness.graphs[0].options.interacting()).toBe(false);
  });
  it('任务切换隔离迟到加载，旧图不能覆盖新任务', async () => {
    const old = deferred<Api.Task>(); vi.mocked(api.getIntegration).mockReturnValueOnce(old.promise);
    await mount(); harness.route.params.id = '9007199254740997';
    vi.mocked(api.getIntegration).mockResolvedValue(task('9007199254740997', '新任务')); await flush();
    old.resolve(task('9007199254740993', '旧任务')); await flush();
    expect(host.textContent).toContain('新任务'); expect(host.textContent).not.toContain('旧任务');
    expect(harness.graphs).toHaveLength(1); expect(api.getIntegration).toHaveBeenLastCalledWith('9007199254740997');
  });
  it('旧任务迟到保存不能覆盖新任务或把新任务卡在保存中', async () => {
    const old = deferred<Api.Task>(); vi.mocked(api.saveIntegrationFlow).mockReturnValueOnce(old.promise);
    await mount(); button('任务保存').click(); await flush();
    harness.route.params.id = '9007199254740997';
    vi.mocked(api.getIntegration).mockResolvedValue(task('9007199254740997', '新任务')); await flush();
    expect(button('任务保存').disabled).toBe(false);
    old.resolve(task('9007199254740993', '旧任务')); await flush();
    expect(host.textContent).toContain('新任务'); button('任务保存').click(); await flush();
    expect(api.saveIntegrationFlow).toHaveBeenCalledTimes(2); expect(vi.mocked(api.saveIntegrationFlow).mock.calls[1]![0].id).toBe('9007199254740997');
  });
  it('键盘新增会阻止按钮默认激活，避免 Enter 松开时关闭刚打开的弹窗', async () => {
    await mount();
    const key = new KeyboardEvent('keydown', { key: 'Enter', bubbles: true, cancelable: true });
    host.querySelector<HTMLButtonElement>('[data-component-code="read-db"]')!.dispatchEvent(key); await flush();
    expect(key.defaultPrevented).toBe(true); expect(host.querySelector('[data-modal]')).not.toBeNull();
  });
  it('子编辑器校验失败不会把无效节点草稿写回画布', async () => {
    vi.mocked(api.getIntegration).mockResolvedValue(task(undefined, undefined, { nodes: [node('branch', 'data-condition', { branches: [] })], edges: [] }));
    await mount(); const instance = harness.graphs[0]; instance.events.get('node:dblclick')({ node: instance.getCellById('branch') }); await flush();
    expect(host.querySelector('[message="无法识别该历史组件，原始配置仍会保留。"]')).toBeNull();
    harness.editorValid = false; harness.editorConfig.branches.push({ id: 'invalid', targetNodeNo: 'missing' });
    button('保存').click(); await flush();
    expect(harness.warning).toHaveBeenCalledWith('请完善节点必填配置');
    expect(instance.getCellById('branch').getData().properties.nodeJson.branches).toEqual([]);
    expect(host.querySelector('[data-modal]')).not.toBeNull();
  });
  it('迟到任务检查不能阻塞下一个任务的检查操作', async () => {
    const old = deferred<{ errors: string[]; valid: boolean; }>(); vi.mocked(api.checkIntegrationFlow).mockReturnValueOnce(old.promise);
    await mount(); button('任务检查').click(); await flush();
    harness.route.params.id = '9007199254740997'; vi.mocked(api.getIntegration).mockResolvedValue(task('9007199254740997', '新任务')); await flush();
    old.resolve({ valid: false, errors: ['旧任务错误'] }); await flush();
    expect(host.textContent).not.toContain('旧任务错误'); button('任务检查').click(); await flush();
    expect(api.checkIntegrationFlow).toHaveBeenCalledTimes(2); expect(host.textContent).toContain('检查通过');
  });
  it('脚本编译检查当前隔离草稿的完整图，期间编辑不改变已提交检查或画布', async () => {
    const old = deferred<{ errors: string[]; valid: boolean }>();
    vi.mocked(api.checkIntegrationFlow).mockReturnValueOnce(old.promise);
    vi.mocked(api.getIntegration).mockResolvedValue(task(undefined, undefined, { nodes: [node('script', 'java-script', { code: 'original', vendor: { retained: true } })], edges: [] }));
    await mount();
    const instance = harness.graphs[0];
    instance.events.get('node:dblclick')({ node: instance.getCellById('script') }); await flush();
    harness.editorConfig.code = 'candidate';
    button('编译').click(); await flush();
    const submitted = JSON.parse(vi.mocked(api.checkIntegrationFlow).mock.calls[0]![0].flowJson);
    expect(submitted.nodes[0].properties.nodeJson).toEqual({ code: 'candidate', vendor: { retained: true } });
    expect(instance.getCellById('script').getData().properties.nodeJson.code).toBe('original');
    harness.editorConfig.code = 'after request'; await flush();
    old.resolve({ valid: true, errors: [] }); await flush();
    expect(host.textContent).not.toContain('完整流程检查通过，已包含当前节点草稿。');
    expect(submitted.nodes[0].properties.nodeJson.code).toBe('candidate');
    expect(api.saveIntegrationFlow).not.toHaveBeenCalled();
  });

});
