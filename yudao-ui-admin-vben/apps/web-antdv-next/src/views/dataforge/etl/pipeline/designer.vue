<script lang="ts" setup>
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type { EtlTemplateGraph } from '#/api/dataforge/etl/template';

import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  provide,
  reactive,
  ref,
  shallowRef,
  watch,
} from 'vue';
import { onBeforeRouteLeave } from 'vue-router';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Graph } from '@antv/x6';
import { Dnd } from '@antv/x6-plugin-dnd';
import { Keyboard } from '@antv/x6-plugin-keyboard';
import { Selection } from '@antv/x6-plugin-selection';
import { Snapline } from '@antv/x6-plugin-snapline';
import {
  Alert,
  Button,
  Checkbox,
  Drawer,
  Input,
  InputNumber,
  message,
  Modal,
  Popconfirm,
  RadioGroup,
  Tag,
  TextArea,
  Tooltip,
} from 'antdv-next';

import {
  getEtlNodeRecords,
  getEtlPipeline,
  getEtlRecordLog,
  getEtlToolList,
  resolveEtlSchema,
  runEtlCommand,
  saveEtlPipeline,
  stopEtlCommand,
  validateEtlPipeline,
} from '#/api/dataforge/etl';
import { CronTab } from '#/components/cron-tab';
import { getRunState } from '#/views/dataforge/execution/run-status';
import { useRunCommands } from '#/views/dataforge/execution/use-run-commands';

import QualityTaskField from '../../dev/modules/quality-task-field.vue';
import { TASK_TYPE_OPTIONS } from '../data';
import { etlEnvironmentKey } from './environment-context';
import EnvironmentField from './environment-field.vue';
import FieldContractSummary from './field-contract-summary.vue';
import {
  createsCycle,
  getOutputPorts,
  getSourcePort,
  getVisualSourcePort,
  serializeEdgePort,
} from './graph-contract';
import { applyEtlHistory, createEtlHistory } from './history';
import { autoLayoutEtlGraph } from './layout';
import {
  getDefaultNodeConfig,
  getNodeEditor,
  isNodeSupported,
} from './node-editors/registry';
import { groupNodeRunStatuses } from './node-run-status';
import ParameterEditor from './parameter-editor.vue';
import { initialParameterValues } from './parameters';
import PreviewDialog from './preview-dialog.vue';
import RunParametersDialog from './run-parameters-dialog.vue';
import {
  edgeKey,
  edgeRuntimeText,
  findBottleneck,
  mapEdgesToDesign,
  nodeNameMap,
  PRESSURE_COLORS,
  summarizeRuntimeEdges,
  withLiveCounts,
} from './runtime-metrics';
import { appendEtlTemplate, captureEtlTemplate } from './template-graph';
import TemplateLibrary from './template-library.vue';
import VersionHistory from './version-history.vue';
import {
  getCategoryStyle,
  getNodePorts,
  NODE_HEIGHT,
  NODE_WIDTH,
  registerEtlShapes,
  runtimeEdgeLabel,
  setupEtlInteraction,
} from './x6-config';

defineOptions({ name: 'DataforgeEtlDesigner', inheritAttrs: false });

const emit = defineEmits<{ (e: 'saved'): void }>();

/** ==================== 抽屉与初始化 ==================== */
const visible = ref(false);
const containerRef = ref<HTMLElement>();
const graph = shallowRef<Graph>();
const dnd = shallowRef<Dnd>();
const history = shallowRef<ReturnType<typeof createEtlHistory>>();
const undoAvailable = ref(false);
const redoAvailable = ref(false);
const hydratingGraph = ref(true);
const saving = ref(false);
const running = ref(false);
const restoreReloadError = ref('');
const versionHistoryRef = ref<InstanceType<typeof VersionHistory>>();
const templateLibraryRef = ref<InstanceType<typeof TemplateLibrary>>();

const { hasAccessByCodes } = useAccess();
/** 操作能力来自菜单按钮权限；接口仍是最终授权边界。 */
const canDevelop = computed(() => hasAccessByCodes(['dataforge:etl:save']));
const canEditGraph = computed(
  () =>
    canDevelop.value &&
    !running.value &&
    !saving.value &&
    !hydratingGraph.value &&
    !restoreReloadError.value,
);
const canQuery = computed(() => hasAccessByCodes(['dataforge:etl:query']));
const canOperate = computed(() => hasAccessByCodes(['dataforge:etl:run']));
const canPreview = computed(
  () => canOperate.value && hasAccessByCodes(['dataforge:etl:query']),
);
const pipeline = reactive<DataforgeEtlApi.Pipeline>({});
provide(
  etlEnvironmentKey,
  computed(() => pipeline.environmentId),
);
const accessStore = useAccessStore();
const userStore = useUserStore();
const runParametersRef = ref<InstanceType<typeof RunParametersDialog>>();
const pendingRunInputs = new Map<
  string,
  Omit<DataforgeEtlApi.EtlRunCommand, 'requestId'>
>();
const unconfirmedRuns = ref<string[]>([]);
function resetUnconfirmedRuns() {
  if (runCommands.busy.value) return;
  Modal.confirm({
    title: '已核对本页所有未确认请求？',
    content: `请先检查流程日志中的执行记录。放弃本页未确认命令后会生成新请求，原执行不会被取消，可能导致重复执行。涉及流程：${unconfirmedRuns.value.join('、')}`,
    onOk: () => {
      runCommands.invalidate();
      pendingRunInputs.clear();
      unconfirmedRuns.value = [];
    },
  });
}
const runCommands = useRunCommands();
const stopCommands = useRunCommands();
let openSeq = 0;
const graphRevision = ref(0);
const savedSnapshot = ref('');
function currentSnapshot() {
  return JSON.stringify({
    name: pipeline.name,
    environmentId: pipeline.environmentId,
    qualityTaskId: pipeline.qualityTaskId,
    taskType: pipeline.taskType,
    cron: pipeline.cron,
    description: pipeline.description,
    parameters: pipeline.parameters,
    ...getGraphJson(),
  });
}
const dirty = computed(() => {
  void graphRevision.value;
  return visible.value && savedSnapshot.value !== currentSnapshot();
});
function confirmDiscard() {
  if (!dirty.value) return Promise.resolve(true);
  return new Promise<boolean>((resolve) =>
    Modal.confirm({
      title: '流程有未保存的修改',
      content: '确认离开并丢弃未保存的节点与配置？',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    }),
  );
}
onBeforeRouteLeave(confirmDiscard);
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) event.preventDefault();
}
onMounted(() => window.addEventListener('beforeunload', beforeUnload));
watch(
  () => [
    accessStore.accessToken,
    accessStore.refreshToken,
    userStore.userInfo?.userId,
  ],
  () => {
    pendingRunInputs.clear();
    unconfirmedRuns.value = [];
    disposeCanvas();
  },
);
async function open(id?: DataforgeEtlApi.Id) {
  const seq = ++openSeq;
  hydratingGraph.value = true;
  restoreReloadError.value = '';
  versionHistoryRef.value?.close();
  templateLibraryRef.value?.close();
  history.value?.clean();
  resetDiagnostics();
  saving.value = false;
  runParametersRef.value?.close();
  stopCommands.invalidate();
  visible.value = true;
  running.value = false;
  currentRecordId.value = undefined;
  currentRunRecord.value = undefined;
  runtimeBottleneck.value = '';
  Object.keys(pipeline).forEach((key) => Reflect.deleteProperty(pipeline, key));
  Object.assign(pipeline, { taskType: 1, parameters: [] });
  stopPolling();
  runLog.value = '';
  logVisible.value = false;
  await loadTools(seq);
  if (seq !== openSeq || !visible.value) return;
  await setupCanvas(id, seq);
  if (seq === openSeq) savedSnapshot.value = currentSnapshot();
}

