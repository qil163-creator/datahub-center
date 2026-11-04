<script setup lang="ts">
import type { Node } from '@antv/x6';

import type {
  IntegrationComponent,
  IntegrationFlow,
  IntegrationFlowNode,
} from './component-catalog';

import type { IntegrationApi as Api } from '#/api/dataforge/integration';
import type { IntegrationComponentCapabilities } from '#/api/dataforge/integration/component-capabilities';

import {
  computed,
  nextTick,
  onBeforeUnmount,
  onMounted,
  ref,
  shallowRef,
  watch,
} from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Graph } from '@antv/x6';
import { Dnd } from '@antv/x6-plugin-dnd';
import { Export } from '@antv/x6-plugin-export';
import { Keyboard } from '@antv/x6-plugin-keyboard';
import { Selection } from '@antv/x6-plugin-selection';
import { Snapline } from '@antv/x6-plugin-snapline';
import {
  Alert,
  Button,
  ConfigProvider,
  message,
  Modal,
  Spin,
  Tooltip,
} from 'antdv-next';

import {
  checkIntegrationFlow,
  getIntegration,
  getIntegrationCategories,
  getIntegrationOwners,
  resolveIntegrationSchema,
  saveIntegrationFlow,
} from '#/api/dataforge/integration';
import { getIntegrationComponentCapabilities } from '#/api/dataforge/integration/component-capabilities';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  createsCycle,
  getSourcePort,
  getVisualSourcePort,
  serializeEdgePort,
} from '../etl/pipeline/graph-contract';
import { getDefaultNodeConfig } from '../etl/pipeline/node-editors/registry';
import { getNodePorts } from '../etl/pipeline/x6-config';
import {
  componentCapability,
  componentGroups,
  componentOf,
  componentSupportsEngine,
  integrationComponents,
  newIntegrationNodeDefaults,
  nodeComponent,
  nodeConfiguration,
  parseIntegrationFlow,
} from './component-catalog';
import {
  inferIntegrationInputFields,
  integrationPortFields,
} from './field-hints';
import NodeConfigurationEditor from './modules/node-configuration-editor.vue';
import NodeFieldsModal from './modules/node-fields-modal.vue';
import ReferenceIcon from './modules/reference-icon.vue';
import ReferenceNodeBasics from './modules/reference-node-basics.vue';
import TaskForm from './modules/task-form.vue';
import { integrationReferenceTheme } from './reference-theme';

import './designer.css';

defineOptions({ name: 'DataforgeIntegrationDesigner' });
const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const can = (action: string) =>
  hasAccessByCodes([`dataforge:integration:${action}`]);
const task = ref<Api.Task>();
const loading = ref(false);
const saving = ref(false);
const checking = ref(false);
const loadError = ref('');
const canvas = ref<HTMLElement>();
const graph = shallowRef<Graph>();
const dnd = shallowRef<Dnd>();
const graphVersion = ref(0);
const saved = ref('');
const original = ref<IntegrationFlow>({ nodes: [], edges: [] });
const collapsed = ref<string[]>([]);
const nodeOpen = ref(false);
const editingId = ref('');
const editingName = ref('');
const editingDescription = ref('');
const editingCode = ref('');
const editingConfig = ref<Record<string, any>>({});
const nodeEditor = ref<{
  canGetRules?: boolean;
  canMergeFields?: boolean;
  openFieldMerge?: () => void;
  openRulePicker?: () => void;
  stepNav?: { last: number; next: () => void; prev: () => void; step: number };
  validate?: () => boolean;
}>();
const nodeChecking = ref(false);
const nodeCheckValid = ref(false);
const nodeCheckMessages = ref<string[]>([]);
const context = ref<{ id: string; kind?: 'edge'; x: number; y: number }>();
const fieldsOpen = ref(false);
const fieldsDirection = ref<'input' | 'output'>('output');
const fieldsNodeId = ref('');
const fieldsStepName = ref('');
const fieldsLoading = ref(false);
const fieldsError = ref('');
const fieldsSchema = ref<Api.NodeSchema>();
const resolvedSchema = shallowRef<Api.SchemaResult>();
const schemaSnapshot = ref('');
const checks = ref<string[]>([]);
const checked = ref(false);
const configOpen = ref(false);
const owners = ref<Api.Owner[]>([]);
const categories = ref<Api.Category[]>([]);
const capabilities = shallowRef<IntegrationComponentCapabilities>();
const capabilitiesLoading = ref(false);
const capabilitiesError = ref('');
const identity = () =>
  JSON.stringify([
    route.params.id,
    access.accessToken,
    access.accessCodes,
    user.userInfo?.userId,
  ]);
