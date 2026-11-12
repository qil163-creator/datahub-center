<script setup lang="ts">
import type { LineageCanvas } from '../graph-model';

import type { GovernanceLineageApi } from '#/api/governance/lineage';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { Alert, Button, Descriptions, DescriptionsItem, Empty, RadioGroup, Select, Spin, Tag } from 'antdv-next';

import { getLineageColumnGraph, getLineageGraph, getLineageNodeColumns } from '#/api/governance/lineage';

import {
  layoutColumnGraph,
  layoutTableGraph,
  mergeGraph,
  nodeOptionLabel,
  nodeTitle,
  ORIGIN_LABELS,
  SOURCE_TYPE_LABELS,
  STATUS_LABELS,
  TRANSFORM_LABELS,
} from '../graph-model';
import LineageGraph from './lineage-graph.vue';

defineOptions({ name: 'LineageExplorer' });

const props = defineProps<{ nodeId?: GovernanceLineageApi.Id }>();

const direction = ref<GovernanceLineageApi.Direction>('BOTH');
const depth = ref(2);
const mode = ref<'column' | 'table'>('table');
const column = ref<string>();
/** 当前字段血缘所属节点（可能不是根节点）。 */
const columnNodeId = ref<GovernanceLineageApi.Id>();
const columns = ref<string[]>([]);
const tableGraph = ref<GovernanceLineageApi.Graph>();
const columnGraph = ref<GovernanceLineageApi.ColumnGraph>();
const selectedKey = ref<string>();
const selectedEdgeKey = ref<string>();
const loading = ref(false);
const error = ref('');

let graphController: AbortController | undefined;
let columnsController: AbortController | undefined;

const depthOptions = [1, 2, 3, 4, 5].map((value) => ({ label: `${value} 层`, value }));
const directionOptions = [
  { label: '上下游', value: 'BOTH' },
  { label: '仅上游', value: 'UPSTREAM' },
  { label: '仅下游', value: 'DOWNSTREAM' },
];

const canvas = computed<LineageCanvas>(() => {
  if (mode.value === 'column') return columnGraph.value ? layoutColumnGraph(columnGraph.value) : { nodes: [], edges: [] };
  return tableGraph.value ? layoutTableGraph(tableGraph.value) : { nodes: [], edges: [] };
});
const truncated = computed(() =>
  mode.value === 'column' ? columnGraph.value?.truncated : tableGraph.value?.truncated,
);
const selectedNode = computed(() => {
  const key = selectedKey.value;
  if (!key) return undefined;
  if (mode.value === 'table') return tableGraph.value?.nodes.find((node) => String(node.id) === key);
  return columnGraph.value?.nodes.find((node) => `${String(node.nodeId)}:${node.column}` === key)?.node;
});
const selectedTableNode = computed(() =>
  mode.value === 'table' ? tableGraph.value?.nodes.find((node) => String(node.id) === selectedKey.value) : undefined,
);
const selectedEdgeSources = computed(() => {
  const key = selectedEdgeKey.value;
  if (!key) return undefined;
  if (mode.value === 'table') {
    const edge = tableGraph.value?.edges.find(
      (item) => `${String(item.upstreamNodeId)}>${String(item.downstreamNodeId)}` === key,
    );
    return edge && { origins: edge.origins, sources: edge.sources, transform: undefined };
  }
  const edge = columnGraph.value?.edges.find(
    (item) =>
      `${String(item.upstreamNodeId)}:${item.upstreamColumn}>${String(item.downstreamNodeId)}:${item.downstreamColumn}` ===
      key,
  );
  return edge && { origins: [], sources: edge.sources, transform: edge };
});

/** 只有最新请求可以写入结果与 loading。 */
function startGraphRequest() {
  graphController?.abort();
  graphController = new AbortController();
  return graphController;
}

function isAbort(reason: unknown) {
  return reason instanceof DOMException
    ? reason.name === 'AbortError'
    : (reason as { code?: string })?.code === 'ERR_CANCELED';
}

async function loadTable(nodeId: GovernanceLineageApi.Id) {
  const controller = startGraphRequest();
  loading.value = true;
  error.value = '';
  try {
    const graph = await getLineageGraph(
      { nodeId, direction: direction.value, depth: depth.value },
      controller.signal,
    );
    if (controller !== graphController) return;
    tableGraph.value = graph;
    selectedKey.value = String(graph.rootNodeId);
    selectedEdgeKey.value = undefined;
  } catch (reason) {
    if (controller === graphController && !isAbort(reason)) {
      error.value = '血缘图加载失败，请稍后重试';
      tableGraph.value = undefined;
    }
  } finally {
    if (controller === graphController) loading.value = false;
  }
}