/**
 * 等待画布容器挂载且有真实尺寸后初始化（抽屉懒挂载 + 打开动画期间容器不可用）；
 * destroy-on-hidden 会销毁旧容器 DOM，因此每次打开都重建 X6 实例
 */
async function setupCanvas(id?: DataforgeEtlApi.Id, seq = openSeq) {
  await nextTick();
  const ready = await waitContainerReady();
  if (!ready) {
    message.error('画布初始化失败，请关闭后重试');
    return;
  }
  if (seq !== openSeq || !visible.value) return;
  initGraph();
  if (id) {
    const data = await getEtlPipeline(id);
    if (seq !== openSeq || !visible.value) return;
    Object.assign(pipeline, data);
    renderGraph(data.nodes ?? [], data.edges ?? []);
    resetView();
    refreshSchema();
  } else {
    configVisible.value = true;
  }
  if (seq === openSeq && visible.value) {
    history.value?.clean();
    hydratingGraph.value = false;
  }
}

/** 最多等待 5 秒容器就绪 */
async function waitContainerReady(): Promise<boolean> {
  for (let i = 0; i < 100; i++) {
    const el = containerRef.value;
    if (el && el.clientWidth > 0 && el.clientHeight > 0) {
      return true;
    }
    await new Promise((resolve) => setTimeout(resolve, 50));
  }
  return false;
}

defineExpose({ open });

async function handleClose() {
  if (!(await confirmDiscard())) return;
  disposeCanvas();
}

function disposeCanvas() {
  ++openSeq;
  resetDiagnostics();
  saving.value = false;
  nodeDrawerVisible.value = false;
  configVisible.value = false;
  versionHistoryRef.value?.close();
  templateLibraryRef.value?.close();
  restoreReloadError.value = '';
  hydratingGraph.value = true;
  history.value?.clean();
  history.value = undefined;
  undoAvailable.value = false;
  redoAvailable.value = false;
  runParametersRef.value?.close();
  stopCommands.invalidate();
  running.value = false;
  currentRecordId.value = undefined;
  currentRunRecord.value = undefined;
  runtimeBottleneck.value = '';
  ++schemaSeq;
  if (schemaTimer) clearTimeout(schemaTimer);
  stopPolling();
  visible.value = false;
  // 抽屉关闭会销毁画布 DOM，销毁实例待下次打开重建
  graph.value?.dispose();
  graph.value = undefined;
  dnd.value = undefined;
}

onBeforeUnmount(() => {
  disposeCanvas();
  window.removeEventListener('beforeunload', beforeUnload);
});

function initGraph() {
  if (!containerRef.value) {
    message.error('画布容器未就绪，请关闭后重试');
    return;
  }
  registerEtlShapes();
  const instance: Graph = new Graph({
    container: containerRef.value,
    autoResize: true,
    interacting: () => canEditGraph.value,
    background: { color: '#ffffff' },
    grid: {
      visible: true,
      size: 16,
      type: 'dot',
      args: { color: '#c2c8d5', thickness: 1.5 },
    },
    panning: true,
    mousewheel: { enabled: true, minScale: 0.5, maxScale: 3 },
    connecting: {
      snap: { radius: 20 },
      allowBlank: false,
      allowLoop: false,
      allowNode: false,
      allowEdge: false,
      allowMulti: false,
      highlight: true,
      connector: { name: 'smooth' },
      connectionPoint: 'anchor',
      createEdge() {
        return this.createEdge({ shape: 'etl-edge', data: {} });
      },
      // 只允许从右侧输出端口连到左侧输入端口
      validateConnection({
        sourceMagnet,
        targetMagnet,
        sourceCell,
        targetCell,
        edge,
      }) {
        if (
          !sourceMagnet ||
          sourceMagnet.getAttribute('port-group') !== 'out'
        ) {
          return false;
        }
        if (
          !targetMagnet ||
          targetMagnet.getAttribute('port-group') !== 'in' ||
          !sourceCell ||
          !targetCell
        )
          return false;
        const edges = instance
          .getEdges()
          .filter((item) => item.id !== edge?.id)
          .map((item) => ({
            sourceNodeId: item.getSourceCellId(),
            targetNodeId: item.getTargetCellId(),
          }));
        return !createsCycle(edges, sourceCell.id, targetCell.id);
      },
    },
    highlighting: {
      magnetAdsorbed: {
        name: 'stroke',
        args: { attrs: { fill: '#5f95ff', stroke: '#5f95ff' } },
      },
    },
  });
  instance.use(new Snapline({ enabled: true }));
  instance.use(
    new Selection({
      enabled: true,
      multiple: true,
      rubberband: true,
      modifiers: 'shift',
      showNodeSelectionBox: false,
    }),
  );
  instance.use(new Keyboard({ enabled: true }));
  const editHistory = createEtlHistory(() => canEditGraph.value);
  instance.use(editHistory);
  history.value = editHistory;
  editHistory.on('change', () => {
    undoAvailable.value = editHistory.canUndo();
    redoAvailable.value = editHistory.canRedo();
  });
  instance.bindKey(['ctrl+z', 'meta+z'], () => {
    handleHistory('undo');
    return false;
  });
  instance.bindKey(['ctrl+shift+z', 'meta+shift+z', 'ctrl+y'], () => {
    handleHistory('redo');
    return false;
  });
  instance.bindKey(['ctrl+s', 'meta+s'], () => {
    if (canEditGraph.value) void handleSave().catch(() => {});
    return false;
  });
  instance.bindKey(['delete', 'backspace'], () => {
    if (!canEditGraph.value) return false;
    const cells = instance.getSelectedCells();
    if (cells.length > 0) {
      instance.batchUpdate('etl-delete-selection', () =>
        instance.removeCells(cells),
      );
    }
    return false;
  });

  for (const event of [
    'node:added',
    'node:removed',
    'node:change:position',
    'node:change:data',
    'edge:added',
    'edge:removed',
    'edge:change:data',
    'edge:change:source',
    'edge:change:target',
  ]) {
    instance.on(event, () => {
      graphRevision.value += 1;
    });
  }
  instance.on('node:dblclick', ({ node }) => openNodeDrawer(node.id));
  instance.on('node:added', () => refreshSchema());
  instance.on('node:removed', () => refreshSchema());
  instance.on('edge:connected', () => refreshSchema());
  instance.on('edge:removed', () => refreshSchema());
  // 选中态通过 data 驱动卡片样式（保存时会剔除该临时字段）
  instance.on('node:selected', ({ node }) => node.setData({ selected: true }));
  instance.on('node:unselected', ({ node }) =>
    node.setData({ selected: false }),
  );
  setupEtlInteraction(instance);

  graph.value = instance;
  dnd.value = new Dnd({ target: instance });
}

function handleHistory(action: 'redo' | 'undo') {
  if (!canEditGraph.value || !graph.value || !history.value) return;
  nodeDrawerVisible.value = false;
  applyEtlHistory(graph.value, history.value, action);
  graphRevision.value += 1;
  refreshSchema();
}

function handleAutoLayout() {
  if (!canEditGraph.value || !graph.value) return;
  try {
    if (autoLayoutEtlGraph(graph.value)) {
      graphRevision.value += 1;
      resetView();
    }
  } catch {
    message.error('自动布局失败，请检查画布节点后重试。');
  }
}

function saveAsTemplate() {
  if (!canEditGraph.value || !canQuery.value || !graph.value) return;
  const selected = graph.value
    .getSelectedCells()
    .filter((cell) => cell.isNode())
    .map((cell) => cell.id);
  const fragment = captureEtlTemplate(getGraphJson(), selected);
  if (fragment.nodes.length === 0) {
    message.warning('请先在画布添加节点。');
    return;
  }
  templateLibraryRef.value?.openCreate(fragment);
}