const request = useObjectRequest(identity, clear);
const saveRequest = useObjectRequest(identity);
const checkRequest = useObjectRequest(identity);
const nodeCheckRequest = useObjectRequest(
  () =>
    JSON.stringify([
      identity(),
      nodeOpen.value,
      editingId.value,
      task.value?.revision,
      editingConfig.value,
      editingName.value,
      editingDescription.value,
      graphVersion.value,
    ]),
  () => {
    nodeChecking.value = false;
    nodeCheckMessages.value = [];
  },
);
const refreshRequest = useObjectRequest(identity);
const fieldsRequest = useObjectRequest(() =>
  JSON.stringify([
    identity(),
    fieldsOpen.value,
    fieldsNodeId.value,
    fieldsDirection.value,
  ]),
);
const hintsRequest = useObjectRequest(() =>
  JSON.stringify([identity(), nodeOpen.value, editingId.value]),
);
const detailRequest = useObjectRequest(
  () => `${configOpen.value}/${route.params.id}`,
);
const capabilitiesRequest = useObjectRequest(
  () =>
    JSON.stringify([
      identity(),
      task.value?.id,
      task.value?.engine,
      task.value?.mode,
    ]),
  clearCapabilities,
);
const capabilityLabels = {
  SUPPORTED: '可用',
  PARTIAL: '可用',
  UNAVAILABLE: '不可用',
} as const;
function componentState(component: IntegrationComponent) {
  return componentCapability(
    component,
    task.value?.engine,
    task.value?.mode,
    capabilities.value,
  );
}
function componentAvailable(component: IntegrationComponent) {
  return componentSupportsEngine(
    component,
    task.value?.engine,
    task.value?.mode,
    capabilities.value,
  );
}
function componentTitle(component: IntegrationComponent) {
  const state = componentState(component);
  return `${component.name} · ${capabilityLabels[state.status]}：${state.reason}`;
}
const capabilitySummary = computed(() => {
  const states = integrationComponents.map(
    (component) => componentState(component).status,
  );
  return `可用 ${states.filter((status) => status !== 'UNAVAILABLE').length} · 不可用 ${states.filter((status) => status === 'UNAVAILABLE').length}`;
});
const editingCapability = computed(() =>
  currentComponent.value ? componentState(currentComponent.value) : undefined,
);
const editable = computed(
  () =>
    can('update') &&
    task.value?.status === 0 &&
    !task.value?.activeRunning &&
    !saving.value &&
    !loading.value &&
    !loadError.value,
);
const currentComponent = computed(() => componentOf(editingCode.value));
const nodeHelp = computed(() => ({
  'field-split': '将一个字段按分隔符或正则表达式拆分，按顺序写入拆分字段。',
  'dedup-records': '按选定字段识别重复记录，可逐字段设置是否忽略大小写。',
  'row-to-column': '将多列数据拆分成多行，每行保存字段对应的键和值。',
  'column-to-row': '将多条键值对数据转化为宽表，每个键（key）对应一列。',
  'data-encrypt': '将选定字段加密后输出到密文字段。',
  'data-decrypt': '将选定字段解密后输出到明文字段。',
  'id-generator': '将自动生成的递增序列或 UUID 输出到指定字段。',
} as Record<string, string>)[editingCode.value]);
const steppedComponent = computed(() =>
  ['data-condition', 'multi-join', 'read-webservice', 'rest-api'].includes(
    editingCode.value,
  ),
);
const editorDescription = computed(() =>
  [
    'custom-token',
    'data-decrypt',
    'data-encrypt',
    'field-derive',
    'id-generator',
    'read-webservice',
  ].includes(editingCode.value),
);
const advancedDescription = computed(() =>
  [
    'add-constant',
    'calculator',
    'column-to-row',
    'data-condition',
    'field-split',
    'multi-join',
    'number-range',
    'rest-api',
    'row-to-column',
    'value-map',
  ].includes(editingCode.value),
);
const upstreamHint = computed(() => {
  void graphVersion.value;
  if (schemaSnapshot.value === snapshot()) {
    const schema = resolvedSchema.value?.nodes?.[editingId.value];
    if (schema)
      return {
        fields: schema.inputFields ?? [],
        complete: schema.complete === true,
      };
  }
  return inferIntegrationInputFields(flow(), editingId.value);
});
const upstreamFields = computed(() =>
  upstreamHint.value.fields.map((field) => ({
    ...field,
    dataType: field.dataType ?? undefined,
  })),
);
const upstreamNodes = computed(() => {
  void graphVersion.value;
  const currentFlow = flow();
  const ids = [
    ...new Set(
      currentFlow.edges
        .filter((edge) => edge.targetNodeId === editingId.value)
        .map((edge) => edge.sourceNodeId),
    ),
  ];
  return ids.map((id) => {
    const source = currentFlow.nodes.find((node) => node.id === id);
    const incoming = currentFlow.edges.filter(
      (edge) =>
        edge.targetNodeId === editingId.value && edge.sourceNodeId === id,
    );
    const fields =
      schemaSnapshot.value === snapshot() && incoming.length === 1
        ? integrationPortFields(resolvedSchema.value?.nodes?.[id], incoming[0]!)
        : undefined;
    return {
      nodeNo: id,
      name: source?.properties.name ?? id,
      fields: fields?.map((field) => ({
        ...field,
        dataType: field.dataType ?? undefined,
      })),
    };
  });
});
const contextComponent = computed(() => {
  const node = context.value && graph.value?.getCellById(context.value.id);
  return node?.isNode()
    ? nodeComponent(node.getData<IntegrationFlowNode>())
    : undefined;
});
const previewFields = computed(() =>
  (
    (fieldsDirection.value === 'input'
      ? fieldsSchema.value?.inputFields
      : fieldsSchema.value?.outputFields) ?? []
  ).map((field) => {
    const decimal = /^(?:decimal|numeric|number|bignumber)(?:\b|\()/iu.test(
      field.dataType ?? '',
    );
    return {
      ...field,
      length: field.length ?? (decimal ? field.precision : undefined),
      precision: decimal ? field.scale : undefined,
    };
  }),
);

const downstreamNodes = computed(() => {
  void graphVersion.value;
  return (graph.value?.getOutgoingEdges(editingId.value) ?? []).map((edge) => {
    const target = graph.value?.getCellById(edge.getTargetCellId());
    return {
      targetId: edge.getTargetCellId(),
      targetName:
        target?.getData<IntegrationFlowNode>()?.properties.name ??
        edge.getTargetCellId(),
    };
  });
});

function clear() {
  dnd.value?.dispose();
  graph.value?.dispose();
  graph.value = undefined;
  dnd.value = undefined;
  task.value = undefined;
  loading.value = false;
  saving.value = false;
  checking.value = false;
  loadError.value = '';
  nodeOpen.value = false;
  configOpen.value = false;
  context.value = undefined;
  fieldsOpen.value = false;
  fieldsNodeId.value = '';
  fieldsStepName.value = '';
  fieldsSchema.value = undefined;
  fieldsError.value = '';
  fieldsLoading.value = false;
  resolvedSchema.value = undefined;
  schemaSnapshot.value = '';
  checks.value = [];
  checked.value = false;
}
function clearCapabilities() {
  capabilities.value = undefined;
  capabilitiesLoading.value = false;
  capabilitiesError.value = '';
}
async function loadCapabilities() {
  const current = task.value;
  if (!current || !can('query')) return;
  const ticket = capabilitiesRequest.begin();
  clearCapabilities();
  capabilitiesLoading.value = true;
  try {
    const result = await getIntegrationComponentCapabilities({
      engine: current.engine,
      mode: current.mode,
    });
    if (!ticket()) return;
    if (
      result.engine !== current.engine ||
      result.mode !== current.mode ||
      !Array.isArray(result.components)
    )
      throw new Error('组件能力响应不匹配');
    capabilities.value = result;
  } catch {
    if (ticket()) capabilitiesError.value = '组件可用性加载失败，请重试。';
  } finally {
    if (ticket()) capabilitiesLoading.value = false;
  }
}
function flow(): IntegrationFlow {
  const nodes =
    graph.value?.getNodes().map((cell) => {
      const position = cell.position();
      const data = cell.getData<IntegrationFlowNode>();
      return { ...data, id: cell.id, x: position.x + 36, y: position.y + 25 };
    }) ?? [];
  const edges =
    graph.value?.getEdges().map((cell) => {
      const data = cell.getData();
      const sourcePort = serializeEdgePort(
        cell.getSourcePortId(),
        data?.properties,
      );
      return {
        ...data,
        id: cell.id,
        sourceNodeId: cell.getSourceCellId(),
        targetNodeId: cell.getTargetCellId(),
        sourcePort,
        targetPort: cell.getTargetPortId() ?? 'in',
        properties: { ...data?.properties, outputPort: sourcePort },
      };
    }) ?? [];
  return { ...original.value, nodes, edges };
}
function snapshot() {
  return JSON.stringify(flow());
}
const dirty = computed(() => {
  void graphVersion.value;
  return !!task.value && !!graph.value && saved.value !== snapshot();
});
function discard() {
  if (!dirty.value) return Promise.resolve(true);
  return new Promise<boolean>((resolve) =>
    Modal.confirm({
      title: '流程有未保存的修改',
      content: '确认丢弃未保存的节点与配置？',
      okText: '确认',
      cancelText: '取消',
      onOk: () => resolve(true),
      onCancel: () => resolve(false),
    }),
  );
}
onBeforeRouteLeave(discard);
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) event.preventDefault();
}
function dismissContext(event: Event) {
  if (
    !(event.target instanceof Element) ||
    !event.target.closest('.qi-node-context')
  )
    context.value = undefined;
}
function contextEscape(event: KeyboardEvent) {
  if (event.key === 'Escape') context.value = undefined;
}
onMounted(() => {
  window.addEventListener('beforeunload', beforeUnload);
  window.addEventListener('pointerdown', dismissContext);
  window.addEventListener('keydown', contextEscape);
});
onBeforeUnmount(() => {
  window.removeEventListener('beforeunload', beforeUnload);
  window.removeEventListener('pointerdown', dismissContext);
  window.removeEventListener('keydown', contextEscape);
  clear();
});

