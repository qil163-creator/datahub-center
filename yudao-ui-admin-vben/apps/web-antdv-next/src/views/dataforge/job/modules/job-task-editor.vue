<script setup lang="ts">
import type {
  IntegrationFlow,
  IntegrationFlowNode,
} from '../../integration/component-catalog';
import type { JobNode } from '../graph-model';

import type { DataforgeDevApi } from '#/api/dataforge/dev';
import type { IntegrationApi as Api } from '#/api/dataforge/integration';
import type { IntegrationComponentCapabilities } from '#/api/dataforge/integration/component-capabilities';

import { computed, nextTick, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';

import {
  Alert,
  Button,
  ConfigProvider,
  Form,
  Input,
  message,
  Modal,
  Select,
  Spin,
  TextArea,
  TreeSelect,
} from 'antdv-next';

import { getDevDirList, getDevTask } from '#/api/dataforge/dev';
import {
  getIntegration,
  getIntegrationCategories,
  getIntegrationOwners,
  resolveIntegrationSchema,
  saveIntegrationFlow,
  updateIntegration,
} from '#/api/dataforge/integration';
import { getIntegrationComponentCapabilities } from '#/api/dataforge/integration/component-capabilities';
import { useObjectRequest } from '#/hooks/use-object-request';

import DevTaskDetail from '../../dev/modules/task-detail-view.vue';
import DevTaskForm from '../../dev/modules/task-form.vue';
import {
  componentCapability,
  nodeComponent,
  nodeConfiguration,
  parseIntegrationFlow,
} from '../../integration/component-catalog';
import { inferIntegrationInputFields } from '../../integration/field-hints';
import {
  categoryTree,
  priorityOptions,
  saveTask,
} from '../../integration/model';
import NodeConfigurationEditor from '../../integration/modules/node-configuration-editor.vue';
import { integrationReferenceTheme } from '../../integration/reference-theme';

import '../../integration/designer.css';
import '../designer.css';

const props = withDefaults(
  defineProps<{ node?: JobNode; open: boolean; readonly?: boolean }>(),
  { node: undefined, readonly: false },
);
const emit = defineEmits<{
  saved: [node: JobNode];
  'update:open': [value: boolean];
}>();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const record = ref<Api.Task>();
const development = ref<DataforgeDevApi.Task>();
const developmentDirectory = ref('');
const capabilities = ref<IntegrationComponentCapabilities>();
const categories = ref<Api.Category[]>([]);
const owners = ref<Api.Owner[]>([]);
const loading = ref(false);
const busy = ref(false);
const failed = ref('');
const validation = ref('');
const flow = ref<IntegrationFlow>({ nodes: [], edges: [] });
const configurations = ref<Record<string, Record<string, any>>>({});
const nodeEditors = ref<{ validate: () => boolean }[]>([]);
const nodePriority = ref<Api.Priority>('MEDIUM');
const sourceEditing = ref(false);
const canSaveNode = computed(
  () =>
    !props.readonly &&
    canQuery.value &&
    !!(record.value || development.value) &&
    !loading.value &&
    !failed.value,
);
const devForm = ref<InstanceType<typeof DevTaskForm>>();
const devEditing = ref(false);
const request = useObjectRequest(
  () => `${props.open}/${props.node?.id}/${props.node?.kind}/${props.node?.taskId}`,
);
const saveRequest = useObjectRequest(
  () => `${props.open}/${props.node?.id}/${props.node?.kind}/${props.node?.taskId}`,
);
const canQuery = computed(() =>
  hasAccessByCodes([
    props.node?.kind === 'DEVELOPMENT'
      ? 'dataforge:dev-task:query'
      : 'dataforge:integration:query',
  ]),
);
const canEditSource = computed(
  () =>
    !props.readonly &&
    !!record.value &&
    record.value.status === 0 &&
    !record.value.activeRunning &&
    hasAccessByCodes(['dataforge:integration:update']),
);
const editable = computed(() => canEditSource.value && sourceEditing.value);
const tree = computed(() => categoryTree(categories.value, true));
const ownerOptions = computed(() =>
  owners.value.map((owner) => ({ value: String(owner.id), label: owner.name })),
);

const metadata = ref('');
const originalFlow = ref('');
const canEditDev = computed(
  () =>
    !props.readonly &&
    development.value?.status === 0 &&
    hasAccessByCodes(['dataforge:dev-task:save']),
);

function configuredFlow() {
  return {
    ...flow.value,
    nodes: flow.value.nodes.map((node) =>
      configurations.value[node.id]
        ? {
            ...node,
            properties: {
              ...node.properties,
              nodeJson: configurations.value[node.id],
            },
          }
        : node,
    ),
    edges: flow.value.edges.map((edge) => {
      const source = flow.value.nodes.find((node) => node.id === edge.sourceNodeId);
      if (!source || toolCode(source) !== 'data-condition') return edge;
      const branch = configurations.value[source.id]?.branches?.find(
        (item: Record<string, any>) => item.targetNodeNo === edge.targetNodeId,
      );
      const port = branch?.id ?? 'main';
      return { ...edge, sourcePort: port, properties: { ...edge.properties, outputPort: port } };
    }),
  };
}
function executionReason(node: IntegrationFlowNode) {
  const component = nodeComponent(node);
  return component ? componentCapability(component, record.value?.engine, record.value?.mode, capabilities.value).reason : undefined;
}
function toolCode(node: IntegrationFlowNode) {
  return (
    nodeComponent(node)?.toolCode ?? String(node.properties.toolCode ?? '')
  );
}
async function resolveFields(node: IntegrationFlowNode) {
  if (!record.value) return [];
  const identity = `${props.node?.kind}/${props.node?.taskId}`;
  const snapshot = JSON.stringify(configuredFlow());
  const result = await resolveIntegrationSchema({
    id: record.value.id,
    revision: record.value.revision,
    flowJson: snapshot,
  });
  if (
    !props.open ||
    identity !== `${props.node?.kind}/${props.node?.taskId}` ||
    snapshot !== JSON.stringify(configuredFlow())
  )
    throw new Error('任务配置已变化，请重试');
  return result.nodes?.[node.id]?.inputFields ?? [];
}
async function load() {
  if (!props.open || !props.node) return;
  nodePriority.value = props.node.priority ?? 'MEDIUM';
  record.value = undefined;
  development.value = undefined;
  developmentDirectory.value = '';
  capabilities.value = undefined;
  failed.value = '';
  validation.value = '';
  devEditing.value = false;
  sourceEditing.value = false;
  flow.value = { nodes: [], edges: [] };
  configurations.value = {};
  if (!canQuery.value) {
    failed.value = '没有此任务的查询权限';
    return;
  }
  const current = request.begin();
  loading.value = true;
  try {
    if (props.node.kind === 'DEVELOPMENT') {
      const task = await getDevTask(props.node.taskId);
      if (!current()) return;
      development.value = task;
      const dirs = await getDevDirList().catch(() => []);
      if (!current()) return;
      const find = (items: DataforgeDevApi.Dir[]): string => {
        for (const item of items) {
          if (String(item.id) === String(task.dirId)) return item.name ?? '';
          const child = find(item.children ?? []);
          if (child) return child;
        }
        return '';
      };
      developmentDirectory.value = find(dirs);
      return;
    }
    const [task, dirs, people] = await Promise.all([
      getIntegration(props.node.taskId),
      getIntegrationCategories(),
      getIntegrationOwners(),
    ]);
    if (!current()) return;
    record.value = {
      ...task,
      categoryId: String(task.categoryId),
      ownerId: task.ownerId === null ? null : String(task.ownerId),
    };
    categories.value = dirs;
    owners.value = people;
    flow.value = parseIntegrationFlow(task.flowJson);
    configurations.value = Object.fromEntries(
      flow.value.nodes.map((node) => [node.id, nodeConfiguration(node)]),
    );
    metadata.value = JSON.stringify(saveTask(record.value));
    originalFlow.value = JSON.stringify(configuredFlow());
    const support = await getIntegrationComponentCapabilities({ engine: task.engine, mode: task.mode }).catch(() => undefined);
    if (current()) capabilities.value = support;
  } catch {
    if (current()) failed.value = '任务配置加载失败，请重试';
  } finally {
    if (current()) loading.value = false;
  }
}
function close() {
  if (busy.value) return;
  const changed = nodePriority.value !== (props.node?.priority ?? 'MEDIUM') ||
    (editable.value && !!record.value && (metadata.value !== JSON.stringify(saveTask(record.value)) || originalFlow.value !== JSON.stringify(configuredFlow())));
  if (!changed) { emit('update:open', false); return; }
  Modal.confirm({ title: '配置尚未保存', content: '关闭后将放弃当前弹窗中尚未保存的修改。', okText: '放弃修改', cancelText: '继续编辑', onOk: () => emit('update:open', false) });
}
async function editDevelopment() {
  if (!canEditDev.value || !props.node) return;
  devEditing.value = true;
  await nextTick();
  await devForm.value?.open(props.node.taskId);
}
async function developmentSaved(id: DataforgeDevApi.Id) {
  const current = request.begin();
  const task = await getDevTask(id);
  if (!current() || !props.node) return;
  development.value = task;
  devEditing.value = false;
  emit('saved', {
    ...props.node,
    name: task.name || props.node.name,
    status: task.status,
    description: task.description,
  });
}
function openDesigner() {
  if (!props.node) return;
  const target = router.resolve({
    name:
      props.node.kind === 'INTEGRATION'
        ? 'DataforgeIntegrationDesigner'
        : 'DataforgeDevTaskDetail',
    params: { id: String(props.node.taskId) },
  });
  window.open(target.href, '_blank', 'noopener');
}
function saveNode() {
  if (!props.node || !canSaveNode.value || busy.value) return;
  emit('saved', { ...props.node, priority: nodePriority.value });
  emit('update:open', false);
  message.success('节点配置已更新，请保存作业流程');
}
function inputFields(node: IntegrationFlowNode) {
  return inferIntegrationInputFields(configuredFlow(), node.id).fields;
}
function downstreamNodes(node: IntegrationFlowNode) {
  return flow.value.edges
    .filter((edge) => edge.sourceNodeId === node.id)
    .map((edge) => ({
      targetId: edge.targetNodeId,
      targetName: String(
        flow.value.nodes.find((item) => item.id === edge.targetNodeId)
          ?.properties.name ?? edge.targetNodeId,
      ),
    }));
}
async function save() {
  if (!canSaveNode.value || busy.value) return;
  if (!record.value || !editable.value) {
    saveNode();
    return;
  }
  validation.value = '';
  for (const node of flow.value.nodes) {
    if (toolCode(node) !== 'data-condition') continue;
    const targets = (configurations.value[node.id]?.branches ?? []).map((branch: Record<string, any>) => branch.targetNodeNo);
    if (new Set(targets).size !== targets.length || targets.some((target: string) => !downstreamNodes(node).some((item) => item.targetId === target))) {
      validation.value = '每个条件分支应绑定不同的已连接下游节点';
      return;
    }
  }
  if (
    !record.value.name.trim() ||
    !record.value.categoryId ||
    record.value.ownerId === null
  ) {
    validation.value = '请填写类目、任务名称和责任人';
    return;
  }
  if (nodeEditors.value.some((editor) => !editor.validate())) {
    validation.value = '请完善节点配置';
    return;
  }
  const current = saveRequest.begin();
  const flowJson = JSON.stringify(configuredFlow());
  busy.value = true;
  let metadataSaved = false;
  try {
    const payload = saveTask(record.value);
    if (JSON.stringify(payload) !== metadata.value) {
      await updateIntegration(payload);
      metadataSaved = true;
      const refreshed = await getIntegration(record.value.id);
      if (!current()) return;
      record.value.revision = refreshed.revision;
      metadata.value = JSON.stringify(saveTask(record.value));
    }
    if (flowJson !== originalFlow.value) {
      const saved = await saveIntegrationFlow({
        id: record.value.id,
        revision: record.value.revision,
        flowJson,
        draft: true,
      });
      if (!current()) return;
      record.value.revision = saved.revision;
      originalFlow.value = flowJson;
    }
    if (!current() || !props.node) return;
    emit('saved', {
      ...props.node,
      name: record.value.name,
      priority: nodePriority.value,
      status: record.value.status,
      description: record.value.description,
    });
    emit('update:open', false);
    message.success('任务配置已保存，请保存作业流程以应用节点优先级');
  } catch {
    if (current() && metadataSaved)
      validation.value = '基本信息已保存，流程配置保存失败，请修正后重试';
  } finally {
    if (current()) busy.value = false;
  }
}
watch(
  () => [props.open, props.node?.id, props.node?.kind, props.node?.taskId],
  () => {
    if (!props.open) {
      record.value = undefined;
      development.value = undefined;
      categories.value = [];
      owners.value = [];
      configurations.value = {};
      flow.value = { nodes: [], edges: [] };
      loading.value = false;
      busy.value = false;
      devEditing.value = false;
      return;
    }
    void load();
  },
  { immediate: true },
);
</script>

<template>
  <ConfigProvider :theme="integrationReferenceTheme">
    <Modal
      :open="open && !devEditing"
      :title="node?.name || '任务配置'"
      :width="1200"
      :mask-closable="false"
      :closable="!busy"
      wrap-class-name="qj-task-editor-modal"
      @cancel="close"
    >
      <Spin :spinning="loading">
        <Alert v-if="failed" type="error" :message="failed" show-icon>
          <template #action><Button @click="load">重试</Button></template>
        </Alert>
        <Alert v-if="validation" type="error" :message="validation" show-icon />
        <Alert v-if="editable" type="info" message="正在修改源任务配置，保存后会影响所有引用此任务的作业。节点优先级仅用于当前作业。" show-icon />
        <div
          v-if="record || development"
          class="qj-linked-task-grid qj-node-priority"
        >
          <label><span><em>*</em>任务优先级</span><Select
              v-model:value="nodePriority"
              :options="priorityOptions"
              aria-label="作业节点任务优先级"
              :disabled="!canSaveNode || busy"
          /></label>
        </div>
        <template v-if="development">
          <DevTaskDetail
            :task="development"
            presentation="config"
            :owner-name="development.ownerName"
            :directory-name="developmentDirectory"
          />
        </template>
        <Form
          v-if="record"
          :disabled="!editable || busy"
          class="qj-linked-task-form"
        >
          <div class="qj-linked-task-grid">
            <label><span>数据集成类目</span><TreeSelect
                v-model:value="record.categoryId"
                :tree-data="tree"
                tree-default-expand-all
                placeholder="请选择数据集成类目"
            /></label>
            <label><span>任务名称</span><Input
                v-model:value="record.name"
                :maxlength="128"
                placeholder="请输入任务名称"
            /></label>
            <label><span>责任人</span><Select
                v-model:value="record.ownerId"
                :options="ownerOptions"
                placeholder="请选择责任人"
            /></label>
            <label><span>联系电话</span><Input
                :value="
                  owners.find(
                    (owner) => String(owner.id) === String(record?.ownerId),
                  )?.contactNumber || record.contactNumber
                "
                disabled
            /></label>

            <label><span>描述</span><TextArea
                v-model:value="record.description"
                :maxlength="256"
                :rows="2"
                show-count
            /></label>
          </div>
          <template v-for="entry in flow.nodes" :key="entry.id">
            <h3 class="qj-linked-task-section">
              {{
                nodeComponent(entry)?.name ||
                entry.properties.name ||
                '节点配置'
              }}
            </h3>
            <NodeConfigurationEditor
              v-if="configurations[entry.id]"
              ref="nodeEditors"
              :config="configurations[entry.id]!"
              :disabled="!editable || busy"
              :engine="record?.engine"
              :mode="record?.mode"
              :tool-code="toolCode(entry)"
              :component-code="nodeComponent(entry)?.code"
              :input-fields="inputFields(entry)"
              :downstream-nodes="downstreamNodes(entry)"
              :resolve-fields="() => resolveFields(entry)"
              :execution-reason="executionReason(entry)"
            />
            <div
              v-if="toolCode(entry) === 'insert-db' && configurations[entry.id]"
              class="qj-linked-task-grid"
            >
              <label><span>前置SQL</span><TextArea
                  v-model:value="configurations[entry.id]!.preSql"
                  disabled
                  :rows="3"
                  :maxlength="4000"
                  show-count
              /></label>
              <label><span>后置SQL</span><TextArea
                  v-model:value="configurations[entry.id]!.postSql"
                  disabled
                  :rows="3"
                  :maxlength="4000"
                  show-count
              /></label>
              <small>受管表输出暂不执行前后置 SQL；历史配置保留展示，非空配置会在任务检查时阻止发布。</small>
              <Button v-if="editable && (configurations[entry.id]!.preSql || configurations[entry.id]!.postSql)" @click="configurations[entry.id]!.preSql = ''; configurations[entry.id]!.postSql = ''">清除历史前后置 SQL</Button>
            </div>
          </template>
        </Form>
        <Button v-if="record" @click="openDesigner">查看完整任务流程</Button>
      </Spin>
      <template #footer>
        <Button :disabled="busy" @click="close">关闭</Button><Button v-if="record && canEditSource && !sourceEditing" @click="sourceEditing = true">修改任务配置</Button><Button
          v-if="development && canEditDev"
          type="primary"
          @click="editDevelopment"
        >
          修改任务配置
</Button><Button
          v-if="canSaveNode"
          type="primary"
          :loading="busy"
          @click="save"
        >
          保存
        </Button>
      </template>
    </Modal>
    <DevTaskForm
      v-if="devEditing"
      ref="devForm"
      @saved="developmentSaved"
      @cancelled="devEditing = false"
    />
  </ConfigProvider>
</template>