function applyTemplate(fragment: EtlTemplateGraph) {
  if (!canEditGraph.value || !canQuery.value || !graph.value) return;
  try {
    appendEtlTemplate(graph.value, fragment);
    graphRevision.value += 1;
    resetView();
    refreshSchema();
  } catch (error) {
    message.error(error instanceof Error ? error.message : '模板应用失败。');
  }
}

/** 画布 → 持久化 JSON（保持与后端约定的 nodes/edges 结构） */
function getGraphJson() {
  const instance = graph.value;
  if (!instance) {
    return { nodes: [] as any[], edges: [] as any[] };
  }
  const nodes = instance.getNodes().map((node) => {
    // selected / runStatus / readCount / writeCount 为画布运行时临时状态，不持久化
    const {
      selected: _selected,
      runStatus: _runStatus,
      readCount: _readCount,
      writeCount: _writeCount,
      ...properties
    } = node.getData() ?? {};
    return {
      id: node.id,
      type: 'EtlTask',
      x: node.position().x,
      y: node.position().y,
      properties,
    };
  });
  const edges = instance.getEdges().map((edge) => ({
    id: edge.id,
    type: 'etl-edge',
    sourceNodeId: edge.getSourceCellId(),
    targetNodeId: edge.getTargetCellId(),
    sourcePort: serializeEdgePort(edge.getSourcePortId(), edge.getData()),
    targetPort: edge.getTargetPortId() ?? 'in',
    properties: {
      ...edge.getData(),
      outputPort: serializeEdgePort(edge.getSourcePortId(), edge.getData()),
    },
  }));
  return { nodes, edges };
}

/** 重置视角：内容适配画布并整体居中（空画布则恢复 100% 缩放） */
function resetView() {
  const instance = graph.value;
  if (!instance) {
    return;
  }
  if (instance.getNodes().length === 0) {
    instance.zoomTo(1);
    return;
  }
  instance.zoomToFit({ padding: 24, maxScale: 1 });
  instance.centerContent();
}

/** 持久化 JSON → 画布 */
function renderGraph(
  nodes: Record<string, any>[],
  edges: Record<string, any>[],
) {
  const instance = graph.value;
  if (!instance) {
    return;
  }
  instance.clearCells();
  nodes.forEach((node) => {
    instance.addNode({
      shape: 'etl-task',
      id: node.id,
      x: node.x ?? 0,
      y: node.y ?? 0,
      width: NODE_WIDTH,
      height: NODE_HEIGHT,
      data: { ...node.properties },
      ports: getNodePorts(node.properties?.toolCode, node.properties?.nodeJson),
    });
  });
  edges.forEach((edge) => {
    instance.addEdge({
      id: edge.id,
      shape: 'etl-edge',
      source: {
        cell: edge.sourceNodeId,
        port: getVisualSourcePort(
          edge,
          nodes.find((node) => node.id === edge.sourceNodeId)?.properties
            ?.toolCode,
        ),
      },
      target: { cell: edge.targetNodeId, port: edge.targetPort ?? 'in' },
      data: { ...edge.properties, outputPort: getSourcePort(edge) },
    });
  });
}

/** ==================== 左侧工具面板 ==================== */
const tools = ref<DataforgeEtlApi.Tool[]>([]);
const toolKeyword = ref('');
const collapsedGroups = reactive<Record<string, boolean>>({});

async function loadTools(seq: number) {
  tools.value = [];
  const result = await getEtlToolList();
  if (seq === openSeq && visible.value) tools.value = result;
}

/** 分组卡片列表（一级目录为分组，二级为可拖拽组件卡片） */
const toolGroups = computed(() => {
  const keyword = toolKeyword.value.trim().toLowerCase();
  return tools.value
    .filter((tool) => tool.type === -1)
    .toSorted((a, b) => (a.orderNo ?? 0) - (b.orderNo ?? 0))
    .map((group) => ({
      ...group,
      children: tools.value
        .filter(
          (tool) =>
            String(tool.parentId) === String(group.id) &&
            tool.type !== -1 &&
            (!keyword || tool.name?.toLowerCase().includes(keyword)),
        )
        .toSorted((a, b) => (a.orderNo ?? 0) - (b.orderNo ?? 0)),
    }))
    .filter((group) => group.children.length > 0);
});

function toggleGroup(id: DataforgeEtlApi.Id) {
  collapsedGroups[id] = !collapsedGroups[id];
}

function handleToolMouseDown(tool: DataforgeEtlApi.Tool, event: MouseEvent) {
  if (!canEditGraph.value) return;
  if (tool.type === -1) {
    return;
  }
  if (!isNodeSupported(tool.code)) {
    message.warning('该组件暂未开放，敬请期待');
    return;
  }
  if (!graph.value || !dnd.value) {
    message.warning('画布尚未初始化完成，请稍候');
    return;
  }
  const node = graph.value.createNode({
    shape: 'etl-task',
    width: NODE_WIDTH,
    height: NODE_HEIGHT,
    data: {
      name: tool.name,
      toolCode: tool.code,
      toolType: tool.type,
      toolName: tool.name,
      icon: tool.icon,
      nodeJson: getDefaultNodeConfig(tool.code, tool.name),
      failGoOn: 0,
      overTimes: 0,
    },
    ports: getNodePorts(tool.code),
  });
  dnd.value.start(node, event);
}

/** ==================== 流程配置 ==================== */
const configVisible = ref(false);

function handleConfigOk() {
  if (!pipeline.name?.trim()) {
    message.warning('请填写流程名称');
    return;
  }
  if (pipeline.taskType === 2 && !pipeline.cron?.trim()) {
    message.warning('周期任务请配置 Cron 表达式');
    return;
  }
  configVisible.value = false;
}

/** ==================== 节点配置抽屉 ==================== */
const nodeDrawerVisible = ref(false);
const currentNodeId = ref('');
const nodeForm = reactive<Record<string, any>>({});
const nodeConfig = reactive<Record<string, any>>({});
const nodeEditor = ref<{ validate?: () => boolean }>();

const currentEditor = computed(() => getNodeEditor(nodeForm.toolCode));

const currentInputFields = computed(
  () => schemaResult.value?.nodes?.[currentNodeId.value]?.inputFields ?? [],
);
const currentQualitySources = computed(() => {
  void graphRevision.value;
  const { nodes, edges } = getGraphJson();
  const sources = new Set(
    edges
      .filter(
        (edge) =>
          edge.targetNodeId === currentNodeId.value &&
          getSourcePort(edge) === 'main',
      )
      .map((edge) => edge.sourceNodeId),
  );
  return nodes
    .filter(
      (node) =>
        sources.has(node.id) &&
        node.properties?.toolCode === 'quality-check' &&
        node.properties.nodeJson?.disposition === 'KEEP_TAGGED',
    )
    .map((node) => ({
      nodeNo: node.id,
      name: node.properties.name ?? node.id,
      statusField: node.properties.nodeJson.statusField ?? '_quality_status',
    }));
});

/** 当前节点的数据下游（条件判断节点分支绑定用） */
const currentDownstreams = computed(() => {
  const { nodes, edges } = getGraphJson();
  return edges
    .filter((edge: any) => edge.sourceNodeId === currentNodeId.value)
    .map((edge: any) => {
      const target = nodes.find((node: any) => node.id === edge.targetNodeId);
      return {
        targetId: edge.targetNodeId,
        targetName: target?.properties?.name ?? edge.targetNodeId,
      };
    });
});

