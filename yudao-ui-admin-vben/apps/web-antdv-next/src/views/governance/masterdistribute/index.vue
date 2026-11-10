<script lang="ts" setup>
import type { PageResult } from '@vben/request';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { GovernanceMasterDistributeApi } from '#/api/governance/masterdistribute';
import type { MasterPushCategory } from '#/api/governance/masterdistribute/category';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { Page, useVbenDrawer, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Input, message, Switch, Tag, Tooltip, Tree } from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/business-vxe-table';
import {
  cancelMasterDistribute,
  deleteMasterDistribute,
  getMasterDistributePage,
  handRunMasterDistribute,
  releaseMasterDistribute,
  retryMasterDistribute,
  updateMasterDistributeStatus,
} from '#/api/governance/masterdistribute';
import { getMasterPushCategoryTree } from '#/api/governance/masterdistribute/category';
import SidebarCollapse from '#/components/sidebar-collapse/index.vue';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import { createObjectRequestGuard } from '#/hooks/object-request-guard';
import { $t } from '#/locales';

import { useTreeExpansion } from '../common/use-tree-expansion';
import { buildCategoryTree } from './category-model';
import {
  DISTRIBUTE_TYPE_OPTIONS,
  isRunBlocked,
  optionColor,
  optionLabel,
  RUN_STATUS_OPTIONS,
  runBlockedReason,
  STATUS_OPTIONS,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Detail from './modules/detail.vue';
import Form from './modules/form.vue';
import Log from './modules/log.vue';
import PushDetail from './modules/push-detail.vue';
import { pushModeLabel, pushScopeLabel } from './push-config';

import '../mastercommon/qdata-master.css';

defineOptions({ name: 'GovernanceMasterDistribute' });

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const detailId = ref<number | string>();
const selected = ref<GovernanceMasterDistributeApi.Distribute[]>([]);
const owner = () =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
  ]);
let active = true;
let lifetime = 0;
let pollTimer: ReturnType<typeof setTimeout> | undefined;
const categories = ref<MasterPushCategory[]>([]);
const categoryKeyword = ref('');
const categoryCollapsed = ref(false);
const categoryId = ref<string>();
const selectedKeys = ref<string[]>(['root']);
const expandedKeys = ref<string[]>(['root']);
const treeLoading = ref(false);
const treeError = ref(false);
let treeSeq = 0;
const categoryTree = computed(() => [{
  key: 'root',
  title: '主数据推送类目',
  children: buildCategoryTree(categories.value, categoryKeyword.value),
}]);

async function loadCategories() {
  const seq = ++treeSeq;
  const identity = owner();
  const pageLifetime = lifetime;
  treeLoading.value = true;
  treeError.value = false;
  const current = () => active && seq === treeSeq && identity === owner() && pageLifetime === lifetime;
  try {
    const rows = await getMasterPushCategoryTree();
    if (!current()) return;
    categories.value = rows;
    if (categoryId.value && !rows.some((item) => String(item.id) === categoryId.value)) {
      categoryId.value = undefined;
      selectedKeys.value = ['root'];
      requestGuard.begin();
      void handleRefresh(true);
    }
  } catch {
    if (current()) {
      categories.value = [];
      treeError.value = true;
    }
  } finally {
    if (current()) treeLoading.value = false;
  }
}

function selectCategory(keys: (number | string)[]) {
  if (keys.length === 0) return;
  const key = String(keys[0]);
  selectedKeys.value = [key];
  categoryId.value = key === 'root' ? undefined : key;
  selected.value = [];
  gridApi.grid?.clearCheckboxRow?.();
  requestGuard.begin();
  void handleRefresh(true);
}

const requestGuard = createObjectRequestGuard(() => `${owner()}:${lifetime}`);
const emptyPage = (): PageResult<GovernanceMasterDistributeApi.Distribute> => ({
  list: [],
  total: 0,
});
let latestQuery: Promise<PageResult<GovernanceMasterDistributeApi.Distribute>> =
  Promise.resolve(emptyPage());