async function load() {
  const ticket = request.begin();
  const id = String(route.params.id ?? '');
  if (!/^\d+$/.test(id) || !can('query')) {
    loadError.value = '无权访问或任务编号无效';
    return;
  }
  loading.value = true;
  loadError.value = '';
  try {
    const result = await getIntegration(id);
    if (!ticket()) return;
    const data = parseIntegrationFlow(result.flowJson);
    task.value = result;
    original.value = data;
    await nextTick();
    if (!ticket() || !canvas.value) return;
    setup(data);
    saved.value = snapshot();
  } catch (error) {
    if (ticket())
      loadError.value =
        error instanceof SyntaxError
          ? '流程快照无法解析，已保留原始内容，请联系管理员检查。'
          : '任务加载失败，请重试';
  } finally {
    if (ticket()) loading.value = false;
  }
}
watch(
  () => [
    route.params.id,
    access.accessToken,
    access.accessCodes,
    user.userInfo?.userId,
  ],
  load,
  { immediate: true },
);
watch(
  () => [task.value?.id, task.value?.engine, task.value?.mode],
  loadCapabilities,
);

function shape(data: IntegrationFlowNode): Node.Metadata {
  const component = nodeComponent(data);
  const icon =
    component?.icon ?? '/assets/qdata-integration/palette-data-clean.png';
  const sprite =
    component?.code === 'read-db' || component?.code === 'insert-db'
      ? `/assets/qdata-integration/node-${component.code}-ref.png`
      : undefined;
  return {
    id: data.id,
    shape: 'rect',
    x: data.x - 36,
    y: data.y - 25,
    width: 72,
    height: 50,
    data,
    markup: [
      { tagName: 'rect', selector: 'body' },
      { tagName: 'rect', selector: 'selection' },
      { tagName: 'image', selector: 'background' },
      { tagName: 'image', selector: 'icon' },
      { tagName: 'text', selector: 'label' },
    ],
    attrs: {
      body: { fill: 'transparent', stroke: 'transparent', rx: 2 },
      selection: {
        class: 'qi-node-selection-outline',
        x: 16,
        y: -2,
        width: 40,
        height: 40,
        rx: 5,
        fill: 'none',
        stroke: '#2f62ff',
        strokeWidth: 1,
        opacity: 0,
        pointerEvents: 'none',
      },
      background: {
        x: 18,
        y: 0,
        width: 36,
        height: 36,
        'xlink:href': sprite ?? '/assets/qdata-integration/node-background.svg',
      },
      icon: {
        x: 24,
        y: 6,
        width: 24,
        height: 24,
        'xlink:href': icon,
        opacity: sprite ? 0 : 1,
        style: { filter: 'brightness(0) invert(1)' },
      },
      label: {
        text: String(
          data.properties.name ?? component?.name ?? data.label ?? '未知组件',
        ),
        refX: 0.5,
        refY: 47,
        textAnchor: 'middle',
        fontSize: 14,
        fill: '#333',
      },
    },
    ports: {
      groups: {
        in: {
          position: {
            name: 'line',
            args: { start: { x: 18, y: 0 }, end: { x: 18, y: 36 } },
          },
          attrs: {
            circle: {
              r: 3,
              magnet: 'passive',
              stroke: '#b8c8e7',
              fill: '#fff',
            },
          },
        },
        out: {
          position: {
            name: 'line',
            args: { start: { x: 54, y: 0 }, end: { x: 54, y: 36 } },
          },
          attrs: {
            circle: { r: 3, magnet: true, stroke: '#b8c8e7', fill: '#fff' },
          },
        },
      },
      items:
        component?.toolCode === 'sub-pipeline'
          ? getNodePorts(component.toolCode, nodeConfiguration(data)).items
          : [
              ...(component?.group === 'input'
                ? []
                : [{ id: 'in', group: 'in' }]),
              ...(component?.group === 'output'
                ? []
                : [{ id: 'out', group: 'out' }]),
            ],
    },
  };
}
function setup(data: IntegrationFlow) {
  graph.value?.dispose();
  const instance: Graph = new Graph({
    container: canvas.value!,
    autoResize: true,
    background: { color: '#fff' },
    panning: true,
    mousewheel: {
      enabled: true,
      modifiers: ['ctrl', 'meta'],
      minScale: 0.3,
      maxScale: 2.5,
    },
    interacting: () => editable.value,
    connecting: {
      allowBlank: false,
      allowLoop: false,
      allowMulti: false,
      allowNode: false,
      snap: { radius: 15 },
      connector: 'normal',
      connectionPoint: 'anchor',
      validateConnection({ sourceCell, targetCell, sourcePort, targetPort }) {
        if (
          !editable.value ||
          !sourceCell ||
          !targetCell ||
          !sourceCell.isNode() ||
          !targetCell.isNode() ||
          sourceCell.getPort(sourcePort ?? '')?.group !== 'out' ||
          targetCell.getPort(targetPort ?? '')?.group !== 'in'
        )
          return false;
        return !createsCycle(
          instance.getEdges().map((edge) => ({
            sourceNodeId: edge.getSourceCellId(),
            targetNodeId: edge.getTargetCellId(),
          })),
          sourceCell.id,
          targetCell.id,
        );
      },
      createEdge: () =>
        instance.createEdge({
          attrs: {
            line: {
              stroke: '#d1d8eb',
              strokeWidth: 1.5,
              targetMarker: { name: 'block', width: 10, height: 7 },
            },
          },
          data: { properties: { edgeType: 0, outputPort: 'main' } },
        }),
    },
  });
  graph.value = instance;
  instance.use(
    new Selection({
      enabled: true,
      multiple: true,
      rubberband: true,
      showNodeSelectionBox: false,
    }),
  );
  instance.use(new Keyboard({ enabled: true }));
  instance.use(new Snapline({ enabled: true }));
  instance.use(new Export());
  dnd.value = new Dnd({ target: instance, scaled: false });
  instance.fromJSON({
    nodes: data.nodes.map(shape),
    edges: data.edges.map((edge) => ({
      ...edge,
      id: edge.id ?? crypto.randomUUID(),
      shape: 'edge',
      source: {
        cell: edge.sourceNodeId,
        port: getVisualSourcePort(
          edge,
          data.nodes.find((node) => node.id === edge.sourceNodeId)?.properties
            .toolCode,
        ),
      },
      target: { cell: edge.targetNodeId, port: edge.targetPort ?? 'in' },
      data: {
        ...edge,
        properties: { ...edge.properties, outputPort: getSourcePort(edge) },
      },
      attrs: {
        line: {
          stroke: '#d1d8eb',
          strokeWidth: 1.5,
          targetMarker: { name: 'block', width: 10, height: 7 },
        },
      },
    })),
  });
  instance.on('node:dblclick', ({ node }) => openNode(node));
  instance.on('node:contextmenu', ({ node, e }) => {
    e.preventDefault();
    const rows =
      nodeComponent(node.getData<IntegrationFlowNode>())?.group === 'input'
        ? 3
        : 4;
    context.value = {
      id: node.id,
      x: Math.max(4, Math.min(e.clientX, window.innerWidth - 144)),
      y: Math.max(4, Math.min(e.clientY, window.innerHeight - rows * 37 - 4)),
    };
  });
  instance.on('edge:dblclick', ({ edge }) => {
    if (editable.value) edge.remove();
  });
  instance.on('edge:contextmenu', ({ edge, e }) => {
    e.preventDefault();
    context.value = {
      id: edge.id,
      kind: 'edge',
      x: Math.max(4, Math.min(e.clientX, window.innerWidth - 144)),
      y: Math.max(4, Math.min(e.clientY, window.innerHeight - 41)),
    };
  });
  instance.on('blank:click', () => {
    context.value = undefined;
  });
  instance.on('cell:change:*', () => {
    checked.value = false;
    graphVersion.value++;
  });
  instance.on('cell:added', () => {
    checked.value = false;
    graphVersion.value++;
  });
  instance.on('cell:removed', () => {
    checked.value = false;
    graphVersion.value++;
  });
  instance.bindKey(['backspace', 'delete'], () => {
    if (editable.value && !nodeOpen.value && !configOpen.value)
      instance.removeCells(instance.getSelectedCells());
  });
  if (data.nodes.length > 0) instance.centerContent();
}
function newNode(
  component: IntegrationComponent,
  x: number,
  y: number,
): IntegrationFlowNode {
  return {
    id: crypto.randomUUID(),
    type: 'integration-node',
    x,
    y,
    properties: {
      componentCode: component.code,
      toolCode: component.toolCode,
      toolType: { input: 1, output: 3, script: 4, transform: 2, control: 2 }[
        component.group
      ],
      name: component.name,
      nodeJson: {
        ...getDefaultNodeConfig(component.toolCode, component.name),
        ...newIntegrationNodeDefaults(component, task.value?.mode),
      },
    },
  };
}
function drag(component: IntegrationComponent, event: MouseEvent) {
  if (!editable.value || !graph.value || !componentAvailable(component)) return;
  dnd.value?.start(
    graph.value.createNode(shape(newNode(component, 36, 25))),
    event,
  );
}
function add(component: IntegrationComponent) {
  if (
    !editable.value ||
    !graph.value ||
    !canvas.value ||
    !componentAvailable(component)
  )
    return;
  const bounds = canvas.value.getBoundingClientRect();
  const point = graph.value.clientToLocal(
    bounds.left + bounds.width / 2,
    bounds.top + bounds.height / 2,
  );
  const cell = graph.value.addNode(shape(newNode(component, point.x, point.y)));
  openNode(cell);
}
function openNode(node: Node) {
  const data = node.getData<IntegrationFlowNode>();
  try {
    editingId.value = node.id;
    editingCode.value =
      nodeComponent(data)?.code ?? data.properties.toolCode ?? '';
    editingName.value = data.properties.name ?? nodeComponent(data)?.name ?? '';
    editingDescription.value = String(data.properties.description ?? '');
    editingConfig.value = nodeConfiguration(data);
    nodeOpen.value = true;
    context.value = undefined;
    void loadFieldHints();
  } catch {
    message.error('节点配置无法解析，原始内容已保留');
  }
}
function commitNode() {
  if (!editable.value || !editingName.value.trim()) {
    message.warning('请填写节点名称');
    return;
  }
  if (nodeEditor.value?.validate && !nodeEditor.value.validate()) {
    message.warning('请完善节点必填配置');
    return;
  }
  const node = graph.value?.getCellById(editingId.value);
  if (!node?.isNode()) return;
  const data = node.getData<IntegrationFlowNode>();
  const component = currentComponent.value;
  const properties = {
    ...data.properties,
    componentCode: component?.code,
    toolCode: component?.toolCode ?? data.properties.toolCode,
    name: editingName.value.trim(),
    description: editingDescription.value,
    nodeJson: JSON.parse(JSON.stringify(editingConfig.value)),
  };
  if (component?.toolCode === 'data-condition') {
    const branches = editingConfig.value.branches ?? [];
    const targets = branches.map(
      (branch: Record<string, any>) => branch.targetNodeNo,
    );
    if (
      targets.some(
        (target: string) =>
          !downstreamNodes.value.some((node) => node.targetId === target),
      ) ||
      new Set(targets).size !== targets.length
    ) {
      message.warning('每个条件分支应绑定不同的已连接下游节点');
      return;
    }
    for (const edge of graph.value?.getOutgoingEdges(node) ?? []) {
      const branch = branches.find(
        (item: Record<string, any>) =>
          item.targetNodeNo === edge.getTargetCellId(),
      );
      const port = branch?.id ?? 'main';
      const edgeData = edge.getData();
      edge.setData(
        {
          ...edgeData,
          sourcePort: port,
          properties: { ...edgeData?.properties, outputPort: port },
        },
        { overwrite: true },
      );
    }
  }
  if (component?.toolCode === 'sub-pipeline') {
    const ports = getNodePorts(component.toolCode, editingConfig.value);
    let removed = false;
    for (const edge of graph.value?.getConnectedEdges(node) ?? []) {
      const group = edge.getSourceCellId() === node.id ? 'out' : 'in';
      const port =
        group === 'out' ? edge.getSourcePortId() : edge.getTargetPortId();
      if (
        !ports.items.some(
          (item: { group: string; id: string }) =>
            item.group === group && item.id === port,
        )
      ) {
        edge.remove();
        removed = true;
      }
    }
    node.prop('ports/items', ports.items);
    if (removed) message.warning('子流程端口已变化，请重新连接失效的连线');
  }
  node.setData({ ...data, properties }, { overwrite: true });
  node.attr('label/text', properties.name);
  graphVersion.value++;
  nodeOpen.value = false;
}
function removeNode() {
  if (context.value && editable.value)
    graph.value?.getCellById(context.value.id)?.remove();
  context.value = undefined;
}
function editContext() {
  const node = context.value && graph.value?.getCellById(context.value.id);
  if (node?.isNode()) openNode(node);
}
async function loadFieldHints() {
  if (!task.value || !nodeOpen.value || !can('query')) return;
  const content = snapshot();
  if (schemaSnapshot.value === content) return;
  const ticket = hintsRequest.begin();
  try {
    const result = await resolveIntegrationSchema({
      id: task.value.id,
      revision: task.value.revision,
      flowJson: content,
    });
    if (ticket() && snapshot() === content) {
      resolvedSchema.value = result;
      schemaSnapshot.value = content;
    }
  } catch {
    // 元数据不可用时继续使用快照中的保守字段提示，未知内容仍可手动填写。
  }
}
async function resolveEditingFields(): Promise<Api.SchemaField[]> {
  if (!task.value || !nodeOpen.value || !can('query'))
    throw new Error('当前步骤不可读取字段');
  const scope = identity();
  const nodeId = editingId.value;
  const config = JSON.stringify(editingConfig.value);
  const draft = flow();
  draft.nodes = draft.nodes.map((node) =>
    node.id === nodeId
      ? {
          ...node,
          properties: { ...node.properties, nodeJson: JSON.parse(config) },
        }
      : node,
  );
  const result = await resolveIntegrationSchema({
    id: task.value.id,
    revision: task.value.revision,
    flowJson: JSON.stringify(draft),
  });
  if (
    scope !== identity() ||
    !nodeOpen.value ||
    nodeId !== editingId.value ||
    config !== JSON.stringify(editingConfig.value)
  )
    throw new Error('节点配置已变化，请重新解析');
  const nodeSchema = result.nodes?.[nodeId];
  if (
    ['read-hive', 'read-mongodb', 'read-odps', 'read-tdengine'].includes(
      editingCode.value,
    ) &&
    editingConfig.value.ifSql === 1 &&
    nodeSchema?.complete !== true
  )
    throw new Error(
      result.errors?.join('；') ||
        '当前数据源尚未返回完整的SQL解析结果，已有声明字段仍保留',
    );
  const fields = nodeSchema?.outputFields;
  if (!fields?.length)
    throw new Error(
      result.errors?.join('；') || '尚未解析到字段，请检查数据连接和查询语句',
    );
  return fields;
}
async function showFields(direction: 'input' | 'output') {
  const node = context.value && graph.value?.getCellById(context.value.id);
  if (!node?.isNode() || !task.value || !can('query')) return;
  const data = node.getData<IntegrationFlowNode>();
  fieldsDirection.value = direction;
  fieldsNodeId.value = node.id;
  fieldsStepName.value =
    data.properties.name ?? nodeComponent(data)?.name ?? '';
  fieldsSchema.value = undefined;
  fieldsError.value = '';
  fieldsOpen.value = true;
  fieldsLoading.value = true;
  context.value = undefined;
  const ticket = fieldsRequest.begin();
  const content = snapshot();
  try {
    const result = await resolveIntegrationSchema({
      id: task.value.id,
      revision: task.value.revision,
      flowJson: content,
    });
    if (!ticket()) return;
    if (snapshot() !== content) {
      fieldsError.value = '流程已发生变化，请重新查看字段。';
      return;
    }
    resolvedSchema.value = result;
    schemaSnapshot.value = content;
    fieldsSchema.value = result.nodes?.[node.id];
    if (!fieldsSchema.value)
      fieldsError.value =
        result.errors?.join('；') ||
        '当前步骤的字段尚无法确定，请完善节点配置。';
  } catch {
    if (ticket()) fieldsError.value = '字段信息加载失败，请关闭后重试。';
  } finally {
    if (ticket()) fieldsLoading.value = false;
  }
}
async function save(draft: boolean) {
  if (!task.value || !editable.value || saving.value || checking.value) return;
  const ticket = saveRequest.begin();
  const payload = {
    id: task.value.id,
    revision: task.value.revision,
    flowJson: snapshot(),
    draft,
  };
  saving.value = true;
  try {
    const result = await saveIntegrationFlow(payload);
    if (!ticket()) return;
    task.value = result;
    saved.value = payload.flowJson;
    message.success(draft ? '流程已暂存' : '任务流程已保存');
  } finally {
    if (ticket()) saving.value = false;
  }
}
async function check() {
  if (
    !task.value ||
    checking.value ||
    saving.value ||
    loading.value ||
    loadError.value
  )
    return;
  const ticket = checkRequest.begin();
  const content = snapshot();
  checking.value = true;
  try {
    const result = await checkIntegrationFlow({
      id: task.value.id,
      revision: task.value.revision,
      flowJson: content,
      draft: false,
    });
    if (!ticket() || snapshot() !== content) return;
    checks.value = result.valid ? ['检查通过'] : result.errors;
    checked.value = true;
  } finally {
    if (ticket()) checking.value = false;
  }
}
async function checkEditingNode() {
  if (!task.value || !nodeOpen.value || nodeChecking.value || !editable.value)
    return;
  const candidate = flow();
  const node = candidate.nodes.find((item) => item.id === editingId.value);
  if (!node) return;
  node.properties = {
    ...node.properties,
    name: editingName.value,
    description: editingDescription.value,
    nodeJson: JSON.parse(JSON.stringify(editingConfig.value)),
  };
  const content = JSON.stringify(candidate);
  const ticket = nodeCheckRequest.begin();
  nodeChecking.value = true;
  nodeCheckMessages.value = [];
  try {
    const result = await checkIntegrationFlow({
      id: task.value.id,
      revision: task.value.revision,
      flowJson: content,
      draft: false,
    });
    if (!ticket()) return;
    nodeCheckValid.value = result.valid;
    nodeCheckMessages.value = result.valid
      ? ['完整流程检查通过，已包含当前节点草稿。']
      : result.errors;
  } catch (error) {
    if (ticket())
      nodeCheckMessages.value = [
        error instanceof Error ? error.message : '流程检查失败',
      ];
  } finally {
    if (ticket()) nodeChecking.value = false;
  }
}
async function openConfig() {
  if (!task.value || !editable.value || checking.value) return;
  configOpen.value = true;
  const ticket = detailRequest.begin();
  const [categoryList, ownerList] = await Promise.all([
    getIntegrationCategories(),
    getIntegrationOwners(),
  ]);
  if (!ticket()) return;
  categories.value = categoryList;
  owners.value = ownerList;
}
async function refreshTask() {
  if (!task.value) return;
  const ticket = refreshRequest.begin();
  const result = await getIntegration(task.value.id);
  if (ticket()) task.value = result;
}
async function back() {
  const name =
    task.value?.mode === 'REALTIME'
      ? 'DataforgeRealtimeDevelopment'
      : 'DataforgeOfflineDevelopment';
  await router.push({ name });
}
async function reset() {
  if (await discard()) await load();
}
function center() {
  graph.value?.zoomTo(1);
  graph.value?.centerContent();
}
function toggleGroup(key: string) {
  collapsed.value = collapsed.value.includes(key)
    ? collapsed.value.filter((item) => item !== key)
    : [...collapsed.value, key];
}
</script>