function openNodeDrawer(nodeId: string) {
  const node = graph.value?.getCellById(nodeId);
  if (!node) {
    return;
  }
  currentNodeId.value = nodeId;
  const data = (node.getData() ?? {}) as Record<string, any>;
  Object.keys(nodeForm).forEach((key) => Reflect.deleteProperty(nodeForm, key));
  Object.assign(nodeForm, {
    name: data.name,
    toolCode: data.toolCode,
    toolName: data.toolName,
    failGoOn: data.failGoOn ?? 0,
    overTimes: data.overTimes ?? 0,
  });
  Object.keys(nodeConfig).forEach((key) =>
    Reflect.deleteProperty(nodeConfig, key),
  );
  Object.assign(nodeConfig, JSON.parse(JSON.stringify(data.nodeJson ?? {})));
  nodeDrawerVisible.value = true;
}

function handleNodeSubmit() {
  if (!canEditGraph.value) return;
  if (!nodeForm.name?.trim()) {
    message.warning('请填写节点名称');
    return;
  }
  if (nodeEditor.value?.validate?.() === false) return;
  const node = graph.value?.getCellById(currentNodeId.value);
  if (!node?.isNode()) {
    return;
  }
  graph.value?.batchUpdate('etl-node-config', () => {
    if (
      new Set([
        'boundary-input',
        'boundary-output',
        'quality-check',
        'sub-pipeline',
      ]).has(nodeForm.toolCode)
    ) {
      const validPorts = getOutputPorts(nodeForm.toolCode, nodeConfig);
      const ports = getNodePorts(nodeForm.toolCode, nodeConfig);
      const inputPorts = new Set(
        ports.items
          .filter((port: { group: string }) => port.group === 'in')
          .map((port: { id: string }) => port.id),
      );
      let removed = false;
      for (const edge of graph.value?.getEdges() ?? []) {
        if (
          (edge.getSourceCellId() === node.id &&
            !validPorts.includes(
              serializeEdgePort(edge.getSourcePortId(), edge.getData()),
            )) ||
          (edge.getTargetCellId() === node.id &&
            !inputPorts.has(edge.getTargetPortId() ?? 'in'))
        ) {
          graph.value?.removeCell(edge);
          removed = true;
        }
      }
      node.prop('ports', ports);
      if (removed) message.warning('节点端口已变化，请重新连接失效的连线');
    }
    node.setData(
      {
        ...node.getData(),
        name: nodeForm.name,
        failGoOn: nodeForm.failGoOn,
        overTimes: nodeForm.overTimes,
        nodeJson: JSON.parse(JSON.stringify(nodeConfig)),
      },
      { overwrite: true },
    );
    // 条件判断节点：把分支绑定回写到对应连线的输出端口
    if (nodeForm.toolCode === 'data-condition') {
      const branches: any[] = nodeConfig.branches ?? [];
      for (const edge of graph.value?.getEdges() ?? []) {
        if (edge.getSourceCellId() !== currentNodeId.value) {
          continue;
        }
        const branch = branches.find(
          (item) => item.targetNodeNo === edge.getTargetCellId(),
        );
        edge.setData(
          { ...edge.getData(), outputPort: branch?.id ?? 'main' },
          { overwrite: true },
        );
      }
    }
  });
  nodeDrawerVisible.value = false;
  refreshSchema();
}

function handleNodeDelete() {
  if (!canEditGraph.value) return;
  graph.value?.batchUpdate('etl-delete-node', () =>
    graph.value?.removeCell(currentNodeId.value),
  );
  nodeDrawerVisible.value = false;
  refreshSchema();
}

/** ==================== 字段契约推导 ==================== */
const schemaResult = ref<DataforgeEtlApi.SchemaResult>();
const schemaFailure = ref('');
let schemaSeq = 0;
let schemaTimer: ReturnType<typeof setTimeout> | undefined;

function refreshSchema() {
  const seq = ++schemaSeq;
  if (schemaTimer) {
    clearTimeout(schemaTimer);
  }
  schemaTimer = setTimeout(async () => {
    try {
      const { nodes, edges } = getGraphJson();
      if (nodes.length === 0) {
        schemaResult.value = undefined;
        return;
      }
      const result = await resolveEtlSchema({
        nodes,
        edges,
        environmentId: pipeline.environmentId,
        ...(pipeline.parameters?.length
          ? {
              parameterDefinitions: pipeline.parameters,
              parameters: initialParameterValues(pipeline.parameters),
            }
          : {}),
      });
      if (seq === schemaSeq && visible.value) {
        schemaResult.value = result;
        schemaFailure.value = '';
      }
    } catch {
      if (seq === schemaSeq && visible.value) {
        schemaResult.value = undefined;
        schemaFailure.value = '字段推导失败，请重新校验或检查节点配置后重试';
      }
    }
  }, 300);
}

/** ==================== 校验与有界预览 ==================== */
const validating = ref(false);
const validationIssues = ref<DataforgeEtlApi.ValidationIssue[]>([]);
const validationSnapshot = ref('');
const validationPassed = ref(false);
const issuesVisible = ref(false);
let validationSeq = 0;
const previewVisible = ref(false);
const previewGraph = ref<DataforgeEtlApi.GraphRequest>({
  nodes: [],
  edges: [],
});
const previewParameters = ref<Record<string, string>>({});
const validationCurrent = computed(() => {
  void graphRevision.value;
  return validationSnapshot.value === JSON.stringify(validationGraph());
});
function validationGraph(): DataforgeEtlApi.GraphRequest {
  return {
    ...getGraphJson(),
    environmentId: pipeline.environmentId,
    ...(pipeline.parameters?.length
      ? {
          parameterDefinitions: pipeline.parameters,
          parameters: initialParameterValues(pipeline.parameters),
        }
      : {}),
  };
}
watch(
  () => [pipeline.environmentId, pipeline.parameters],
  () => {
    if (!visible.value || hydratingGraph.value) return;
    resetDiagnostics();
    refreshSchema();
  },
  { deep: true },
);
const allIssues = computed<DataforgeEtlApi.ValidationIssue[]>(() => [
  ...validationIssues.value,
  ...(schemaResult.value?.errors ?? []).map((message) => ({
    severity: 'ERROR' as const,
    code: 'SCHEMA',
    message,
  })),
  ...(schemaResult.value?.warnings ?? []).map((message) => ({
    severity: 'WARNING' as const,
    code: 'SCHEMA',
    message,
  })),
  ...(schemaFailure.value
    ? [
        {
          severity: 'ERROR' as const,
          code: 'SCHEMA_REQUEST_FAILED',
          message: schemaFailure.value,
        },
      ]
    : []),
]);

function resetDiagnostics() {
  ++validationSeq;
  ++schemaSeq;
  if (schemaTimer) clearTimeout(schemaTimer);
  schemaTimer = undefined;
  validating.value = false;
  validationIssues.value = [];
  validationSnapshot.value = '';
  validationPassed.value = false;
  issuesVisible.value = false;
  schemaResult.value = undefined;
  schemaFailure.value = '';
  previewVisible.value = false;
  previewGraph.value = { nodes: [], edges: [] };
  previewParameters.value = {};
}

async function validateCurrentGraph() {
  const seq = ++validationSeq;
  const session = openSeq;
  const data = validationGraph();
  const snapshot = JSON.stringify(data);
  validating.value = true;
  issuesVisible.value = true;
  validationPassed.value = false;
  try {
    const result = await validateEtlPipeline(data);
    if (seq !== validationSeq || session !== openSeq || !visible.value)
      return false;
    validationIssues.value = result.issues;
    validationSnapshot.value = snapshot;
    validationPassed.value = result.valid;
    if (snapshot !== JSON.stringify(validationGraph())) return false;
    return result.valid;
  } catch {
    if (seq === validationSeq && session === openSeq && visible.value) {
      validationSnapshot.value = '';
      validationIssues.value = [
        {
          severity: 'ERROR',
          code: 'VALIDATION_REQUEST_FAILED',
          message: '校验请求失败，尚未验证通过。请重试。',
        },
      ];
    }
    return false;
  } finally {
    if (seq === validationSeq && session === openSeq) validating.value = false;
  }
}