let nativeQuerySettled: Promise<void> | undefined;
let settleNativeQuery: (() => void) | undefined;
let refreshQueued = false;
let reloadQueued = false;
let refreshPromise: Promise<void> | undefined;

function onNativeQuerySettled() {
  settleNativeQuery?.();
  nativeQuerySettled = undefined;
  settleNativeQuery = undefined;
}

function stopPolling() {
  if (pollTimer !== undefined) clearTimeout(pollTimer);
  pollTimer = undefined;
}

function schedulePolling(
  result: PageResult<GovernanceMasterDistributeApi.Distribute>,
) {
  stopPolling();
  if (
    !active ||
    document.visibilityState === 'hidden' ||
    !result.list.some((row) => [1, 2].includes(row.blockingRunStatus ?? 0))
  )
    return;
  pollTimer = setTimeout(() => {
    void handleRefresh();
  }, 5000);
}

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

const [LogModal, logModalApi] = useVbenModal({
  connectedComponent: Log,
  destroyOnClose: true,
});

const [DetailDrawer, detailDrawerApi] = useVbenDrawer({
  connectedComponent: Detail,
  destroyOnClose: true,
});

function handleRefresh(resetPage = false) {
  if (!active) return Promise.resolve();
  refreshQueued = true;
  reloadQueued ||= resetPage;
  if (!refreshPromise) {
    refreshPromise = (async () => {
      while (refreshQueued) {
        if (!active) return;
        // VXE 在 tableLoading 时会丢弃 query；等待其 success/error 回调后补读。
        if (nativeQuerySettled) await nativeQuerySettled;
        if (!active) return;
        refreshQueued = false;
        const reload = reloadQueued;
        reloadQueued = false;
        await (reload ? gridApi.reload() : gridApi.query());
      }
    })().finally(() => {
      refreshPromise = undefined;
      if (active && refreshQueued) void handleRefresh();
    });
  }
  return refreshPromise;
}

async function handleSearch() {
  const values = await gridApi.formApi.getValues();
  gridApi.formApi.setLatestSubmissionValues(values);
  await handleRefresh(true);
}

async function handleReset() {
  await gridApi.formApi.reset();
  await handleSearch();
}

const pendingRows = reactive(new Set<string>());

function isRowPending(row: GovernanceMasterDistributeApi.Distribute) {
  return pendingRows.has(`${owner()}:${row.id}`);
}

async function runRowAction(
  row: GovernanceMasterDistributeApi.Distribute,
  action: (current: () => boolean) => Promise<void>,
) {
  const identity = owner();
  const pageLifetime = lifetime;
  const current = () =>
    active && identity === owner() && pageLifetime === lifetime;
  const key = `${identity}:${row.id}`;
  if (pendingRows.has(key)) return;
  pendingRows.add(key);
  try {
    await action(current);
    if (current()) await handleRefresh();
  } finally {
    pendingRows.delete(key);
  }
}

function handleCreate() {
  const selectedCategory = categories.value.find((item) => String(item.id) === categoryId.value);
  formModalApi.setData({ categoryId: selectedCategory?.status === 0 ? categoryId.value : undefined }).open();
}

function handleEdit(row: GovernanceMasterDistributeApi.Distribute) {
  formModalApi.setData(row).open();
}

function handleLog(row: GovernanceMasterDistributeApi.Distribute) {
  logModalApi.setData(row).open();
}

function handleDetail(row: GovernanceMasterDistributeApi.Distribute) {
  detailId.value = row.id;
}

async function handleToggle(
  row: GovernanceMasterDistributeApi.Distribute,
  enabled: boolean | number | string,
) {
  if (!hasAccessByCodes(['governance:master-distribute:release'])) return;
  await runRowAction(row, async (current) => {
    await updateMasterDistributeStatus(row.id!, enabled ? 1 : 0);
    if (current()) message.success(enabled ? '已启用' : '已禁用');
  });
}

async function handleRetry(row: GovernanceMasterDistributeApi.Distribute) {
  await runRowAction(row, async (current) => {
    await retryMasterDistribute(row.id!);
    if (current()) message.success('已受理重试，请查看推送日志');
  });
}

