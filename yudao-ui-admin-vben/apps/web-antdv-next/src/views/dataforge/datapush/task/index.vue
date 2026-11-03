<script lang="ts" setup>
import type { PageResult } from '@vben/request';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { PushCategory } from '#/api/dataforge/datapush/category';
import type { DataforgeDataPushTaskApi } from '#/api/dataforge/datapush/task';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onMounted,
  ref,
  watch,
} from 'vue';

import { Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Input, message, Tag, Tree } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import { getPushCategoryTree } from '#/api/dataforge/datapush/category';
import {
  cancelDataPushTask,
  copyDataPushTask,
  deleteDataPushTask,
  getDataPushTaskPage,
  handRunDataPushTask,
  releaseDataPushTask,
  stopDataPushTask,
} from '#/api/dataforge/datapush/task';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { $t } from '#/locales';

import {
  collectExpandableKeys,
  isTreeExpanded,
} from '../../common/tree-expansion';
import { buildCategoryTree } from './category-model';
import {
  CHANNEL_TYPE_OPTIONS,
  optionColor,
  optionLabel,
  PUSH_MODE,
  PUSH_MODE_OPTIONS,
  PUSH_TYPE_OPTIONS,
  RELEASE_STATUS,
  RUN_STATUS_OPTIONS,
  STATUS_OPTIONS,
  TASK_TYPE,
  useGridColumns,
  useGridFormSchema,
} from './data';
import { awaitLatestPageRequest } from './latest-page-request';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';
import Log from './modules/log.vue';
import Preview from './modules/preview.vue';
import Watermark from './modules/watermark.vue';
import {
  hasUnknownOutcome,
  isExecutionBlocked,
  runStateLabel,
} from './run-state';

defineOptions({ name: 'DataforgeDataPushTask' });

const POLL_MS = 5000;

const access = useAccessStore();
const user = useUserStore();
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]),
);
const formVisible = ref(false);
const formData = ref<{
  categoryId?: number | string;
  id?: DataforgeDataPushTaskApi.Task['id'];
}>();
const categories = ref<PushCategory[]>([]);
const categoryKeyword = ref('');
const categoryCollapsed = ref(false);
const expandedKeys = ref<string[]>(['root']);
const selectedKeys = ref<string[]>(['root']);
const categoryId = ref<string>();
const treeLoading = ref(false);
const treeError = ref(false);
let treeSeq = 0;
let alive = true;
let activatedOnce = false;
let latestPageRequest:
  | Promise<PageResult<DataforgeDataPushTaskApi.Task>>
  | undefined;
const categoryTree = computed(() => [
  {
    key: 'root',
    title: '数据推送类目',
    children: buildCategoryTree(categories.value, categoryKeyword.value),
  },
]);
const allExpanded = computed(() =>
  isTreeExpanded(categoryTree.value, expandedKeys.value),
);
function toggleExpandAll() {
  expandedKeys.value = allExpanded.value
    ? []
    : collectExpandableKeys(categoryTree.value);
}

async function loadCategories() {
  const seq = ++treeSeq;
  const started = identity.value;
  treeLoading.value = true;
  treeError.value = false;
  try {
    const rows = await getPushCategoryTree();
    if (alive && seq === treeSeq && started === identity.value) {
      const expand = allExpanded.value;
      categories.value = rows;
      if (expand || categoryKeyword.value.trim())
        expandedKeys.value = collectExpandableKeys(categoryTree.value);
      if (
        categoryId.value &&
        !rows.some((item) => String(item.id) === categoryId.value)
      ) {
        categoryId.value = undefined;
        selectedKeys.value = ['root'];
        void gridApi.reload();
      }
    }
  } catch {
    if (alive && seq === treeSeq && started === identity.value) {
      categories.value = [];
      treeError.value = true;
    }
  } finally {
    if (alive && seq === treeSeq && started === identity.value)
      treeLoading.value = false;
  }
}