function locateIssue(issue: DataforgeEtlApi.ValidationIssue) {
  if (!issue.nodeId) return;
  const node = graph.value?.getCellById(issue.nodeId);
  if (!node) return;
  graph.value?.centerCell(node);
  graph.value?.resetSelection([node]);
  openNodeDrawer(issue.nodeId);
}

async function openPreview() {
  if (!canPreview.value) return;
  const seq = openSeq;
  const snapshot = JSON.stringify(validationGraph());
  const selection = await runParametersRef.value?.openPreview(
    pipeline.parameters ?? [],
  );
  if (
    !selection ||
    seq !== openSeq ||
    !visible.value ||
    snapshot !== JSON.stringify(validationGraph())
  )
    return;
  previewGraph.value = JSON.parse(snapshot);
  previewParameters.value = selection.parameters;
  previewVisible.value = true;
}

async function openVersionHistory() {
  if (
    !canQuery.value ||
    running.value ||
    saving.value ||
    hydratingGraph.value ||
    restoreReloadError.value
  )
    return;
  if (pipeline.id === undefined) {
    message.warning('请先保存流程，再查看版本历史');
    return;
  }
  const seq = openSeq;
  if (dirty.value) {
    const saved = await new Promise<boolean>((resolve) =>
      Modal.confirm({
        title: '先保存当前修改',
        content:
          '版本差异以已保存草稿为基准。保存当前修改后打开版本历史，取消则保留当前画布。',
        okText: '保存并继续',
        onOk: async () => {
          try {
            resolve(await handleSave());
          } catch {
            resolve(false);
          }
        },
        onCancel: () => resolve(false),
      }),
    );
    if (!saved) return;
  }
  if (seq === openSeq && visible.value)
    await versionHistoryRef.value?.open(pipeline.id, pipeline.revision ?? 0);
}

/** 先完整读取再替换画布；恢复已受理但回读失败时保留旧画布并阻止误保存。 */
async function reloadSavedGraph() {
  if (pipeline.id === undefined) return;
  const seq = openSeq;
  hydratingGraph.value = true;
  try {
    const data = await getEtlPipeline(pipeline.id);
    if (seq !== openSeq || !visible.value) return;
    resetDiagnostics();
    Object.keys(pipeline).forEach((key) =>
      Reflect.deleteProperty(pipeline, key),
    );
    Object.assign(pipeline, data);
    renderGraph(data.nodes ?? [], data.edges ?? []);
    history.value?.clean();
    graphRevision.value += 1;
    savedSnapshot.value = currentSnapshot();
    restoreReloadError.value = '';
    refreshSchema();
  } catch {
    if (seq === openSeq && visible.value)
      restoreReloadError.value =
        '版本已恢复，但新草稿加载失败。当前画布暂保留，重新加载成功后方可编辑。';
  } finally {
    if (seq === openSeq) hydratingGraph.value = false;
  }
}

/** ==================== 保存 / 运行 ==================== */
async function handleSave(silent = false) {
  if (saving.value || !canDevelop.value) return false;
  if (
    (!silent && running.value) ||
    hydratingGraph.value ||
    restoreReloadError.value
  )
    return false;
  const seq = openSeq;
  if (!pipeline.name?.trim()) {
    message.warning('请先点击「流程配置」完善基础信息');
    configVisible.value = true;
    return false;
  }
  const { nodes, edges } = getGraphJson();
  const snapshot = currentSnapshot();
  saving.value = true;
  try {
    if (seq !== openSeq || !visible.value || snapshot !== currentSnapshot())
      return false;
    const id = await saveEtlPipeline({
      ...pipeline,
      cron: pipeline.taskType === 2 ? pipeline.cron : undefined,
      nodes,
      edges,
    });
    if (seq !== openSeq || !visible.value) return false;
    pipeline.id = id;
    const refreshedPipeline = await getEtlPipeline(id);
    if (seq !== openSeq || !visible.value) return false;
    pipeline.revision = refreshedPipeline.revision;
    savedSnapshot.value = snapshot;
    if (!silent) {
      message.success('草稿已保存，发布和运行前仍需通过校验');
    }
    emit('saved');
    return true;
  } finally {
    if (seq === openSeq) saving.value = false;
  }
}

/** 运行与状态轮询 */
const currentRecordId = ref<DataforgeEtlApi.Id>();
const currentRunRecord = ref<DataforgeEtlApi.EtlRecord>();
const runtimeBottleneck = ref('');
const currentRunState = computed(() =>
  getRunState('ETL', currentRunRecord.value ?? {}),
);
const runLog = ref('');
const logVisible = ref(false);
let pollTimer: ReturnType<typeof setTimeout> | undefined;
let pollSeq = 0;

async function handleRun() {
  if (
    running.value ||
    saving.value ||
    !canOperate.value ||
    restoreReloadError.value
  )
    return;
  stopCommands.invalidate();
  const seq = openSeq;
  running.value = true;
  try {
    if (!pipeline.publishedRevision && !(await handleSave(true))) {
      if (seq === openSeq) running.value = false;
      return;
    }
    if (seq !== openSeq || !visible.value) return;
    if (!canQuery.value) {
      message.warning(
        '查看运行参数需要 ETL 查询权限，请联系管理员授权；本次未提交运行。',
      );
      running.value = false;
      return;
    }
    const id = pipeline.id!;
    const key = String(id);
    let payload = pendingRunInputs.get(key);
    if (!payload) {
      const selection = await runParametersRef.value?.open(id);
      if (seq !== openSeq || !visible.value) return;
      if (!selection) {
        running.value = false;
        return;
      }
      payload = { taskId: id, ...selection };
      pendingRunInputs.set(key, payload);
    }
    const receipt = await runCommands.execute(
      `etl:run:${key}`,
      payload,
      runEtlCommand,
    );
    if (receipt) {
      pendingRunInputs.delete(key);
      unconfirmedRuns.value = unconfirmedRuns.value.filter(
        (item) => item !== key,
      );
    }
    if (seq !== openSeq || !visible.value) return;
    if (!receipt) {
      running.value = false;
      return;
    }
    currentRecordId.value = receipt.recordId;
    currentRunRecord.value = {
      id: receipt.recordId,
      runStatus: receipt.runStatus,
      stopRequested: receipt.stopRequested,
    };
    logVisible.value = true;
    runLog.value = '';
    clearNodeRuntimeData();
    message.success('已提交执行');
    startPolling(receipt.recordId);
  } catch {
    if (seq === openSeq && visible.value) {
      running.value = false;
      if (
        pipeline.id !== undefined &&
        !unconfirmedRuns.value.includes(String(pipeline.id))
      )
        unconfirmedRuns.value.push(String(pipeline.id));
    }
  }
}

function startPolling(recordId: DataforgeEtlApi.Id) {
  stopPolling();
  const seq = pollSeq;
  const refresh = async () => {
    try {
      const [record, nodeRecords] = await Promise.all([
        getEtlRecordLog(recordId),
        getEtlNodeRecords(recordId),
      ]);
      if (seq !== pollSeq || !visible.value) return;
      currentRunRecord.value = {
        ...record,
        stopRequested:
          record.stopRequested || currentRunRecord.value?.stopRequested,
      };
      runLog.value = record.log ?? '';
      // 浅合并：子流程多记录时行数为 undefined，需覆盖上一轮可能透传的旧值（深合并会跳过 undefined）
      for (const nodeRecord of groupNodeRunStatuses(
        withLiveCounts(nodeRecords ?? [], record.runtimeMetrics),
      ))
        graph.value?.getCellById(nodeRecord.id)?.setData(
          {
            runStatus: nodeRecord.status,
            readCount: nodeRecord.readCount,
            writeCount: nodeRecord.writeCount,
          },
          { deep: false },
        );
      try {
        applyRuntimeOverlay(record.runtimeMetrics, nodeRecords ?? []);
      } catch {
        // 观测展示失败不能阻断运行状态与结束判定
      }
      const state = getRunState('ETL', currentRunRecord.value);
      if (!state.shouldPoll && [3, 4, 5].includes(record.runStatus ?? 0)) {
        running.value = false;
        stopPolling();
        if (record.runStatus === 3)
          message.success('流程执行成功，请查看质量验收结果');
        else if (record.runStatus === 5) message.warning('流程已确认停止');
        else message.error('流程执行失败，请查看日志');
      } else {
        pollTimer = setTimeout(refresh, state.canResolve ? 5000 : 2000);
      }
    } catch {
      if (seq === pollSeq && visible.value)
        pollTimer = setTimeout(refresh, 5000);
    }
  };
  void refresh();
}
function stopPolling() {
  ++pollSeq;
  if (pollTimer) clearTimeout(pollTimer);
  pollTimer = undefined;
}