async function loadColumn(nodeId: GovernanceLineageApi.Id, name: string) {
  const controller = startGraphRequest();
  loading.value = true;
  error.value = '';
  try {
    const graph = await getLineageColumnGraph(
      { nodeId, column: name, direction: direction.value, depth: depth.value },
      controller.signal,
    );
    if (controller !== graphController) return;
    columnGraph.value = graph;
    selectedKey.value = `${String(graph.rootNodeId)}:${graph.rootColumn}`;
    selectedEdgeKey.value = undefined;
  } catch (reason) {
    if (controller === graphController && !isAbort(reason)) {
      error.value = '字段血缘加载失败，请稍后重试';
      columnGraph.value = undefined;
    }
  } finally {
    if (controller === graphController) loading.value = false;
  }
}

async function loadColumns(nodeId: GovernanceLineageApi.Id) {
  columnsController?.abort();
  const controller = new AbortController();
  columnsController = controller;
  try {
    const list = await getLineageNodeColumns(nodeId, controller.signal);
    if (controller === columnsController) columns.value = list ?? [];
  } catch (reason) {
    if (controller === columnsController && !isAbort(reason)) columns.value = [];
  }
}

/** 在当前图上展开某个节点的一层上游或下游。 */
async function expand(nodeId: GovernanceLineageApi.Id, towards: 'DOWNSTREAM' | 'UPSTREAM') {
  const base = tableGraph.value;
  if (!base) return;
  const controller = startGraphRequest();
  loading.value = true;
  try {
    const extra = await getLineageGraph({ nodeId, direction: towards, depth: 1 }, controller.signal);
    if (controller !== graphController) return;
    tableGraph.value = mergeGraph(base, extra);
  } catch (reason) {
    if (controller === graphController && !isAbort(reason)) error.value = '展开失败，请稍后重试';
  } finally {
    if (controller === graphController) loading.value = false;
  }
}

function reload() {
  if (props.nodeId === undefined || props.nodeId === null || props.nodeId === '') return;
  if (mode.value === 'column' && column.value && columnNodeId.value !== undefined)
    void loadColumn(columnNodeId.value, column.value);
  else void loadTable(props.nodeId);
}

function selectNode(key: string) {
  selectedKey.value = key;
  selectedEdgeKey.value = undefined;
}

function selectEdge(key: string) {
  selectedEdgeKey.value = key;
}

function showColumn(name: string) {
  const nodeId = mode.value === 'table' ? selectedKey.value : selectedNode.value?.id;
  if (!nodeId) return;
  column.value = name;
  columnNodeId.value = nodeId;
  mode.value = 'column';
  void loadColumn(nodeId, name);
}

function backToTable() {
  mode.value = 'table';
  column.value = undefined;
  columnNodeId.value = undefined;
  if (!tableGraph.value) reload();
}

watch(
  () => props.nodeId,
  (nodeId) => {
    tableGraph.value = undefined;
    columnGraph.value = undefined;
    columns.value = [];
    mode.value = 'table';
    column.value = undefined;
    selectedKey.value = undefined;
    if (nodeId !== undefined && nodeId !== null && nodeId !== '') void loadTable(nodeId);
    else graphController?.abort();
  },
  { immediate: true },
);

watch([direction, depth], reload);

watch(
  () => (mode.value === 'table' ? selectedKey.value : undefined),
  (key) => {
    columns.value = [];
    if (key) void loadColumns(key);
  },
);

onBeforeUnmount(() => {
  graphController?.abort();
  columnsController?.abort();
});
</script>