function selectCategory(keys: (number | string)[]) {
  if (keys.length === 0) return;
  const key = String(keys[0]);
  selectedKeys.value = [key];
  categoryId.value = key === 'root' ? undefined : key;
  void gridApi.reload();
}

watch(categoryKeyword, (keyword) => {
  if (keyword.trim())
    expandedKeys.value = [
      'root',
      ...categories.value.map((item) => String(item.id)),
    ];
});
onMounted(() => {
  void loadCategories();
});
onActivated(() => {
  if (activatedOnce) void loadCategories();
  activatedOnce = true;
});

function closeForm() {
  formVisible.value = false;
  formData.value = undefined;
}

watch(identity, () => {
  closeForm();
  stopPoll();
  ++treeSeq;
  latestPageRequest = undefined;
  categories.value = [];
  categoryId.value = undefined;
  selectedKeys.value = ['root'];
  expandedKeys.value = ['root'];
  categoryKeyword.value = '';
  void loadCategories();
});

const [DetailModal, detailModalApi] = useVbenModal({
  connectedComponent: Detail,
  destroyOnClose: true,
});

const [LogModal, logModalApi] = useVbenModal({
  connectedComponent: Log,
  destroyOnClose: true,
});

const [WatermarkModal, watermarkModalApi] = useVbenModal({
  connectedComponent: Watermark,
  destroyOnClose: true,
});

const [PreviewModal, previewModalApi] = useVbenModal({
  connectedComponent: Preview,
  destroyOnClose: true,
});

let pollTimer: null | ReturnType<typeof setInterval> = null;

function stopPoll() {
  if (pollTimer) {
    clearInterval(pollTimer);
    pollTimer = null;
  }
}

function syncPoll(list: DataforgeDataPushTaskApi.Task[]) {
  const running = list.some((row) => row.running);
  if (running && !pollTimer) {
    pollTimer = setInterval(() => {
      gridApi.query();
    }, POLL_MS);
  } else if (!running) {
    stopPoll();
  }
}

onBeforeUnmount(() => {
  alive = false;
  stopPoll();
  treeSeq++;
});

function handleRefresh() {
  gridApi.query();
}

function handleCreate() {
  const selected = categories.value.find(
    (item) => String(item.id) === categoryId.value,
  );
  formData.value = {
    categoryId: selected?.status === 0 ? categoryId.value : undefined,
  };
  formVisible.value = true;
  stopPoll();
}

function handleEdit(row: DataforgeDataPushTaskApi.Task) {
  formData.value = { id: row.id };
  formVisible.value = true;
  stopPoll();
}

function handleFormSuccess() {
  closeForm();
  handleRefresh();
}

function handleFormClose() {
  closeForm();
  handleRefresh();
}

function handleDetail(row: DataforgeDataPushTaskApi.Task) {
  detailModalApi.setData({ id: row.id }).open();
}

function handleLog(row: DataforgeDataPushTaskApi.Task) {
  logModalApi.setData(row).open();
}

function handleWatermark(row: DataforgeDataPushTaskApi.Task) {
  watermarkModalApi.setData(row).open();
}

function handlePreview(row: DataforgeDataPushTaskApi.Task) {
  previewModalApi.setData(row).open();
}

async function handleCopy(row: DataforgeDataPushTaskApi.Task) {
  await copyDataPushTask(row.id!);
  message.success('已复制为未发布任务');
  handleRefresh();
}

function isReleased(row: DataforgeDataPushTaskApi.Task) {
  return row.status === RELEASE_STATUS.RELEASED;
}

async function handleRelease(row: DataforgeDataPushTaskApi.Task) {
  const hideLoading = message.loading({ content: '正在发布...', duration: 0 });
  try {
    await releaseDataPushTask(row.id!);
    message.success(
      row.taskType === TASK_TYPE.ONCE
        ? '已发布并触发执行'
        : '已发布，按 Cron 排期执行',
    );
    handleRefresh();
  } finally {
    hideLoading();
  }
}