/** 新一轮运行前清除上次运行遗留的节点运行态（状态、读写行数），避免旧值在首轮轮询前残留 */
function clearNodeRuntimeData() {
  for (const node of graph.value?.getNodes() ?? []) {
    const {
      runStatus: _runStatus,
      readCount: _readCount,
      writeCount: _writeCount,
      ...rest
    } = node.getData() ?? {};
    node.setData(rest, { overwrite: true });
  }
  for (const edge of graph.value?.getEdges() ?? [])
    if (edge.getLabels().length > 0) edge.setLabels([]);
  runtimeBottleneck.value = '';
}

/** 连线展示运行时队列与上游等待，工具栏提示瓶颈；标签不写入连线数据 */
function applyRuntimeOverlay(
  metrics: DataforgeEtlApi.RuntimeMetrics | null | undefined,
  records: DataforgeEtlApi.NodeRecord[],
) {
  const summaries = summarizeRuntimeEdges(metrics, nodeNameMap(records));
  const bottleneck = findBottleneck(summaries);
  runtimeBottleneck.value = bottleneck
    ? `瓶颈：下游【${bottleneck.targetName}】处理较慢`
    : '';
  const mapped = mapEdgesToDesign(summaries, records);
  for (const edge of graph.value?.getEdges() ?? []) {
    const source = edge.getSourceCellId();
    const target = edge.getTargetCellId();
    const summary =
      source && target ? mapped.get(edgeKey(source, target)) : undefined;
    if (summary)
      edge.setLabels([
        runtimeEdgeLabel(
          edgeRuntimeText(summary),
          PRESSURE_COLORS[summary.level],
        ),
      ]);
    else if (edge.getLabels().length > 0) edge.setLabels([]);
  }
}

async function handleStop() {
  if (
    !canOperate.value ||
    !currentRecordId.value ||
    !currentRunState.value.canStop
  ) {
    return;
  }
  const recordId = currentRecordId.value;
  const seq = pollSeq;
  const receipt = await stopCommands.execute(
    `etl:stop:${recordId}`,
    { recordId },
    stopEtlCommand,
  );
  if (
    !receipt ||
    seq !== pollSeq ||
    String(currentRecordId.value) !== String(recordId) ||
    String(receipt.recordId) !== String(recordId)
  )
    return;
  // 回执是受理快照，不覆盖更新的轮询状态；停止意图只属于同一运行。
  currentRunRecord.value = {
    ...currentRunRecord.value,
    stopRequested:
      currentRunRecord.value?.stopRequested || receipt.stopRequested,
  };
  message.success('取消请求已受理，执行终止待确认');
}

const getTitle = computed(() =>
  pipeline.id ? `流程设计 - ${pipeline.name ?? ''}` : '新建流程',
);
</script>

