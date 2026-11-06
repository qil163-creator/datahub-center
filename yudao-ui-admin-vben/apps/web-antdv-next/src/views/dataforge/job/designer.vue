<script setup lang="ts">
import type { JobNode } from './graph-model';
import type { PaletteCategory } from './task-palette';

import type { JobApi as Api } from '#/api/dataforge/job';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';
import { onBeforeRouteLeave, useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { confirm, Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  ConfigProvider,
  Input,
  message,
  Modal,
  Spin,
} from 'antdv-next';

import { getDevDirList, getDevTask, updateDevTaskStatus } from '#/api/dataforge/dev';
import {
  getIntegration,
  getIntegrationCategories,
  setIntegrationStatus,
} from '#/api/dataforge/integration';
import {
  checkJob,
  getJob,
  getJobTaskOptions,
  updateJob,
} from '#/api/dataforge/job';
import { useObjectRequest } from '#/hooks/use-object-request';

import { useFullSyncScope } from '../fullsync/use-scope';
import ReferenceIcon from '../integration/modules/reference-icon.vue';
import { jobTaskIcon } from './graph-model';
import JobFlow from './modules/job-flow.vue';
import JobForm from './modules/job-form.vue';
import JobTaskEditor from './modules/job-task-editor.vue';
import { taskPalette } from './task-palette';

import './designer.css';

defineOptions({ name: 'DataforgeJobDesigner' });
const route = useRoute();
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const can = (action: string) => hasAccessByCodes([`dataforge:job:${action}`]);
const id = computed(() =>
  typeof route.params.id === 'string' && /^\d+$/.test(route.params.id)
    ? route.params.id
    : '',
);
const job = ref<Api.Job>();
const tasks = ref<Api.TaskOption[]>([]);
const taskCategories = ref<PaletteCategory[]>([]);
const draft = ref('');
const saved = ref('');
const loading = ref(false);
const saving = ref(false);
const checking = ref(false);
const changingTaskStatus = ref(false);
const failed = ref('');
const paletteFailed = ref(false);
const search = ref('');
const collapsed = ref<string[]>([]);
const configOpen = ref(false);
const selected = ref<JobNode>();
const nodeOpen = ref(false);
const checked = ref(false);
const checkLines = ref<string[]>([]);
const flow = ref<InstanceType<typeof JobFlow>>();
const { active, scope } = useFullSyncScope(() => id.value);
const request = useObjectRequest(() => scope.value);
const saveRequest = useObjectRequest(() => scope.value);
const checkRequest = useObjectRequest(() => scope.value);
const editable = computed(
  () =>
    !!job.value &&
    can('update') &&
    job.value.status === 0 &&
    !saving.value &&
    !checking.value &&
    !changingTaskStatus.value,
);
const dirty = computed(() => !!job.value && draft.value !== saved.value);
const configuredJob = computed(() =>
  job.value ? { ...job.value, flowJson: draft.value } : undefined,
);
const groups = computed(() => taskPalette(tasks.value, taskCategories.value, search.value, collapsed.value));

function toggle(key: string) {
  collapsed.value = collapsed.value.includes(key)
    ? collapsed.value.filter((value) => value !== key)
    : [...collapsed.value, key];
}
function taskNode(task: Api.TaskOption) {
  return {
    priority: 'MEDIUM' as const,
    taskId: task.id,
    name: task.name,
    kind: task.kind,
    status: task.status,
    description: task.description,
    taskType: task.taskType,
    dbType: task.dbType,
  };
}
function drag(event: DragEvent, task: Api.TaskOption) {
  if (!editable.value) {
    event.preventDefault();
    return;
  }
  event.dataTransfer?.setData(
    'application/qdata-job-task',
    JSON.stringify(taskNode(task)),
  );
  if (event.dataTransfer) event.dataTransfer.effectAllowed = 'copy';
}
function canToggleTask(node: JobNode) {
  return (
    editable.value &&
    hasAccessByCodes([
      node.kind === 'INTEGRATION'
        ? 'dataforge:integration:update'
        : 'dataforge:dev-task:save',
    ])
  );
}
function toggleTaskStatus(node: JobNode) {
  if (!canToggleTask(node)) return;
  const scopeAtOpen = scope.value;
  const status = node.status === 1 ? 0 : 1;
  Modal.confirm({
    title: `${status === 1 ? '上线' : '下线'}任务`,
    content: `将${status === 1 ? '上线' : '下线'}“${node.name}”，所有引用此源任务的作业都会看到状态变化。`,
    async onOk() {
      if (scopeAtOpen !== scope.value || !canToggleTask(node)) return;
      changingTaskStatus.value = true;
      try {
        if (node.kind === 'INTEGRATION') {
          const task = await getIntegration(node.taskId);
          if (scopeAtOpen !== scope.value) return;
          await setIntegrationStatus({
            id: task.id,
            revision: task.revision,
            status,
          });
          const savedTask = await getIntegration(node.taskId);
          if (scopeAtOpen === scope.value)
            taskSaved({
              ...node,
              status: savedTask.status,
              name: savedTask.name,
              description: savedTask.description,
            });
        } else {
          await updateDevTaskStatus(node.taskId, status);
          const task = await getDevTask(node.taskId);
          if (scopeAtOpen === scope.value)
            taskSaved({
              ...node,
              status: task.status,
              name: task.name || node.name,
              description: task.description,
            });
        }
      } finally {
        changingTaskStatus.value = false;
      }
    },
  });
}
function inspect(node: JobNode) {
  selected.value = node;
  nodeOpen.value = true;
}
function taskSaved(node: JobNode) {
  flow.value?.updateTask(node);
  tasks.value = tasks.value.map((task) =>
    task.kind === node.kind && String(task.id) === String(node.taskId)
      ? {
          ...task,
          name: node.name,
          status: node.status,
          description: node.description,
        }
      : task,
  );
}
async function load() {
  if (!active.value || !id.value || !can('query')) return;
  const current = request.begin();
  loading.value = true;
  failed.value = '';
  paletteFailed.value = false;
  try {
    const [record, options, integrationCategories, developmentDirectories] = await Promise.allSettled([
      getJob(id.value),
      getJobTaskOptions(),
      getIntegrationCategories(),
      getDevDirList(),
    ]);
    if (!current()) return;
    if (record.status === 'rejected') {
      failed.value = '作业加载失败，请重试';
      return;
    }
    job.value = record.value;
    draft.value = record.value.flowJson || '{"nodes":[],"edges":[]}';
    saved.value = draft.value;
    tasks.value = options.status === 'fulfilled' ? options.value : [];
    taskCategories.value = integrationCategories.status === 'fulfilled' ? integrationCategories.value.map((item) => ({ ...item, kind: 'INTEGRATION' as const })) : [];
    if (developmentDirectories.status === 'fulfilled') {
      const add = (dirs: typeof developmentDirectories.value) => {
        for (const item of dirs) {
          if (item.id !== undefined) taskCategories.value.push({ id: item.id, parentId: item.parentId, name: item.name || '未分类', kind: 'DEVELOPMENT' });
          add(item.children ?? []);
        }
      };
      add(developmentDirectories.value);
    }
    collapsed.value = tasks.value.filter((task) => task.kind === 'INTEGRATION').map((task) => `INTEGRATION/${task.categoryId ?? 'uncategorized'}`);
    paletteFailed.value = options.status === 'rejected';
  } finally {
    if (current()) loading.value = false;
  }
}
async function save() {
  if (!job.value || !editable.value) return;
  const current = saveRequest.begin();
  const record = job.value;
  const content = draft.value;
  saving.value = true;
  try {
    await updateJob({
      id: record.id,
      revision: record.revision,
      categoryId: record.categoryId,
      name: record.name,
      ownerId: record.ownerId,
      ownerPhone: record.ownerPhone,
      strategy: record.strategy,
      cronExpression: record.cronExpression,
      description: record.description,
      flowJson: content,
    });
    const refreshed = await getJob(record.id);
    if (!current()) return;
    job.value = refreshed;
    saved.value = content;
    message.success('作业流程已保存');
  } finally {
    if (current()) saving.value = false;
  }
}
async function check() {
  if (!job.value || checking.value || saving.value) return;
  const current = checkRequest.begin();
  const content = draft.value;
  checking.value = true;
  try {
    const result = await checkJob({ flowJson: content });
    if (!current() || content !== draft.value) return;
    checkLines.value =
      result.errors.length > 0
        ? result.errors
        : [
            result.valid && result.executable
              ? '检查通过，作业流程可执行'
              : '流程结构检查通过，任务执行绑定尚未就绪',
          ];
    checked.value = true;
  } finally {
    if (current()) checking.value = false;
  }
}
function configured(value: Api.Job) {
  job.value = value;
  draft.value = value.flowJson || draft.value;
  saved.value = draft.value;
}
async function discard() {
  if (!dirty.value) return true;
  try {
    await confirm({
      title: '作业流程尚未保存',
      content: '确认离开并丢弃未保存的修改？',
      confirmText: '确认离开',
      cancelText: '继续编辑',
      icon: 'warning',
    });
    return true;
  } catch {
    return false;
  }
}
function beforeUnload(event: BeforeUnloadEvent) {
  if (dirty.value) event.preventDefault();
}
onBeforeRouteLeave(discard);
onMounted(() => window.addEventListener('beforeunload', beforeUnload));
onBeforeUnmount(() => window.removeEventListener('beforeunload', beforeUnload));
watch(draft, () => {
  checked.value = false;
});
watch(
  scope,
  () => {
    job.value = undefined;
    tasks.value = [];
    draft.value = '';
    saved.value = '';
    loading.value = false;
    saving.value = false;
    checking.value = false;
    configOpen.value = false;
    nodeOpen.value = false;
    selected.value = undefined;
    checked.value = false;
    failed.value = '';
    void load();
  },
  { immediate: true },
);
</script>

<template>
  <ConfigProvider
    :theme="{
      inherit: false,
      token: { colorPrimary: '#2663ff', borderRadius: 2, fontSize: 14 },
    }"
  >
    <Page
      auto-content-height
      content-class="flex min-h-0 flex-col overflow-hidden p-0"
    >
      <div class="qj-designer">
        <Alert
          v-if="!id || !can('query')"
          type="warning"
          title="作业编号无效或没有查询权限"
          show-icon
        />
        <Alert v-if="failed" type="error" :title="failed" show-icon>
          <template #action>
            <Button @click="load">重试</Button>
          </template>
        </Alert>
        <Spin :spinning="loading" class="qj-designer-spin">
          <header class="qj-design-header">
            <div class="qj-design-title">
              <span></span>{{ job?.name || '作业管理配置' }}
            </div>
            <div class="qj-design-actions">
              <Button
                type="primary"
                :disabled="!editable"
                :loading="saving"
                @click="save"
              >
                <ReferenceIcon name="save" />任务保存
              </Button>
              <Button :disabled="!editable" @click="configOpen = true">
                <ReferenceIcon name="settings" />任务配置
              </Button>
              <Button
                :disabled="!job || !can('query')"
                :loading="checking"
                @click="check"
              >
                <ReferenceIcon name="check" />任务检查
              </Button>
              <Button @click="router.push('/develop/job')">
                <ReferenceIcon name="back" />返回列表
              </Button>
            </div>
          </header>
          <div v-if="job" class="qj-design-layout">
            <aside class="qj-design-palette" aria-label="可编排的任务">
              <Input
                v-model:value="search"
                placeholder="请输入类目/任务名称检索"
                allow-clear
              >
                <template #prefix><ReferenceIcon name="search" /></template>
              </Input>
              <Alert
                v-if="paletteFailed"
                type="warning"
                title="任务列表加载失败"
                show-icon
              >
                <template #action>
                  <Button size="small" @click="load">重试</Button>
                </template>
              </Alert>
              <div class="qj-palette-tree subtle-tree-scrollbar">
                <section v-for="group in groups" :key="group.kind">
                  <button
                    class="qj-palette-group"
                    :aria-expanded="!collapsed.includes(group.kind)"
                    @click="toggle(group.kind)"
                  >
                    <span
                      class="qj-chevron"
                      :class="{ collapsed: collapsed.includes(group.kind) }"
                      >⌄</span><ReferenceIcon name="folder" /><span>{{ group.name }}</span><span class="qj-palette-count">({{ group.count }})</span>
                  </button>
                  <template
                    v-if="!!search.trim() || !collapsed.includes(group.kind)"
                  >
                    <template v-for="row in group.rows" :key="row.key">
                      <button v-if="row.type === 'category'" class="qj-palette-group" :style="{ paddingLeft: `${row.depth * 18}px` }" :aria-expanded="!!search.trim() || !collapsed.includes(row.key)" @click="toggle(row.key)">
                        <span class="qj-chevron" :class="{ collapsed: !search.trim() && collapsed.includes(row.key) }">⌄</span><ReferenceIcon name="folder" /><span>{{ row.name }}</span><span class="qj-palette-count">({{ row.count }})</span>
                      </button>
                      <button v-else class="qj-palette-task" :style="{ paddingLeft: `${row.depth * 18 + 5}px` }" :title="row.task.name" :draggable="editable" :aria-label="`添加任务 ${row.task.name}`" @dragstart="drag($event, row.task)" @dblclick="flow?.addTask(taskNode(row.task))" @keydown.enter="flow?.addTask(taskNode(row.task))">
                        <img :src="jobTaskIcon(row.task)" alt="" /><span>{{ row.task.name }}</span>
                      </button>
                    </template>
                  </template>
                </section>
                <div v-if="!tasks.length && !paletteFailed" class="qj-empty">
                  暂无可编排任务
                </div>
              </div>
            </aside>
            <main class="qj-design-main">
              <JobFlow
                ref="flow"
                v-model:flow-json="draft"
                :task-options="tasks"
                :readonly="!editable"
                :can-toggle-task="canToggleTask"
                @toggle-status="toggleTaskStatus"
                @inspect="inspect"
                @error="failed = $event"
              />
              <section v-if="checked" class="qj-check-panel">
                <div>
                  <strong>检查消息</strong><button aria-label="关闭检查消息" @click="checked = false">
                    ×
                  </button>
                </div>
                <p v-for="(line, index) in checkLines" :key="index">
                  {{ line }}
                </p>
              </section>
            </main>
          </div>
        </Spin>
      </div>
      <JobForm
        v-model:open="configOpen"
        :initial-job="configuredJob"
        @success="configured"
      />
      <JobTaskEditor
        v-model:open="nodeOpen"
        :node="selected"
        :readonly="!editable"
        @saved="taskSaved"
      />
    </Page>
  </ConfigProvider>
</template>