async function handleCancel(row: DataforgeDataPushTaskApi.Task) {
  const hideLoading = message.loading({
    content: '正在取消发布...',
    duration: 0,
  });
  try {
    await cancelDataPushTask(row.id!);
    message.success(
      row.running ? '已取消发布并请求停止，发送后的结果需核查' : '已取消发布',
    );
    handleRefresh();
  } finally {
    hideLoading();
  }
}

async function handleHandRun(row: DataforgeDataPushTaskApi.Task) {
  const hideLoading = message.loading({
    content: '正在提交执行...',
    duration: 0,
  });
  try {
    await handRunDataPushTask(row.id!);
    message.success('已触发执行，可在执行记录中查看进度');
    handleRefresh();
  } finally {
    hideLoading();
  }
}

async function handleStop(row: DataforgeDataPushTaskApi.Task) {
  const hideLoading = message.loading({ content: '正在停止...', duration: 0 });
  try {
    await stopDataPushTask(row.id!);
    message.success('已发送停止指令');
    handleRefresh();
  } finally {
    hideLoading();
  }
}

async function handleDelete(row: DataforgeDataPushTaskApi.Task) {
  const hideLoading = message.loading({
    content: $t('ui.actionMessage.deleting', [row.name]),
    duration: 0,
  });
  try {
    await deleteDataPushTask(row.id!);
    message.success($t('ui.actionMessage.deleteSuccess', [row.name]));
    handleRefresh();
  } finally {
    hideLoading();
  }
}

const [Grid, gridApi] = useVbenVxeGrid({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    proxyConfig: {
      ajax: {
        query: async ({ page }, formValues) => {
          const started = identity.value;
          const request = getDataPushTaskPage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
            categoryId: categoryId.value,
          });
          latestPageRequest = request;
          const settled = await awaitLatestPageRequest(
            request,
            () => latestPageRequest,
            () => alive && started === identity.value,
          );
          if (!settled.current) return { list: [], total: 0 };
          if (!formVisible.value) syncPoll(settled.result.list ?? []);
          return settled.result;
        },
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: { refresh: true, search: true },
  } as VxeTableGridOptions<DataforgeDataPushTaskApi.Task>,
});
</script>