<template>
  <Drawer
    :closable="true"
    :footer="null"
    :open="visible"
    :push="false"
    :title="getTitle"
    destroy-on-hidden
    placement="bottom"
    size="100%"
    @close="handleClose"
  >
    <RunParametersDialog ref="runParametersRef" />
    <Alert
      v-if="unconfirmedRuns.length"
      type="warning"
      message="上次运行受理结果尚未确认，再次运行将使用原参数和原请求编号。"
      class="mb-2"
    >
      <template #action>
        <Button
          :disabled="runCommands.busy.value"
          @click="resetUnconfirmedRuns"
        >
          <template #icon><IconifyIcon icon="lucide:settings" /></template>
          核对记录后重新配置
        </Button>
      </template>
    </Alert>
    <div class="designer">
      <!-- 左侧工具树 -->
      <aside class="designer__aside">
        <Input
          v-model:value="toolKeyword"
          allow-clear
          class="mb-2"
          placeholder="搜索组件"
          size="small"
        />
        <div class="designer__tools subtle-tree-scrollbar">
          <div v-for="group in toolGroups" :key="group.id" class="tool-group">
            <div class="tool-group__title" @click="toggleGroup(group.id)">
              <IconifyIcon
                :icon="
                  collapsedGroups[group.id]
                    ? 'lucide:chevron-right'
                    : 'lucide:chevron-down'
                "
                class="text-muted-foreground size-3.5"
              />
              <span>{{ group.name }}</span>
              <span class="tool-group__count">
                {{ group.children.length }}
              </span>
            </div>
            <div v-show="!collapsedGroups[group.id]" class="tool-group__list">
              <div
                v-for="tool in group.children"
                :key="tool.id"
                :title="tool.description"
                class="tool-card"
                @mousedown="handleToolMouseDown(tool, $event)"
              >
                <span
                  class="tool-card__icon"
                  :style="getCategoryStyle(tool.type)"
                >
                  <IconifyIcon
                    :icon="tool.icon || 'lucide:box'"
                    class="size-4"
                  />
                </span>
                <span class="tool-card__name">{{ tool.name }}</span>
              </div>
            </div>
          </div>
        </div>
      </aside>

      <!-- 右侧画布区 -->
      <div class="designer__main">
        <div class="designer__toolbar">
          <Button size="small" @click="configVisible = true"
            ><template #icon><IconifyIcon icon="lucide:settings" /></template
            >流程配置</Button
          >
          <Button
            v-if="canQuery"
            size="small"
            :disabled="hydratingGraph"
            @click="templateLibraryRef?.open()"
          >
            <template #icon
              ><IconifyIcon icon="lucide:layout-template"
            /></template>
            团队模板
          </Button>
          <Button
            v-if="canQuery && canDevelop"
            size="small"
            :disabled="!canEditGraph"
            @click="saveAsTemplate"
          >
            <template #icon><IconifyIcon icon="lucide:file-plus" /></template>
            保存为模板
          </Button>
          <Button
            v-if="canQuery"
            :disabled="
              running || saving || hydratingGraph || !!restoreReloadError
            "
            size="small"
            @click="openVersionHistory"
          >
            <template #icon><IconifyIcon icon="lucide:history" /></template>
            版本历史
          </Button>
          <Tag v-if="hydratingGraph">正在载入流程…</Tag>
          <template v-if="restoreReloadError">
            <span class="text-destructive text-xs">{{
              restoreReloadError
            }}</span
            ><Button
              :loading="hydratingGraph"
              size="small"
              @click="reloadSavedGraph"
            >
              <template #icon
                ><IconifyIcon icon="lucide:refresh-cw"
              /></template>
              重新加载草稿
            </Button>
          </template>
          <Button
            :disabled="!canEditGraph || !undoAvailable"
            size="small"
            title="撤销 Ctrl/Cmd+Z"
            @click="handleHistory('undo')"
          >
            <template #icon><IconifyIcon icon="lucide:undo-2" /></template>
            撤销
          </Button>
          <Button
            :disabled="!canEditGraph || !redoAvailable"
            size="small"
            title="重做 Ctrl/Cmd+Shift+Z"
            @click="handleHistory('redo')"
          >
            <template #icon><IconifyIcon icon="lucide:redo-2" /></template>
            重做
          </Button>
          <Button
            :disabled="!canEditGraph"
            size="small"
            title="从左到右整理画布，可撤销"
            @click="handleAutoLayout"
          >
            <template #icon><IconifyIcon icon="lucide:network" /></template>
            自动布局
          </Button>
          <Button
            v-if="canDevelop"
            :loading="validating"
            :disabled="saving || running || hydratingGraph"
            size="small"
            @click="validateCurrentGraph"
          >
            <template #icon><IconifyIcon icon="lucide:list-checks" /></template>
            校验
          </Button>
          <Button v-if="canPreview" size="small" @click="openPreview">
            <template #icon><IconifyIcon icon="lucide:eye" /></template>
            样本预览
          </Button>
          <Button size="small" @click="issuesVisible = !issuesVisible">
            <template #icon
              ><IconifyIcon icon="lucide:triangle-alert"
            /></template>
            问题 {{ allIssues.length }}
          </Button>
          <Button
            v-if="canDevelop"
            v-access:code="'dataforge:etl:save'"
            :loading="saving"
            :disabled="!canEditGraph"
            size="small"
            type="primary"
            @click="handleSave()"
          >
            <template #icon><IconifyIcon icon="lucide:save" /></template>
            保存
          </Button>
          <Button
            v-if="canOperate"
            v-access:code="'dataforge:etl:run'"
            :disabled="
              running ||
              saving ||
              hydratingGraph ||
              !!restoreReloadError ||
              runCommands.busy.value
            "
            size="small"
            type="primary"
            ghost
            @click="handleRun"
          >
            <template #icon><IconifyIcon icon="lucide:play" /></template>
            {{ pipeline.publishedRevision ? '运行发布版' : '运行开发草稿' }}
          </Button>
          <Popconfirm
            v-if="running && canOperate && currentRunState.canStop"
            title="确认停止当前执行吗？"
            @confirm="handleStop"
          >
            <Button :loading="stopCommands.busy.value" danger size="small">
              <template #icon><IconifyIcon icon="lucide:square" /></template>
              停止
            </Button>
          </Popconfirm>
          <Tag v-if="currentRunRecord" :color="currentRunState.color">
            {{ currentRunState.label }}
          </Tag>
          <Tag v-if="runtimeBottleneck" color="warning">
            {{ runtimeBottleneck }}
          </Tag>
          <Button size="small" @click="logVisible = !logVisible">
            <template #icon><IconifyIcon icon="lucide:scroll-text" /></template>
            {{ logVisible ? '隐藏日志' : '显示日志' }}
          </Button>
          <Tooltip title="重置视角">
            <Button
              class="flex items-center justify-center"
              size="small"
              @click="resetView"
            >
              <IconifyIcon class="size-4" icon="lucide:locate-fixed" />
            </Button>
          </Tooltip>
          <span class="text-muted-foreground ml-2 text-xs">
            草稿 r{{ pipeline.revision ?? 0 }} · 发布 r{{
              pipeline.publishedRevision ?? '未发布'
            }}。 双击编辑，Shift+拖拽框选，Delete 删除，Ctrl/Cmd+S 保存
          </span>
        </div>
        <div class="designer__canvas-wrap">
          <div
            ref="containerRef"
            :inert="!canEditGraph"
            class="designer__canvas"
          ></div>
          <section
            v-if="issuesVisible"
            class="designer__issues"
            aria-label="流程校验问题"
          >
            <div class="flex items-center justify-between gap-2">
              <strong>校验与字段问题</strong>
              <span v-if="validating">正在校验…</span>
              <span v-else-if="validationSnapshot && !validationCurrent"
                >画布已修改，请重新校验</span
              >
              <span v-else-if="validationCurrent && validationPassed"
                >流程校验通过</span
              >
              <Button size="small" type="text" @click="issuesVisible = false">
                <template #icon
                  ><IconifyIcon icon="lucide:chevron-up"
                /></template>
                收起
              </Button>
            </div>
            <p
              v-if="!allIssues.length && !validationSnapshot && !validating"
              class="text-muted-foreground"
            >
              尚未执行流程校验
            </p>
            <button
              v-for="(issue, index) in allIssues"
              :key="`${issue.code}-${index}`"
              class="designer__issue"
              :disabled="!issue.nodeId"
              @click="locateIssue(issue)"
            >
              <Tag :color="issue.severity === 'ERROR' ? 'error' : 'warning'">
                {{ issue.severity === 'ERROR' ? '错误' : '警告' }}
              </Tag>
              <span>{{ issue.message }}</span>
              <span v-if="issue.field" class="text-muted-foreground">{{
                issue.field
              }}</span>
              <span v-if="issue.nodeId" class="text-primary">定位节点</span>
            </button>
          </section>
          <div v-if="logVisible" class="designer__log">
            <pre>{{ runLog || '（暂无日志）' }}</pre>
          </div>
        </div>
      </div>
    </div>

    <PreviewDialog
      :open="previewVisible"
      :graph="previewGraph"
      :parameters="previewParameters"
      :initial-target-node-id="currentNodeId"
      @close="previewVisible = false"
    />
    <TemplateLibrary
      ref="templateLibraryRef"
      :can-edit="canEditGraph"
      @apply="applyTemplate"
    />
    <VersionHistory
      ref="versionHistoryRef"
      :can-restore="canEditGraph && canQuery"
      @restored="reloadSavedGraph"
    />

    <!-- 流程配置弹窗 -->
    <Modal
      v-model:open="configVisible"
      title="流程配置"
      width="640px"
      @ok="handleConfigOk"
    >
      <div class="flex flex-col gap-3 py-1" :inert="!canEditGraph">
        <EnvironmentField
          v-model:environment-id="pipeline.environmentId"
          :disabled="!canEditGraph"
        />
        <QualityTaskField v-model:quality-task-id="pipeline.qualityTaskId" />
        <div class="form-item">
          <label class="form-item__label form-item__label--required">
            流程名称
          </label>
          <Input
            v-model:value="pipeline.name"
            allow-clear
            placeholder="如：用户数据清洗入仓"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label form-item__label--required">
            调度类型
          </label>
          <RadioGroup
            v-model:value="pipeline.taskType"
            :options="TASK_TYPE_OPTIONS"
            option-type="button"
          />
          <div class="form-item__tip">
            发布冻结当前草稿；一次性任务手动运行，周期任务启用后按已发布 Cron
            调度
          </div>
        </div>
        <div v-if="pipeline.taskType === 2" class="form-item">
          <label class="form-item__label form-item__label--required">
            Cron 表达式
          </label>
          <CronTab
            v-model="pipeline.cron"
            placeholder="例如：0 0 2 * * ?（每天凌晨 2 点）"
          />
        </div>
        <div class="form-item">
          <label class="form-item__label">描述</label>
          <TextArea v-model:value="pipeline.description" :rows="2" />
        </div>
        <div class="form-item">
          <label class="form-item__label"
            >流程参数（节点配置中用 ${params.名称} 引用）</label
          >
          <ParameterEditor v-model="pipeline.parameters" />
        </div>
      </div>
    </Modal>

    <!-- 节点配置抽屉 -->
    <Drawer
      v-model:open="nodeDrawerVisible"
      :title="`节点配置 - ${nodeForm.toolName ?? ''}`"
      :size="nodeForm.toolCode === 'rest-api' ? '1040px' : '560px'"
      destroy-on-hidden
    >
      <div class="flex flex-col gap-3" :inert="!canEditGraph">
        <div class="form-item">
          <label class="form-item__label form-item__label--required">
            节点名称
          </label>
          <Input v-model:value="nodeForm.name" allow-clear />
        </div>
        <div class="flex gap-4">
          <div class="form-item flex-1">
            <label class="form-item__label">超时（分钟，0 不限）</label>
            <InputNumber
              v-model:value="nodeForm.overTimes"
              :min="0"
              class="w-full"
            />
          </div>
          <div class="form-item flex-1">
            <label class="form-item__label">失败后流程继续</label>
            <div class="pt-1">
              <Checkbox
                :checked="nodeForm.failGoOn === 1"
                @update:checked="
                  (v: boolean) => (nodeForm.failGoOn = v ? 1 : 0)
                "
              >
                失败不阻塞控制下游
              </Checkbox>
            </div>
          </div>
        </div>
        <div v-if="currentInputFields.length > 0" class="form-item">
          <label class="form-item__label">上游字段</label>
          <div class="designer__fields">
            <span
              v-for="field in currentInputFields"
              :key="field.name"
              class="designer__field"
            >
              {{ field.name }}
              <FieldContractSummary :field="field" />
            </span>
          </div>
        </div>
        <div class="designer__divider">组件配置</div>
        <component
          :is="currentEditor"
          ref="nodeEditor"
          :quality-sources="currentQualitySources"
          v-if="currentEditor"
          :key="currentNodeId"
          :config="nodeConfig"
          :disabled="!canEditGraph"
          :downstream-nodes="currentDownstreams"
          :input-fields="currentInputFields"
          :node-name="nodeForm.name"
        />
        <div class="mt-4 flex justify-between">
          <Popconfirm title="确认删除该节点吗？" @confirm="handleNodeDelete">
            <Button danger
              ><template #icon><IconifyIcon icon="lucide:trash-2" /></template
              >删除节点</Button
            >
          </Popconfirm>
          <div class="flex gap-2">
            <Button @click="nodeDrawerVisible = false">取消</Button>
            <Button type="primary" @click="handleNodeSubmit">确定</Button>
          </div>
        </div>
      </div>
    </Drawer>
  </Drawer>