async function handleBatchDelete() {
  const rows = [...selected.value];
  const identity = owner();
  const pageLifetime = lifetime;
  const current = () =>
    active && identity === owner() && pageLifetime === lifetime;
  for (const row of rows) {
    if (!current()) return;
    await handleDelete(row);
  }
  if (!current()) return;
  selected.value = [];
  gridApi.grid?.clearCheckboxRow?.();
}

/** 发布：一次性任务立即执行一次，周期任务开始排期 */
async function handleRelease(row: GovernanceMasterDistributeApi.Distribute) {
  await runRowAction(row, async (current) => {
    await releaseMasterDistribute(row.id!);
    if (!current()) return;
    message.success(
      row.taskType === 1 ? '已发布并触发执行' : '已发布，按 Cron 排期执行',
    );
  });
}

async function handleCancel(row: GovernanceMasterDistributeApi.Distribute) {
  await runRowAction(row, async (current) => {
    await cancelMasterDistribute(row.id!);
    if (!current()) return;
    message.success('已取消发布');
  });
}

async function handleHandRun(row: GovernanceMasterDistributeApi.Distribute) {
  await runRowAction(row, async (current) => {
    await handRunMasterDistribute(row.id!);
    if (!current()) return;
    message.success('已受理，可在执行记录中查看等待或执行进度');
  });
}

async function handleDelete(row: GovernanceMasterDistributeApi.Distribute) {
  await runRowAction(row, async (current) => {
    const hideLoading = message.loading({
      content: $t('ui.actionMessage.deleting', [row.name]),
      duration: 0,
    });
    try {
      await deleteMasterDistribute(row.id!);
      if (!current()) return;
      message.success($t('ui.actionMessage.deleteSuccess', [row.name]));
    } finally {
      hideLoading();
    }
  });
}

const [Grid, gridApi] = useVbenVxeGrid({
  showSearchForm: true,
  formOptions: {
    schema: useGridFormSchema(),
    handleSubmit: handleSearch,
    handleReset,
  },
  gridEvents: {
    checkboxAll: ({
      records,
    }: {
      records: GovernanceMasterDistributeApi.Distribute[];
    }) => {
      selected.value = records;
    },
    checkboxChange: ({
      records,
    }: {
      records: GovernanceMasterDistributeApi.Distribute[];
    }) => {
      selected.value = records;
    },
    pageChange: () => {
      if (nativeQuerySettled) void handleRefresh();
    },
  },
  gridOptions: {
    columns: useGridColumns(),
    height: 'auto',
    keepSource: true,
    showOverflow: false,
    pagerConfig: {
      currentPage: 1,
      pageSize: 10,
      pageSizes: [10, 20, 50, 100],
    },
    proxyConfig: {
      autoLoad: true,
      ajax: {
        query: async ({ page }, formValues) => {
          nativeQuerySettled = new Promise((resolve) => {
            settleNativeQuery = resolve;
          });
          stopPolling();
          const current = requestGuard.begin();
          const query: typeof latestQuery = getMasterDistributePage({
            pageNo: page.currentPage,
            pageSize: page.pageSize,
            ...formValues,
            ...(categoryId.value ? { categoryId: categoryId.value } : {}),
          }).then(
            (result) => {
              if (!current() || !active)
                return latestQuery === query ? emptyPage() : latestQuery;
              schedulePolling(result);
              return result;
            },
            (error: unknown) => {
              if (!current() || !active)
                return latestQuery === query ? emptyPage() : latestQuery;
              throw error;
            },
          );
          latestQuery = query;
          return query;
        },
        querySuccess: onNativeQuerySettled,
        queryError: onNativeQuerySettled,
      },
    },
    rowConfig: { keyField: 'id', isHover: true },
    toolbarConfig: {
      custom: true,
      refresh: true,
      refreshOptions: { queryMethod: () => handleRefresh() },
      search: true,
    },
  } as VxeTableGridOptions<GovernanceMasterDistributeApi.Distribute>,
});