<template>
  <div class="lineage-explorer">
    <div class="lineage-explorer__toolbar">
      <RadioGroup v-model:value="direction" :options="directionOptions" option-type="button" size="small" />
      <Select v-model:value="depth" :options="depthOptions" size="small" style="width: 90px" aria-label="展开层数" />
      <template v-if="mode === 'column'">
        <Tag color="processing">字段血缘：{{ column }}</Tag>
        <Button size="small" @click="backToTable">返回表级血缘</Button>
      </template>
      <span class="lineage-explorer__legend">实线：自动解析或运行记录；虚线：手工维护、字段归属不确定或间接依赖</span>
    </div>
    <Alert
      v-if="truncated"
      type="warning"
      show-icon
      class="lineage-explorer__alert"
      message="血缘节点较多，结果已截断。可减少层数或只看上游/下游。"
    />
    <Alert v-if="error" type="error" show-icon class="lineage-explorer__alert" :message="error" />
    <div class="lineage-explorer__body">
      <Spin :spinning="loading" wrapper-class-name="lineage-explorer__graph">
        <LineageGraph
          :canvas="canvas"
          :selected-key="selectedKey"
          @select-edge-key="selectEdge"
          @select-node-key="selectNode"
        />
      </Spin>
      <aside class="lineage-explorer__panel">
        <template v-if="selectedEdgeSources">
          <h4>连线来源</h4>
          <p v-if="selectedEdgeSources.transform" class="lineage-explorer__muted">
            {{ TRANSFORM_LABELS[selectedEdgeSources.transform.transformSubtype] ?? selectedEdgeSources.transform.transformSubtype }}
            <Tag v-if="selectedEdgeSources.transform.ambiguous" color="warning">归属不确定</Tag>
            <Tag v-if="selectedEdgeSources.transform.masking" color="purple">脱敏/哈希</Tag>
          </p>
          <p v-if="selectedEdgeSources.origins.length" class="lineage-explorer__muted">
            {{ selectedEdgeSources.origins.map((origin) => ORIGIN_LABELS[origin] ?? origin).join('、') }}
          </p>
          <ul class="lineage-explorer__list">
            <li v-for="source in selectedEdgeSources.sources" :key="String(source.id)">
              <Tag>{{ SOURCE_TYPE_LABELS[source.sourceType] ?? source.sourceType }}</Tag>
              {{ source.sourceName || source.sourceId }}
              <Tag v-if="source.status" :color="STATUS_LABELS[source.status]?.color">
                {{ STATUS_LABELS[source.status]?.label ?? source.status }}
              </Tag>
            </li>
          </ul>
        </template>
        <template v-else-if="selectedNode">
          <h4>{{ nodeTitle(selectedNode) }}</h4>
          <Descriptions :column="1" size="small">
            <DescriptionsItem label="对象">{{ nodeOptionLabel(selectedNode) }}</DescriptionsItem>
            <DescriptionsItem v-if="selectedNode.nodeType === 'TABLE'" label="数据源">
              {{ selectedNode.datasourceId ?? '-' }}
            </DescriptionsItem>
            <DescriptionsItem v-else label="外部数据集">{{ selectedNode.externalKey }}</DescriptionsItem>
            <DescriptionsItem label="元数据">
              {{ selectedNode.metaTableId ? '已关联' : '未采集' }}
            </DescriptionsItem>
          </Descriptions>
          <div v-if="selectedTableNode" class="lineage-explorer__actions">
            <Button
              size="small"
              :disabled="!selectedTableNode.upstreamExpandable"
              @click="expand(selectedTableNode.id, 'UPSTREAM')"
            >
              展开上游
            </Button>
            <Button
              size="small"
              :disabled="!selectedTableNode.downstreamExpandable"
              @click="expand(selectedTableNode.id, 'DOWNSTREAM')"
            >
              展开下游
            </Button>
          </div>
          <template v-if="mode === 'table'">
            <h4>字段</h4>
            <Empty v-if="columns.length === 0" description="暂无字段血缘" />
            <ul v-else class="lineage-explorer__columns">
              <li v-for="name in columns" :key="name">
                <Button type="link" size="small" @click="showColumn(name)">{{ name === '*' ? '整表依赖' : name }}</Button>
              </li>
            </ul>
          </template>
        </template>
        <Empty v-else description="点击节点或连线查看详情" />
      </aside>
    </div>
  </div>
</template>

<style scoped>
.lineage-explorer {
  display: flex;
  flex-direction: column;
  gap: 8px;
  height: 100%;
  min-height: 480px;
}

.lineage-explorer__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
}

.lineage-explorer__legend,
.lineage-explorer__muted {
  color: #86909c;
  font-size: 12px;
}

.lineage-explorer__body {
  display: flex;
  flex: 1;
  gap: 12px;
  min-height: 0;
}

.lineage-explorer__graph {
  flex: 1;
  min-width: 0;
}

.lineage-explorer__graph :deep(.ant-spin-container) {
  height: 100%;
}

.lineage-explorer__panel {
  width: 280px;
  flex-shrink: 0;
  overflow: auto;
  padding: 8px 12px;
  border: 1px solid #eceef2;
  border-radius: 6px;
}

.lineage-explorer__panel h4 {
  margin: 4px 0 8px;
  font-weight: 600;
}

.lineage-explorer__actions {
  display: flex;
  gap: 8px;
  margin: 8px 0;
}

.lineage-explorer__list,
.lineage-explorer__columns {
  margin: 0;
  padding: 0;
  list-style: none;
}

.lineage-explorer__list li {
  padding: 4px 0;
}
</style>