<template>
  <Page auto-content-height>
    <Form
      v-if="formVisible"
      :key="`form:${identity}`"
      :initial-data="formData"
      @close="handleFormClose"
      @success="handleFormSuccess"
    />
    <DetailModal :key="`detail:${identity}`" />
    <LogModal :key="`log:${identity}`" @success="handleRefresh" />
    <PreviewModal :key="`preview:${identity}`" />
    <WatermarkModal :key="`watermark:${identity}`" @success="handleRefresh" />
    <div
      v-show="!formVisible"
      class="push-layout"
      :class="{ 'directory-collapsed': categoryCollapsed }"
    >
      <aside
        v-show="!categoryCollapsed"
        id="push-directory"
        class="push-directory"
      >
        <div class="flex items-center gap-2">
          <Input
            v-model:value="categoryKeyword"
            class="min-w-0 flex-1"
            allow-clear
            placeholder="请输入数据推送类目名称"
            aria-label="搜索数据推送类目"
          >
            <template #prefix><IconifyIcon icon="lucide:search" /></template>
          </Input>
          <TreeToolbar
            :expanded="allExpanded"
            :loading="treeLoading"
            @toggle-expand="toggleExpandAll"
            @refresh="loadCategories"
          />
        </div>
        <Alert v-if="treeError" type="warning" message="类目读取失败">
          <template #action
            ><Button size="small" @click="loadCategories"
              >重试</Button
            ></template
          >
        </Alert>
        <Tree
          v-model:expanded-keys="expandedKeys"
          class="subtle-tree-scrollbar"
          :selected-keys="selectedKeys"
          :tree-data="categoryTree"
          :auto-expand-parent="false"
          :disabled="treeLoading"
          block-node
          @select="selectCategory"
        >
          <template #titleRender="node">
            <span class="directory-node">
              <IconifyIcon
                class="directory-icon"
                :icon="
                  node.children?.length || node.key === 'root'
                    ? 'lucide:folder-open'
                    : 'lucide:file-text'
                "
              />
              <span class="directory-name" :title="String(node.title)">{{
                node.title
              }}</span>
            </span>
          </template>
        </Tree>
      </aside>
      <SidebarCollapse
        :collapsed="categoryCollapsed"
        label="类目树"
        @toggle="categoryCollapsed = !categoryCollapsed"
      />
      <div class="push-main">
        <Grid :key="identity" table-title="推送任务">
          <template #toolbar-tools>
            <TableAction
              :actions="[
                {
                  label: $t('common.create'),
                  type: 'primary',
                  icon: ACTION_ICON.ADD,
                  auth: ['dataforge:data-push:create'],
                  onClick: handleCreate,
                },
              ]"
            />
          </template>
          <template #name="{ row }">
            <span class="block whitespace-pre-wrap break-words">{{
              row.name || '-'
            }}</span>
          </template>
          <template #channelType="{ row }">
            <Tag :color="optionColor(CHANNEL_TYPE_OPTIONS, row.channelType)">
              {{ optionLabel(CHANNEL_TYPE_OPTIONS, row.channelType) }}
            </Tag>
          </template>
          <template #pushType="{ row }">
            <Tag :color="optionColor(PUSH_TYPE_OPTIONS, row.pushType)">
              {{ optionLabel(PUSH_TYPE_OPTIONS, row.pushType) }}
            </Tag>
          </template>
          <template #pushMode="{ row }">
            <Tag :color="optionColor(PUSH_MODE_OPTIONS, row.pushMode)">
              {{ optionLabel(PUSH_MODE_OPTIONS, row.pushMode) }}
            </Tag>
          </template>
          <template #status="{ row }">
            <Tag :color="optionColor(STATUS_OPTIONS, row.status)">
              {{ optionLabel(STATUS_OPTIONS, row.status) }}
            </Tag>
          </template>
          <template #running="{ row }">
            <Tag
              :color="
                hasUnknownOutcome(row)
                  ? 'warning'
                  : row.running
                    ? 'processing'
                    : 'default'
              "
            >
              {{ runStateLabel(row) }}
            </Tag>
          </template>
          <template #lastRunStatus="{ row }">
            <Tag
              v-if="row.lastRunStatus"
              :color="optionColor(RUN_STATUS_OPTIONS, row.lastRunStatus)"
            >
              {{ optionLabel(RUN_STATUS_OPTIONS, row.lastRunStatus) }}
            </Tag>
            <span v-else>-</span>
          </template>
          <template #actions="{ row }">
            <TableAction
              :actions="[
                {
                  label: $t('common.edit'),
                  type: 'link',
                  icon: ACTION_ICON.EDIT,
                  auth: ['dataforge:data-push:update'],
                  ifShow: !isReleased(row) && !isExecutionBlocked(row),
                  onClick: handleEdit.bind(null, row),
                },
              ]"
              :drop-down-actions="[
                {
                  label: '试跑预览',
                  type: 'link',
                  icon: ACTION_ICON.SEARCH,
                  auth: ['dataforge:data-push:query'],
                  onClick: handlePreview.bind(null, row),
                },
                {
                  label: '执行记录',
                  type: 'link',
                  icon: 'lucide:history',
                  auth: ['dataforge:data-push:query'],
                  onClick: handleLog.bind(null, row),
                },
                {
                  label: '水位',
                  type: 'link',
                  icon: ACTION_ICON.FILTER,
                  auth: ['dataforge:data-push:query'],
                  ifShow: row.pushMode === PUSH_MODE.INCREMENT,
                  onClick: handleWatermark.bind(null, row),
                },
                {
                  label: row.running ? '停止' : '执行',
                  disabled: hasUnknownOutcome(row),
                  type: 'link',
                  danger: !!row.running,
                  icon: row.running ? 'lucide:square' : 'lucide:play',
                  auth: ['dataforge:data-push:run'],
                  popConfirm: {
                    title: row.running
                      ? `确认停止「${row.name}」吗？`
                      : `确认立即执行「${row.name}」吗？`,
                    confirm: row.running
                      ? handleStop.bind(null, row)
                      : handleHandRun.bind(null, row),
                  },
                },
                {
                  label: isReleased(row) ? '取消发布' : '发布',
                  disabled: !isReleased(row) && isExecutionBlocked(row),
                  type: 'link',
                  danger: isReleased(row),
                  icon: isReleased(row) ? 'lucide:square' : 'lucide:send',
                  auth: ['dataforge:data-push:release'],
                  popConfirm: {
                    title: isReleased(row)
                      ? row.running
                        ? `确认取消发布「${row.name}」吗？将停止后续调度并请求取消当前执行；已发送的结果需核查，不能直接重跑。`
                        : `确认取消发布「${row.name}」吗？将停止后续调度。`
                      : row.taskType === TASK_TYPE.ONCE
                        ? `确认发布「${row.name}」吗？发布后将立即执行一次。`
                        : `确认发布「${row.name}」吗？将按 Cron 周期调度执行。`,
                    confirm: isReleased(row)
                      ? handleCancel.bind(null, row)
                      : handleRelease.bind(null, row),
                  },
                },
                {
                  label: '详情',
                  type: 'link',
                  icon: ACTION_ICON.VIEW,
                  auth: ['dataforge:data-push:query'],
                  onClick: handleDetail.bind(null, row),
                },
                {
                  label: '复制',
                  type: 'link',
                  icon: ACTION_ICON.COPY,
                  auth: ['dataforge:data-push:create'],
                  onClick: handleCopy.bind(null, row),
                },
                {
                  label: $t('common.delete'),
                  type: 'link',
                  danger: true,
                  icon: ACTION_ICON.DELETE,
                  auth: ['dataforge:data-push:delete'],
                  ifShow: !isReleased(row) && !isExecutionBlocked(row),
                  popConfirm: {
                    title: $t('ui.actionMessage.deleteConfirm', [row.name]),
                    confirm: handleDelete.bind(null, row),
                  },
                },
              ]"
            />
          </template>
        </Grid>
      </div>
    </div>
  </Page>