function invalidatePage(preserveCategory = false) {
  ++lifetime;
  ++treeSeq;
  if (!preserveCategory) {
    categories.value = [];
    categoryId.value = undefined;
    categoryKeyword.value = '';
    selectedKeys.value = ['root'];
    expandedKeys.value = ['root'];
  }
  treeLoading.value = false;
  treeError.value = false;
  detailId.value = undefined;
  selected.value = [];
  requestGuard.begin();
  stopPolling();
  void formModalApi.close();
  void logModalApi.close();
  void detailDrawerApi.close();
  gridApi.setGridOptions({ data: [], pagerConfig: { total: 0 } });
}

watch(
  owner,
  async () => {
    const identity = owner();
    invalidatePage();
    gridApi.formApi.setLatestSubmissionValues({});
    await gridApi.formApi.reset();
    if (active && identity === owner()) {
      void loadCategories();
      await handleRefresh(true);
    }
  },
  { flush: 'sync' },
);

function onVisibilityChange() {
  if (document.visibilityState === 'hidden') stopPolling();
  else if (active) void handleRefresh();
}
onMounted(() => {
  document.addEventListener('visibilitychange', onVisibilityChange);
  void loadCategories();
});
onActivated(() => {
  if (!active) {
    active = true;
    void loadCategories();
    void handleRefresh();
  }
});
onDeactivated(() => {
  active = false;
  invalidatePage(true);
});
onBeforeUnmount(() => {
  active = false;
  ++treeSeq;
  requestGuard.dispose();
  onNativeQuerySettled();
  stopPolling();
  document.removeEventListener('visibilitychange', onVisibilityChange);
});
const { allExpanded, toggleExpand } = useTreeExpansion(
  categoryTree,
  expandedKeys,
  categoryKeyword,
);
</script>

<template>
  <Page auto-content-height content-class="metadata-page qdata-master-page">
    <FormModal @success="handleRefresh" />
    <LogModal @success="handleRefresh" />
    <DetailDrawer />
    <PushDetail
      v-if="detailId !== undefined"
      :id="detailId"
      @back="detailId = undefined"
      @changed="handleRefresh()"
    />

    <div v-show="detailId === undefined" class="push-layout" :class="{ 'directory-collapsed': categoryCollapsed }">
      <aside v-show="!categoryCollapsed" id="master-push-directory" class="push-directory">
        <div class="flex items-center gap-2">
          <Input
            class="min-w-0 flex-1"
            v-model:value="categoryKeyword"
            allow-clear
            placeholder="请输入主数据推送类目名称"
            aria-label="搜索主数据推送类目"
          >
            <template #prefix><IconifyIcon icon="lucide:search" /></template>
          </Input>
          <TreeToolbar
            :expanded="allExpanded"
            :loading="treeLoading"
            @toggle-expand="toggleExpand"
            @refresh="loadCategories"
          />
        </div>
        <Alert v-if="treeError" type="warning" message="类目读取失败">
          <template #action><Button size="small" @click="loadCategories">重试</Button></template>
        </Alert>
        <Tree
          v-model:expanded-keys="expandedKeys"
          class="subtle-tree-scrollbar"
          :selected-keys="selectedKeys"
          :tree-data="categoryTree"
          :disabled="treeLoading"
          block-node
          @select="selectCategory"
        >
          <template #titleRender="node">
            <span class="directory-node">
              <IconifyIcon class="directory-icon" :icon="node.children?.length || node.key === 'root' ? 'lucide:folder-open' : 'lucide:file-text'" />
              <span class="directory-name" :title="String(node.title)">{{ node.title }}</span>
            </span>
          </template>
        </Tree>
      </aside>
      <SidebarCollapse :collapsed="categoryCollapsed" label="类目树" @toggle="categoryCollapsed = !categoryCollapsed" />
      <div class="push-main">
    <Grid
      class="metadata-grid qdata-push-grid"
      table-title="主数据分发列表"
    >
      <template #toolbar-tools>
        <div class="metadata-toolbar">
          <div class="metadata-toolbar">
            <TableAction
              :actions="[
                {
                  label: '新增',
                  type: 'primary',
                  icon: ACTION_ICON.ADD,
                  auth: ['governance:master-distribute:create'],
                  onClick: handleCreate,
                },
                {
                  label: '批量删除',
                  type: 'primary',
                  icon: ACTION_ICON.DELETE,
                  danger: true,
                  auth: ['governance:master-distribute:delete'],
                  disabled:
                    selected.length === 0 ||
                    selected.some(
                      (row) =>
                        row.status === 1 ||
                        isRunBlocked(row) ||
                        isRowPending(row),
                    ),
                  popConfirm: {
                    title: '确认删除选中的主数据推送配置？',
                    confirm: handleBatchDelete,
                  },
                },
              ]"
            />
          </div>
        </div>
      </template>

      <template #model="{ row }">
        <div class="metadata-info">
          <div class="metadata-info__title">
            <button class="metadata-link" @click="handleDetail(row)">
              {{ row.masterModelName ?? '-' }} ({{
                row.masterModelTableName || '-'
              }})