</template>

<style scoped>
.designer {
  display: flex;
  gap: 10px;
  height: calc(100vh - 108px);
}

.designer__aside {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  width: 220px;
  padding: 10px;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.designer__tools {
  flex: 1;
  overflow-y: auto;
}

.tool-group {
  margin-bottom: 4px;
}

.tool-group__title {
  display: flex;
  gap: 4px;
  align-items: center;
  padding: 6px 4px;
  font-size: 13px;
  font-weight: 600;
  cursor: pointer;
  user-select: none;
}

.tool-group__count {
  margin-left: auto;
  font-size: 12px;
  font-weight: 400;
  color: hsl(var(--muted-foreground));
}

.tool-group__list {
  display: flex;
  flex-direction: column;
  gap: 6px;
  padding: 2px 2px 10px;
}

.tool-card {
  display: flex;
  gap: 8px;
  align-items: center;
  padding: 7px 10px;
  cursor: grab;
  user-select: none;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}

.tool-card:hover {
  border-color: #5f95ff;
  box-shadow: 0 2px 8px rgb(95 149 255 / 15%);
}

.tool-card__icon {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 26px;
  height: 26px;
  border-radius: 6px;
}

.tool-card__name {
  font-size: 13px;
}

.designer__main {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-width: 0;
}

.designer__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  padding: 8px 10px;
  margin-bottom: 8px;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.designer__issues {
  max-height: 240px;
  padding: 10px 12px;
  overflow: auto;
  background: hsl(var(--card));
  border-top: 1px solid hsl(var(--border));
}

.designer__issue {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  padding: 6px 0;
  text-align: left;
}

.designer__issue:not(:disabled):hover {
  background: hsl(var(--muted));
}

.designer__canvas-wrap {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: 8px;
}

.designer__canvas {
  flex: 1;
  min-height: 0;
  background: #fff;
}

.designer__log {
  height: 30%;
  min-height: 120px;
  padding: 8px 12px;
  overflow: auto;
  background: #0f172a;
  border-top: 1px solid hsl(var(--border));
}

.designer__log pre {
  margin: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, Consolas, monospace;
  font-size: 12px;
  line-height: 1.6;
  color: #e2e8f0;
  word-break: break-all;
  white-space: pre-wrap;
}

.designer__fields {
  display: flex;
  flex-wrap: wrap;
  gap: 6px;
  max-height: 90px;
  padding: 8px;
  overflow-y: auto;
  background: hsl(var(--muted) / 40%);
  border-radius: 6px;
}

.designer__field {
  padding: 2px 8px;
  font-size: 12px;
  background: hsl(var(--card));
  border: 1px solid hsl(var(--border));
  border-radius: 4px;
}

.designer__field em {
  margin-left: 4px;
  font-style: normal;
  color: hsl(var(--muted-foreground));
}

.designer__divider {
  padding-left: 8px;
  margin-top: 4px;
  font-size: 13px;
  font-weight: 600;
  border-left: 3px solid hsl(var(--primary));
}

.form-item {
  display: flex;
  flex-direction: column;
  gap: 6px;
}

.form-item__label {
  font-size: 14px;
  font-weight: 500;
}

.form-item__label--required::before {
  margin-right: 4px;
  color: hsl(var(--destructive));
  content: '*';
}

.form-item__tip {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}
</style>

<style>
/* X6 自定义节点卡片样式（非 scoped，注入画布 DOM） */
.etl-card {
  position: relative;
  box-sizing: border-box;
  display: flex;
  gap: 10px;
  align-items: center;
  padding: 0 14px 0 16px;
  cursor: move;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 10px;
  box-shadow: 0 1px 3px rgb(15 23 42 / 6%);
  transition:
    border-color 0.2s,
    box-shadow 0.2s;
}

.etl-card:hover {
  border-color: #c7d2fe;
  box-shadow: 0 3px 10px rgb(15 23 42 / 10%);
}

.etl-card--selected {
  border-color: #5f95ff;
  box-shadow: 0 0 0 2px rgb(95 149 255 / 25%);
}

.etl-card__accent {
  position: absolute;
  top: 10px;
  bottom: 10px;
  left: 0;
  width: 4px;
  border-radius: 0 4px 4px 0;
}

.etl-card__icon {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 32px;
  height: 32px;
  font-size: 13px;
  font-weight: 600;
  border-radius: 8px;
}

.etl-card__meta {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 2px;
  min-width: 0;
}

.etl-card__name {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 13px;
  font-weight: 600;
  color: #0f172a;
  white-space: nowrap;
}

.etl-card__tool {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 12px;
  color: #94a3b8;
  white-space: nowrap;
}

/* 运行时读写行数（轮询写入的临时字段，不持久化） */
.etl-card__counts {
  overflow: hidden;
  text-overflow: ellipsis;
  font-size: 11px;
  line-height: 1.3;
  color: #94a3b8;
  white-space: nowrap;
}

.etl-card__status {
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: center;
  width: 18px;
  height: 18px;
}

.etl-card__spin {
  box-sizing: border-box;
  width: 14px;
  height: 14px;
  border: 2px solid #bfdbfe;
  border-top-color: #3b82f6;
  border-radius: 50%;
  animation: etl-card-spin 0.9s linear infinite;
}

.etl-card__close {
  position: absolute;
  top: 4px;
  right: 4px;
  display: none;
  align-items: center;
  justify-content: center;
  width: 16px;
  height: 16px;
  font-size: 10px;
  line-height: 1;
  color: #94a3b8;
  cursor: pointer;
  background: #fff;
  border: 1px solid #e2e8f0;
  border-radius: 50%;
}

.etl-card:hover .etl-card__close {
  display: flex;
}

.etl-card__close:hover {
  color: #ef4444;
  border-color: #fecaca;
}

@keyframes etl-card-spin {
  to {
    transform: rotate(360deg);
  }
}
</style>