</template>

<style scoped>
.push-layout {
  position: relative;
  display: flex;
  gap: 0;
  height: 100%;
  min-height: 0;
}

.push-directory {
  display: flex;
  flex: 0 0 clamp(220px, 16.6667%, 300px);
  flex-direction: column;
  min-width: 0;
  padding: 12px;
  overflow: hidden;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.push-directory :deep(.ant-tree) {
  flex: 1;
  min-height: 0;
  margin-top: 12px;
  overflow: auto;
  font-size: 14px;
  background: transparent;
}

.push-directory :deep(.ant-tree-treenode) {
  width: 100%;
}

.push-directory :deep(.ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
}

.push-directory :deep(.ant-tree-title) {
  display: block;
  width: 100%;
}

.push-directory :deep(.ant-alert) {
  margin-top: 8px;
}

.directory-node {
  display: flex;
  gap: 8px;
  align-items: center;
  width: 100%;
  min-height: 28px;
}

.directory-icon {
  flex-shrink: 0;
  color: #688bff;
}

.directory-name {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.push-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
}

@media (width <= 640px) {
  .push-layout {
    flex-direction: column;
    height: auto;
    min-height: 900px;
  }

  .push-directory {
    flex: none;
    width: 100%;
    height: 190px;
  }

  .push-layout > :deep(.sidebar-collapse) {
    width: 100%;
    min-width: 0;
    height: 12px;
  }

  .push-layout > :deep(.sidebar-collapse svg) {
    transform: rotate(90deg);
  }

  .push-main {
    flex: none;
    height: 700px;
  }
}

:deep(.datapush-action-column),
:deep(.datapush-action-column .vxe-cell) {
  overflow: visible !important;
}
</style>