</button><span class="metadata-tag">{{
              row.distributeType === 2
                ? pushScopeLabel(row.config?.api?.pushScope)
                : optionLabel(DISTRIBUTE_TYPE_OPTIONS, row.distributeType)
            }}</span><span
              v-if="row.distributeType === 2"
              class="metadata-tag metadata-tag--success"
              >{{ pushModeLabel(row.config?.api?.pushMode) }}</span>
          </div>
          <TextEllipsisCell
            class="metadata-muted"
            :content="row.description || '-'"
            :line="2"
            center
          />
        </div>
      </template>
      <template #destination="{ row }">
        <span class="qdata-master-url">{{
          row.config?.api?.url ||
          row.config?.db?.targetTableName ||
          row.config?.mq?.kafka?.topic ||
          '-'
        }}</span>
      </template>
      <template #result="{ row }">
        <span>{{ row.totalCount ?? 0 }} / {{ row.successCount ?? 0 }} /
          {{ row.failureCount ?? 0 }}</span><span
          v-if="row.unknownCount && String(row.unknownCount) !== '0'"
          class="metadata-tag metadata-tag--file"
          >待核查 {{ row.unknownCount }}</span>
      </template>
      <template #distributeType="{ row }">
        <Tag :color="optionColor(DISTRIBUTE_TYPE_OPTIONS, row.distributeType)">
          {{ optionLabel(DISTRIBUTE_TYPE_OPTIONS, row.distributeType) }}
        </Tag>
      </template>
      <template #status="{ row }">
        <Switch
          v-if="row.distributeType === 2"
          :checked="row.status === 1"
          :loading="isRowPending(row)"
          :disabled="
            !hasAccessByCodes(['governance:master-distribute:release']) ||
            isRowPending(row) ||
            (row.status !== 1 && isRunBlocked(row))
          "
          @change="(value) => handleToggle(row, value)"
        />
        <Tag v-else :color="optionColor(STATUS_OPTIONS, row.status)">
          <template v-if="row.distributeType !== 2">
            {{ optionLabel(STATUS_OPTIONS, row.status) }}
          </template>
        </Tag>
      </template>
      <template #blockingRunStatus="{ row }">
        <div class="flex items-center gap-1">
          <Tooltip :title="runBlockedReason(row)">
            <Tag
              :color="
                optionColor(
                  RUN_STATUS_OPTIONS,
                  row.blockingRunStatus || row.latestRunStatus,
                )
              "
            >
              {{
                optionLabel(
                  RUN_STATUS_OPTIONS,
                  row.blockingRunStatus || row.latestRunStatus,
                )
              }}
            </Tag>
          </Tooltip>
          <TableAction
            v-if="row.blockingRunStatus === 5"
            :actions="[
              {
                label: '核查',
                icon: 'lucide:shield-check',
                type: 'link',
                auth: ['governance:master-distribute:query'],
                tooltip:
                  '发布状态与执行结果独立；请在执行记录中核查后再发布或执行。',
                onClick: handleLog.bind(null, row),
              },
            ]"
          />
        </div>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: $t('common.detail'),
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['governance:master-distribute:query'],
              onClick: handleDetail.bind(null, row),
            },
            {
              label: '配置',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['governance:master-distribute:update'],
              disabled:
                (row.distributeType !== 2 && row.status === 1) ||
                (row.distributeType === 2 &&
                  row.status === 1 &&
                  !hasAccessByCodes([
                    'governance:master-distribute:release',
                  ])) ||
                isRunBlocked(row) ||
                isRowPending(row),
              tooltip:
                row.status === 1 &&
                !hasAccessByCodes(['governance:master-distribute:release'])
                  ? '修改已启用的推送配置需要启停权限'
                  : row.distributeType !== 2 && row.status === 1
                    ? '请先取消发布再修改派发任务。'
                    : runBlockedReason(row),
              onClick: handleEdit.bind(null, row),
            },
            {
              label: row.status === 1 ? '取消发布' : '发布',
              ifShow: row.distributeType !== 2,
              type: 'link',
              danger: row.status === 1,
              icon: row.status === 1 ? 'lucide:square' : 'lucide:send',
              auth: ['governance:master-distribute:release'],
              disabled:
                isRowPending(row) || (row.status !== 1 && isRunBlocked(row)),
              loading: isRowPending(row),
              tooltip: row.status === 1 ? undefined : runBlockedReason(row),
              popConfirm: {
                disabled:
                  isRowPending(row) || (row.status !== 1 && isRunBlocked(row)),
                title:
                  row.status === 1
                    ? `确认取消发布「${row.name}」吗？等待任务会取消；已进入发送的任务保留未知结果，不保证远端立即停止。`
                    : row.taskType === 1
                      ? `确认发布「${row.name}」吗？发布后将立即执行一次。`
                      : `确认发布「${row.name}」吗？将按 Cron 周期调度执行。`,
                confirm:
                  row.status === 1
                    ? handleCancel.bind(null, row)
                    : handleRelease.bind(null, row),
              },
            },
          ]"
          :drop-down-actions="[
            {
              label: '重试',
              type: 'link',
              icon: 'lucide:rotate-cw',
              auth: ['governance:master-distribute:run'],
              ifShow: row.distributeType === 2,
              disabled:
                row.latestRunStatus !== 4 ||
                isRunBlocked(row) ||
                isRowPending(row) ||
                row.status !== 1,
              popConfirm: {
                title: '确认重试最近一次失败的推送？',
                confirm: () => handleRetry(row),
              },
            },
            {
              label: '执行记录',
              type: 'link',
              icon: 'lucide:history',
              auth: ['governance:master-distribute:query'],
              onClick: handleLog.bind(null, row),
            },
            {
              label: row.distributeType === 2 ? '推送' : '手动执行',
              type: 'link',
              icon: 'lucide:play',
              auth: ['governance:master-distribute:run'],
              disabled:
                isRunBlocked(row) ||
                isRowPending(row) ||
                (row.distributeType === 2 && row.status !== 1),
              popConfirm: {
                disabled:
                  isRunBlocked(row) ||
                  isRowPending(row) ||
                  (row.distributeType === 2 && row.status !== 1),
                title: `确认立即执行「${row.name}」吗？`,
                confirm: handleHandRun.bind(null, row),
              },
            },
            {
              label: $t('common.delete'),
              type: 'link',
              danger: true,
              icon: ACTION_ICON.DELETE,
              auth: ['governance:master-distribute:delete'],
              disabled: isRunBlocked(row) || isRowPending(row),
              ifShow: row.status !== 1,
              popConfirm: {
                disabled: isRunBlocked(row) || isRowPending(row),
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
  font-size: 14px;
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

@media (width <= 640px) {
  .push-layout {
    flex-direction: column;
  }

  .push-directory {
    flex: 0 0 190px;
    width: 100%;
    min-height: 0;
  }

  .push-layout :deep(.sidebar-collapse) {
    flex: 0 0 12px;
    width: 100%;
    height: 12px;
  }
}

.push-main {
  flex: 1;
  min-width: 0;
  min-height: 0;
}

:deep(.masterdistribute-action-column),
:deep(.masterdistribute-action-column .vxe-cell) {
  overflow: visible !important;
}
</style>