<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col overflow-hidden p-0"
  >
    <div class="qi-designer">
      <Alert v-if="loadError" :message="loadError" type="error" show-icon>
        <template #action><Button @click="load">重试</Button></template>
      </Alert>
      <Spin :spinning="loading">
        <header class="qi-design-header">
          <div class="qi-design-title">
            <span v-if="task" class="qi-engine-badge"><img
                :src="`/assets/qdata-integration/${task.engine === 'FLINK' ? 'flink.svg' : task.engine === 'DATAX' ? 'datax.png' : 'spark.svg'}`"
                alt=""
              />{{ task.engine }} 引擎</span><span>{{ task?.name }}</span>
          </div>
          <div class="qi-design-actions">
            <Button :disabled="!editable" :loading="saving" @click="save(true)">
              <ReferenceIcon name="draft" />暂存
            </Button>
            <Button
              :disabled="!editable"
              :loading="saving"
              type="primary"
              @click="save(false)"
            >
              <ReferenceIcon name="save" />任务保存
            </Button>
            <Button :disabled="!editable || checking" @click="openConfig">
              <ReferenceIcon name="settings" />任务配置
            </Button>
            <Button
              :disabled="!task || !can('query')"
              :loading="checking"
              @click="check"
            >
              <ReferenceIcon name="check" />任务检查
            </Button>
            <Button @click="back"><ReferenceIcon name="back" />返回列表</Button>
          </div>
        </header>
        <div class="qi-design-layout">
          <aside
            class="qi-design-palette subtle-tree-scrollbar"
            aria-label="流程组件"
          >
            <p
              v-if="capabilitiesLoading"
              class="qi-capability-summary"
              role="status"
            >
              正在确认组件可用性…
            </p>
            <div
              v-else-if="capabilitiesError"
              class="qi-capability-error"
              role="alert"
            >
              <span>{{ capabilitiesError }}</span><button type="button" @click="loadCapabilities">重试</button>
            </div>
            <p
              v-else-if="capabilities"
              class="qi-capability-summary"
              role="status"
            >
              {{ capabilitySummary }}
            </p>
            <section v-for="group in componentGroups" :key="group.key">
              <button
                class="qi-palette-group"
                :aria-expanded="!collapsed.includes(group.key)"
                @click="toggleGroup(group.key)"
              >
                <span
                  class="qi-chevron"
                  :class="{ collapsed: collapsed.includes(group.key) }"
                  >⌄</span><img
                  :src="`/assets/qdata-integration/palette-${group.icon}.png`"
                  alt=""
                />{{ group.name }}
              </button>
              <div v-if="!collapsed.includes(group.key)">
                <button
                  v-for="item in integrationComponents.filter(
                    (entry) => entry.group === group.key,
                  )"
                  :key="item.code"
                  class="qi-palette-item"
                  :disabled="!editable || !componentAvailable(item)"
                  :title="componentTitle(item)"
                  :data-component-code="item.code"
                  :data-capability-status="componentState(item).status"
                  :aria-label="componentTitle(item)"
                  @mousedown.left="drag(item, $event)"
                  @dblclick="add(item)"
                  @keydown.enter.prevent="add(item)"
                  @keydown.space.prevent="add(item)"
                >
                  <img :src="item.icon" alt="" /><span
                    class="qi-palette-name"
                    >{{ item.name }}</span><span
                    v-if="capabilities"
                    class="qi-palette-status"
                    :class="{
                      available: componentAvailable(item),
                    }"
                    aria-hidden="true"
                    >{{ componentAvailable(item) ? '可用' : '不可用' }}</span>
                </button>
              </div>
            </section>
          </aside>
          <main class="qi-design-main" @contextmenu.prevent>
            <div ref="canvas" class="qi-design-canvas"></div>
            <div class="qi-canvas-tools">
              <Tooltip title="缩小">
                <button aria-label="缩小" @click="graph?.zoom(-0.1)">
                  <ReferenceIcon name="canvas-zoom-out-ref" />
                </button>
              </Tooltip>
              <Tooltip title="放大">
                <button aria-label="放大" @click="graph?.zoom(0.1)">
                  <ReferenceIcon name="canvas-zoom-in-ref" />
                </button>
              </Tooltip>
              <Tooltip title="定位中心">
                <button aria-label="定位中心" @click="center">
                  <ReferenceIcon name="canvas-center-ref" />
                </button>
              </Tooltip>
              <Tooltip title="导出图片">
                <button
                  aria-label="导出图片"
                  @click="graph?.exportPNG(task?.name ?? '集成流程')"
                >
                  <ReferenceIcon name="canvas-export-ref" />
                </button>
              </Tooltip>
              <Tooltip title="重置">
                <button aria-label="重置" @click="reset">
                  <ReferenceIcon name="canvas-reset-ref" />
                </button>
              </Tooltip>
            </div>
            <section v-if="checked" class="qi-design-checks" aria-live="polite">
              <header>
                检查消息
                <button aria-label="关闭检查消息" @click="checked = false">
                  ×
                </button>
              </header>
              <p v-for="(item, index) in checks" :key="index">{{ item }}</p>
            </section>
          </main>
        </div>
      </Spin>
      <div
        v-if="context"
        class="qi-node-context"
        role="menu"
        aria-label="节点操作"
        :style="{ left: `${context.x}px`, top: `${context.y}px` }"
      >
        <button
          v-if="context.kind === 'edge'"
          role="menuitem"
          :disabled="!editable"
          @click="removeNode"
        >
          删除连接线
        </button>
        <template v-else>
          <button role="menuitem" :disabled="!editable" @click="removeNode">
            删除节点
          </button>
          <button role="menuitem" @click="editContext">编辑节点</button>
          <button
            v-if="contextComponent?.group !== 'input'"
            role="menuitem"
            @click="showFields('input')"
          >
            显示输入字段
          </button>
          <button role="menuitem" @click="showFields('output')">
            显示输出字段
          </button>
        </template>
      </div>
      <ConfigProvider :theme="integrationReferenceTheme">
        <NodeFieldsModal
          v-model:open="fieldsOpen"
          :direction="fieldsDirection"
          :step-name="fieldsStepName"
          :fields="previewFields"
          :loading="fieldsLoading"
          :error="fieldsError"
          :partial="fieldsSchema?.complete === false"
        />
        <Modal
          v-model:open="nodeOpen"
          :title="currentComponent?.name ?? '节点配置'"
          :width="1200"
          :mask-closable="false"
          :closable="false"
          class="qi-node-modal qi-node-modal-reference"
          :class="{
            'qi-node-modal-table-input': editingCode === 'read-db',
            'qi-node-modal-field-split': editingCode === 'field-split',
            'qi-node-modal-dedup': editingCode === 'dedup-records',
            'qi-node-modal-row-to-column': editingCode === 'row-to-column',
            'qi-node-modal-column-to-row': editingCode === 'column-to-row',
            'qi-node-modal-id-generator': editingCode === 'id-generator',
          }"
          :data-component-code="editingCode"
          :destroy-on-hidden="true"
        >
          <template v-if="nodeHelp" #title>
            {{ currentComponent?.name ?? '节点配置' }}
            <Tooltip :title="nodeHelp">
              <span class="qi-node-title-help" tabindex="0" aria-label="组件说明"><ReferenceIcon name="info" /></span>
            </Tooltip>
          </template>
          <ReferenceNodeBasics
            v-if="!steppedComponent"
            v-model:name="editingName"
            v-model:description="editingDescription"
            :type-name="currentComponent?.name"
            :disabled="!editable"
            :show-description="!editorDescription && !advancedDescription"
            :heading="editingCode === 'read-json' ? '属性字段' : undefined"
            :description-placeholder="
              editingCode === 'read-json'
                ? '请输入描述（可选），如：从JSONL文件读取结构化数据，要求每行一个JSON对象且结构一致'
                : undefined
            "
            :hide-type="editingCode === 'sub-pipeline'"
            :stacked="editingCode === 'sub-pipeline'"
          />
          <ConfigProvider :component-disabled="!editable">
            <fieldset :disabled="!editable" class="qi-node-fields">
              <NodeConfigurationEditor
                ref="nodeEditor"
                :config="editingConfig"
                :tool-code="currentComponent?.toolCode ?? editingCode"
                :component-code="editingCode"
                :mode="task?.mode"
                :engine="task?.engine"
                :input-fields="upstreamFields"
                :resolve-fields="resolveEditingFields"
                :downstream-nodes="downstreamNodes"
                :upstream-nodes="upstreamNodes"
                :execution-reason="editingCapability?.reason"
                :disabled="!editable"
                :task-id="task?.id"
                :revision="task?.revision"
                @request-check="checkEditingNode"
              >
                <template #basics>
                  <ReferenceNodeBasics
                    v-if="steppedComponent"
                    v-model:name="editingName"
                    v-model:description="editingDescription"
                    :type-name="currentComponent?.name"
                    :type-required="editingCode === 'read-webservice'"
                    :name-hint="
                      editingCode === 'rest-api'
                        ? '任何有意义且能描述本节点的说明(选择API后自动填充为API的名称)'
                        : undefined
                    "
                    :disabled="!editable"
                    :stacked="
                      ['multi-join', 'read-webservice'].includes(editingCode)
                    "
                    :show-description="
                      !editorDescription && !advancedDescription
                    "
                  />
                </template>
                <template #description>
                  <ReferenceNodeBasics
                    v-if="editorDescription"
                    v-model:name="editingName"
                    v-model:description="editingDescription"
                    description-only
                    :disabled="!editable"
                  />
                </template>
              </NodeConfigurationEditor>
              <Alert
                v-if="nodeChecking"
                type="info"
                message="正在检查包含当前节点草稿的完整流程……"
              />
              <Alert
                v-else-if="nodeCheckMessages.length"
                :type="nodeCheckValid ? 'success' : 'error'"
                :message="nodeCheckMessages.join('；')"
              />
              <details v-if="advancedDescription" class="qi-reference-advanced">
                <summary>节点描述</summary>
                <ReferenceNodeBasics
                  v-model:name="editingName"
                  v-model:description="editingDescription"
                  description-only
                  :disabled="!editable"
                />
              </details>
            </fieldset>
          </ConfigProvider>
          <template #footer>
            <Button
              v-if="nodeEditor?.canGetRules"
              class="qi-node-get-fields"
              :disabled="!editable"
              @click="nodeEditor?.openRulePicker?.()"
            >
              获取清洗规则
            </Button>
            <Button
              v-else-if="nodeEditor?.canMergeFields && editingCode !== 'dedup-records'"
              class="qi-node-get-fields"
              :disabled="!editable"
              @click="nodeEditor?.openFieldMerge?.()"
            >
              获取字段
            </Button>
            <Button
              v-if="nodeEditor?.stepNav && nodeEditor.stepNav.step > 0"
              :auto-insert-space="false"
              type="primary"
              @click="nodeEditor.stepNav.prev()"
            >
              上一步
            </Button>
            <Button
              v-if="nodeEditor?.stepNav && nodeEditor.stepNav.step < nodeEditor.stepNav.last"
              :auto-insert-space="false"
              type="primary"
              @click="nodeEditor.stepNav.next()"
            >
              下一步
            </Button>
            <Button :auto-insert-space="false" @click="nodeOpen = false">关闭</Button><Button :auto-insert-space="false" :disabled="!editable" type="primary" @click="commitNode">
              保存
            </Button>
            <Button
              v-if="editingCode === 'dedup-records' && nodeEditor?.canMergeFields"
              class="qi-node-get-fields"
              :disabled="!editable"
              @click="nodeEditor?.openFieldMerge?.()"
            >
              获取字段
            </Button>
          </template>
        </Modal>
        <TaskForm
          v-if="task"
          v-model:open="configOpen"
          :task="task"
          :mode="task.mode"
          :categories="categories"
          :owners="owners"
          @saved="refreshTask"
        />
      </ConfigProvider>
    </div>
  </Page>
</template>
