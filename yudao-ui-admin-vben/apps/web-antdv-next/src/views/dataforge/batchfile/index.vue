<script setup lang="ts">
import type { BatchFileApi as Api } from '#/api/dataforge/batchfile';

import { computed, nextTick, reactive, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDate } from '@vben/utils';

import {
  Alert,
  Button,
  Dropdown,
  Menu,
  MenuItem,
  message,
  Switch,
  Tag,
} from 'antdv-next';

import { useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  deleteBatchFile,
  executeBatchFile,
  getBatchFile,
  getBatchFilePage,
  getBatchFileSummary,
  updateBatchFileStatus,
} from '#/api/dataforge/batchfile';
import { createCommandTracker } from '#/api/dataforge/execution/command';
import { TextEllipsisCell } from '#/components/text-ellipsis-cell';
import { useObjectRequest } from '#/hooks/use-object-request';

import { activeRun, idOf, statusOf, statusOptions } from './model';
import EmptyState from './modules/empty-state.vue';
import ReferenceIcon from './modules/reference-icon.vue';
import { useBatchConfirm } from './use-confirm';
import { useBatchScope } from './use-scope';

defineOptions({ name: 'DataforgeBatchFile' });
const router = useRouter();
const { hasAccessByCodes } = useAccess();
const can = (action: string) =>
  hasAccessByCodes([`dataforge:batch-file:${action}`]);
const allowed = computed(() => can('query'));
const rows = ref<Api.Task[]>([]);
const summary = ref<Api.Summary>();
const applied = reactive({
  taskName: '',
  lastStatus: undefined as Api.LastStatus | undefined,
});
const page = ref(1);
const pageSize = ref(10);
const sortField = ref<NonNullable<Api.PageReq['sortField']>>('id');
const sortOrder = ref<NonNullable<Api.PageReq['sortOrder']>>('desc');
const total = ref(0);
const loading = ref(false);
const failed = ref(false);
const busy = ref(false);
const { active, scope } = useBatchScope();
const confirm = useBatchConfirm(scope);
const request = useObjectRequest(() => scope.value);
const actionRequest = useObjectRequest(() => scope.value);
const commands = createCommandTracker(() => scope.value);
const [Grid, gridApi] = useVbenVxeGrid<Api.Task>({
  formOptions: {
    schema: [
      {
        fieldName: 'taskName',
        label: '任务名称',
        component: 'Input',
        componentProps: {
          allowClear: true,
          placeholder: '请输入任务名称',
          'aria-label': '任务名称',
        },
      },
      {
        fieldName: 'lastStatus',
        label: '最近状态',
        component: 'Select',
        componentProps: {
          allowClear: true,
          options: statusOptions,
          placeholder: '请选择最近状态',
          'aria-label': '最近状态',
        },
      },
    ],
    handleSubmit: async (values) => {
      applied.taskName = values.taskName ?? '';
      applied.lastStatus = values.lastStatus;
      page.value = 1;
      await load();
    },
    handleReset: reset,
    resetButtonOptions: { show: false },
    submitButtonOptions: { show: false },
    showCollapseButton: false,
  },
  gridOptions: {
    showOverflow: false,
    showHeaderOverflow: 'tooltip',
    columns: [
      { field: 'id', title: '编号', width: 160, sortable: true },
      {
        field: 'taskName',
        title: '任务信息',
        minWidth: 280,
        align: 'left',
        showOverflow: false,
        slots: { default: 'info' },
      },
      { field: 'fileCount', title: '文件数', width: 105, sortable: true },
      { field: 'targetCount', title: '目标表数', width: 115, sortable: true },
      {
        field: 'lastStatus',
        title: '最近执行信息',
        width: 180,
        showOverflow: false,
        slots: { default: 'last' },
      },
      {
        field: 'online',
        title: '任务状态',
        width: 100,
        slots: { default: 'online' },
      },
      {
        field: 'updateTime',
        title: '更新时间',
        width: 180,
        sortable: true,
        formatter: ({ cellValue }: { cellValue?: number }) =>
          formatDate(cellValue, 'YYYY-MM-DD HH:mm') || '-',
      },
      {
        title: '操作',
        width: 260,
        fixed: 'right',
        showOverflow: false,
        slots: { default: 'actions' },
      },
    ],
    data: [],
    height: 'auto',
    rowConfig: { keyField: 'id', isHover: true },
    sortConfig: {
      remote: true,
      multiple: false,
      orders: ['desc', 'asc'],
      defaultSort: { field: 'id', order: 'desc' },
    },
    proxyConfig: { enabled: false, autoLoad: false },
    pagerConfig: { currentPage: 1, pageSize: 10, total: 0 },
    toolbarConfig: {
      refresh: true,
      refreshOptions: { icon: 'bf-icon bf-icon-refresh', queryMethod: load },
      custom: true,
      customOptions: { icon: 'bf-icon bf-icon-grid' },
      zoom: false,
    },
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize: size,
    }: {
      currentPage: number;
      pageSize: number;
    }) => {
      page.value = currentPage;
      pageSize.value = size;
      void load();
    },
    sortChange: ({
      field,
      order,
    }: {
      field: string;
      order?: null | string;
    }) => {
      if (!sortable(field)) return;
      sortField.value = field;
      sortOrder.value = order === 'asc' ? 'asc' : 'desc';
      page.value = 1;
      void load();
    },
  },
});
watch(
  [rows, loading, page, pageSize, total],
  async () => {
    gridApi.setGridOptions({
      data: rows.value,
      loading: loading.value,
      pagerConfig: {
        currentPage: page.value,
        pageSize: pageSize.value,
        total: total.value,
      },
    });
    // 配置更新会重建表格列，待更新完成后恢复服务端排序标记。
    await nextTick();
    await nextTick();
    void gridApi.grid.sort?.(sortField.value, sortOrder.value);
  },
  { immediate: true },
);
async function load() {
  if (!active.value || !allowed.value) return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  try {
    const [result, statistics] = await Promise.all([
      getBatchFilePage({
        pageNo: page.value,
        pageSize: pageSize.value,
        taskName: applied.taskName || undefined,
        lastStatus: applied.lastStatus,
        sortField: sortField.value,
        sortOrder: sortOrder.value,
      }),
      getBatchFileSummary(),
    ]);
    if (current()) {
      rows.value = result.list;
      total.value = result.total;
      summary.value = statistics;
    }
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}
function sortable(key: string): key is NonNullable<Api.PageReq['sortField']> {
  return ['fileCount', 'id', 'targetCount', 'updateTime'].includes(key);
}
async function reset() {
  await gridApi.formApi.reset();
  applied.taskName = '';
  applied.lastStatus = undefined;
  page.value = 1;
  await load();
}
function statusColor(value: Api.LastStatus) {
  const colors: Record<string, string> = {
    failed: 'error',
    running: 'processing',
    success: 'success',
  };
  return colors[statusOf(value).tone] ?? 'default';
}
function detail(row: Api.Task, tab = 'files') {
  void router.push({
    name: 'DataforgeBatchFileDetail',
    params: { id: idOf(row.id) },
    query: tab === 'files' ? {} : { tab },
  });
}
function edit(row: Api.Task) {
  void router.push({
    name: 'DataforgeBatchFileEdit',
    params: { id: idOf(row.id) },
  });
}
function updateStatus(row: Api.Task) {
  if (busy.value || !can('update')) return;
  const openedScope = scope.value;
  confirm({
    okText: '确定',
    cancelText: '取消',
    title: row.online ? '下线任务' : '上线任务',
    content: row.online ? '下线后可修改任务配置。' : '上线后可执行文件入库。',
    async onOk() {
      if (scope.value !== openedScope || busy.value || !can('update')) return;
      const current = actionRequest.begin();
      busy.value = true;
      try {
        const latest = await getBatchFile(row.id);
        if (!current()) return;
        await updateBatchFileStatus({
          id: latest.id,
          revision: latest.revision,
          online: !row.online,
        });
        if (current()) {
          message.success('任务状态已更新');
          await load();
        }
      } finally {
        if (current()) busy.value = false;
      }
    },
  });
}
function confirmExecute(row: Api.Task) {
  if (
    busy.value ||
    !can('execute') ||
    !row.online ||
    activeRun(row.lastStatus, row.lastRunResolvedTime)
  )
    return;
  const openedScope = scope.value;
  confirm({
    okText: '确定',
    cancelText: '取消',
    title: '执行文件入库',
    content:
      '本次执行将向目标表追加全部文件的数据。此前成功或部分成功写入的行不会自动跳过，再次执行可能产生重复数据。执行人需具备批量文件执行和目标数据写入权限；目标存在禁止写入策略时不可入库。',
    onOk: () => (scope.value === openedScope ? execute(row) : undefined),
  });
}
async function execute(row: Api.Task) {
  if (
    busy.value ||
    !can('execute') ||
    !row.online ||
    activeRun(row.lastStatus, row.lastRunResolvedTime)
  )
    return;
  const current = actionRequest.begin();
  busy.value = true;
  try {
    const receipt = await commands.execute(
      `execute:${idOf(row.id)}`,
      { id: row.id },
      executeBatchFile,
    );
    if (current() && receipt) {
      message.success(
        `${receipt.replayed ? '已确认原执行请求' : '执行请求已受理'}，请查看运行实例`,
      );
      await load();
    }
  } finally {
    if (current()) busy.value = false;
  }
}
function remove(row: Api.Task) {
  if (busy.value || !can('delete')) return;
  const openedScope = scope.value;
  confirm({
    okText: '确定',
    cancelText: '取消',
    title: '删除任务',
    content: `确定删除任务“${row.taskName}”？`,
    async onOk() {
      if (scope.value !== openedScope || busy.value || !can('delete')) return;
      const current = actionRequest.begin();
      busy.value = true;
      try {
        await deleteBatchFile(row.id, row.revision);
        if (current()) {
          message.success('任务已删除');
          if (rows.value.length === 1 && page.value > 1) page.value--;
          await load();
        }
      } finally {
        if (current()) busy.value = false;
      }
    },
  });
}
watch(
  scope,
  () => {
    rows.value = [];
    summary.value = undefined;
    total.value = 0;
    loading.value = false;
    failed.value = false;
    busy.value = false;
    page.value = 1;
    commands.invalidate();
    void load();
  },
  { immediate: true },
);
</script>

<template>
  <Page
    auto-content-height
    class="batch-file-list"
    content-class="flex flex-col gap-3"
  >
    <div
      class="bg-card text-muted-foreground flex shrink-0 flex-wrap items-center gap-x-6 gap-y-3 rounded-md px-4 py-3 text-sm"
    >
      <span
        v-for="entry in [
          {
            key: 'taskCount' as const,
            title: '入库任务',
            marker: 'bg-primary',
          },
          {
            key: 'successCount' as const,
            title: '成功实例',
            marker: 'bg-success rounded-full',
          },
          {
            key: 'failedCount' as const,
            title: '异常实例',
            marker: 'bg-warning',
          },
          { key: 'runCount' as const, title: '任务实例', marker: 'bg-primary' },
        ]"
        :key="entry.key"
        class="border-border inline-flex items-center gap-2 border-r pr-6"
      >
        <i
          class="size-[7px] shrink-0 rounded-sm"
          :class="entry.marker"
          aria-hidden="true"
        ></i>
        {{ entry.title }}：
        <strong class="text-foreground font-normal"
          >{{ summary ? String(summary[entry.key]) : '-' }}个</strong
        >
      </span>
      <span class="ml-auto inline-flex flex-wrap items-center gap-2">
        数据统计截止：{{
          summary
            ? formatDate(summary.statisticsTime, 'YYYY/MM/DD HH:mm:ss')
            : '-'
        }}
        <Button
          type="link"
          size="small"
          aria-label="刷新统计"
          :disabled="loading"
          @click="load"
        >
          <ReferenceIcon name="refresh" />
        </Button>
      </span>
    </div>
    <Grid class="min-h-0 flex-1" table-title="批量文件入库">
      <template #form-reset-before>
        <Button @click="reset"><ReferenceIcon name="reset" />重置</Button>
      </template>
      <template #form-submit-before>
        <Button
          type="primary"
          :disabled="!allowed"
          @click="gridApi.formApi.validateAndSubmit()"
        >
          <ReferenceIcon name="search" />查询
        </Button>
      </template>
      <template #toolbar-tools>
        <Button
          v-if="can('create')"
          type="primary"
          @click="router.push({ name: 'DataforgeBatchFileCreate' })"
        >
          <ReferenceIcon name="plus" />新增
        </Button>
        <Button
          class="ml-2"
          shape="circle"
          aria-label="显示/隐藏查询"
          title="显示/隐藏查询"
          @click="gridApi.toggleSearchForm()"
        >
          <span class="bf-search-icon" aria-hidden="true"></span>
        </Button>
      </template>
      <template #top>
        <Alert
          v-if="!allowed"
          class="mb-3"
          type="warning"
          message="没有批量文件入库查询权限"
          show-icon
        />
        <Alert
          v-if="failed"
          class="mb-3"
          type="error"
          message="任务加载失败，请重试"
          show-icon
        >
          <template #action>
            <Button size="small" @click="load">
              <template #icon>
                <IconifyIcon icon="lucide:refresh-cw" /> </template
              >重试
            </Button>
          </template>
        </Alert>
      </template>
      <template #info="{ row }">
        <Button
          type="link"
          class="h-auto max-w-full whitespace-normal p-0 text-left"
          @click="detail(row)"
        >
          {{ row.taskName }}
        </Button>
        <TextEllipsisCell
          class="text-muted-foreground block whitespace-normal text-xs"
          :content="row.description || '固定结构文件批量入库任务'"
          :line="2"
          center
        />
      </template>
      <template #last="{ row }">
        <Tag :color="statusColor(row.lastStatus)">
          {{ statusOf(row.lastStatus).label }}
        </Tag>
        <span class="text-muted-foreground block text-xs">{{
          row.lastRunTime
            ? formatDate(row.lastRunTime, 'YYYY-MM-DD HH:mm')
            : '-　-'
        }}</span>
      </template>
      <template #online="{ row }">
        <Switch
          :checked="row.online"
          :disabled="
            busy ||
            !can('update') ||
            activeRun(row.lastStatus, row.lastRunResolvedTime)
          "
          :aria-label="`${row.taskName}任务状态`"
          @change="updateStatus(row)"
        />
      </template>
      <template #actions="{ row }">
        <div
          class="batch-file-table-actions flex items-center justify-center gap-1 whitespace-nowrap"
        >
          <Button
            v-if="can('update')"
            type="link"
            :disabled="
              busy ||
              row.online ||
              activeRun(row.lastStatus, row.lastRunResolvedTime)
            "
            @click="edit(row)"
          >
            <ReferenceIcon name="edit" />配置
          </Button>
          <Button type="link" @click="detail(row)">
            <ReferenceIcon name="eye" />详情
          </Button>
          <Dropdown :trigger="['click']">
            <Button type="link"><IconifyIcon icon="lucide:ellipsis-vertical" />更多</Button>
            <template #popupRender>
              <Menu class="batch-file-menu">
                <MenuItem
                  v-if="can('execute')"
                  :disabled="
                    busy ||
                    !row.online ||
                    activeRun(row.lastStatus, row.lastRunResolvedTime)
                  "
                  @click="confirmExecute(row)"
                >
                  <ReferenceIcon name="play" />执行一次
                </MenuItem>
                <MenuItem @click="detail(row, 'runs')">
                  <ReferenceIcon name="play" />运行实例
                </MenuItem>
                <MenuItem
                  v-if="can('delete')"
                  danger
                  :disabled="
                    busy ||
                    row.online ||
                    activeRun(row.lastStatus, row.lastRunResolvedTime)
                  "
                  @click="remove(row)"
                >
                  <IconifyIcon
                    class="mr-1 inline-flex size-4 align-middle"
                    icon="lucide:trash-2"
                  />删除
                </MenuItem>
              </Menu>
            </template>
          </Dropdown>
        </div>
      </template>
      <template #empty><EmptyState v-if="!loading" /></template>
    </Grid>
  </Page>
</template>
<style scoped>
.batch-file-list :deep(.batch-file-table-actions .ant-btn-link) {
  padding: 4px;
}

.batch-file-list :deep(.vxe-tools--operate .bf-icon),
.batch-file-list :deep(.ant-btn-circle .bf-icon) {
  margin-right: 0;
}

.batch-file-list :deep(.bf-empty) {
  display: flex;
  flex-direction: column;
  gap: 16px;
  align-items: center;
  justify-content: center;
  color: hsl(var(--muted-foreground));
}

.batch-file-list :deep(.bf-empty img) {
  width: 196px;
  height: 112px;
  object-fit: contain;
}

.bf-search-icon {
  position: relative;
  display: inline-block;
  width: 12px;
  height: 12px;
  border: 1.4px solid currentcolor;
  border-radius: 50%;
}

.bf-search-icon::after {
  position: absolute;
  right: -4px;
  bottom: -2px;
  width: 5px;
  height: 1.4px;
  content: '';
  background: currentcolor;
  transform: rotate(48deg);
}

/* 保留原有 ReferenceIcon 的几何绘制，避免列表换用标准布局后图标丢失。 */
:deep(.bf-icon) {
  position: relative;
  display: inline-block;
  flex: none;
  width: 12px;
  height: 12px;
  margin-right: 5px;
  vertical-align: -1px;
  color: currentcolor;
}

:deep(.bf-icon)::before,
:deep(.bf-icon)::after {
  position: absolute;
  box-sizing: border-box;
  content: '';
}

:deep(.bf-icon-search)::before {
  inset: 0 3px 3px 0;
  border: 1px solid currentcolor;
  border-radius: 50%;
}

:deep(.bf-icon-search)::after {
  right: 0;
  bottom: 1px;
  width: 5px;
  height: 1px;
  background: currentcolor;
  transform: rotate(45deg);
}

:deep(.bf-icon-plus)::before {
  top: 5px;
  left: 1px;
  width: 10px;
  height: 1px;
  background: currentcolor;
}

:deep(.bf-icon-plus)::after {
  top: 1px;
  left: 5px;
  width: 1px;
  height: 10px;
  background: currentcolor;
}

:deep(.bf-icon-edit)::before {
  inset: 2px 2px 0 0;
  border: 1px solid currentcolor;
}

:deep(.bf-icon-edit)::after {
  top: 2px;
  left: 6px;
  width: 2px;
  height: 9px;
  background: currentcolor;
  box-shadow: 0 0 0 2px #fff;
  transform: rotate(42deg);
  transform-origin: top;
}

:deep(.bf-icon-eye)::before {
  inset: 2px 0;
  border: 1px solid currentcolor;
  border-radius: 70% 20%;
  transform: rotate(45deg);
}

:deep(.bf-icon-eye)::after {
  top: 4px;
  left: 4px;
  width: 4px;
  height: 4px;
  border: 1px solid currentcolor;
  border-radius: 50%;
}

:deep(.bf-icon-play)::before,
:deep(.bf-icon-check)::before {
  inset: 0;
  border: 1px solid currentcolor;
  border-radius: 50%;
}

:deep(.bf-icon-play)::after {
  top: 3px;
  left: 5px;
  border-top: 3px solid transparent;
  border-bottom: 3px solid transparent;
  border-left: 4px solid currentcolor;
}

:deep(.bf-icon-check)::after {
  top: 3px;
  left: 3px;
  width: 6px;
  height: 4px;
  border-bottom: 1px solid currentcolor;
  border-left: 1px solid currentcolor;
  transform: rotate(-45deg);
}

:deep(.bf-icon-refresh)::before {
  inset: 1px;
  border: 1px solid currentcolor;
  border-top-color: transparent;
  border-radius: 50%;
  transform: rotate(-35deg);
}

:deep(.bf-icon-refresh)::after {
  top: 0;
  right: 0;
  width: 0;
  border-right: 4px solid currentcolor;
  border-bottom: 4px solid transparent;
}

:deep(.bf-icon-grid)::before {
  top: 1px;
  left: 1px;
  width: 4px;
  height: 4px;
  background: currentcolor;
  box-shadow:
    6px 0 currentcolor,
    0 6px currentcolor,
    6px 6px currentcolor;
}

:deep(.bf-icon-down)::before {
  top: 2px;
  left: 3px;
  width: 6px;
  height: 6px;
  border-right: 1px solid currentcolor;
  border-bottom: 1px solid currentcolor;
  transform: rotate(45deg);
}

:deep(.bf-icon-back)::before {
  top: 3px;
  left: 3px;
  width: 9px;
  height: 7px;
  border-top: 1px solid currentcolor;
  border-right: 1px solid currentcolor;
  border-radius: 0 5px 0 0;
}

:deep(.bf-icon-back)::after {
  top: 1px;
  left: 2px;
  width: 6px;
  height: 6px;
  border-bottom: 1px solid currentcolor;
  border-left: 1px solid currentcolor;
  transform: rotate(45deg);
}

:deep(.bf-icon-field)::before {
  inset: 0 1px;
  border: 1px solid currentcolor;
}

:deep(.bf-icon-field)::after {
  top: 3px;
  left: 4px;
  width: 4px;
  height: 1px;
  background: currentcolor;
  box-shadow:
    0 3px currentcolor,
    0 6px currentcolor;
}

:deep(.bf-icon-reset)::before {
  top: 0;
  left: 6px;
  width: 1px;
  height: 6px;
  background: currentcolor;
}

:deep(.bf-icon-reset)::after {
  right: 1px;
  bottom: 0;
  left: 1px;
  height: 7px;
  background: repeating-linear-gradient(
    90deg,
    transparent 0 2px,
    currentcolor 2px 3px
  );
  border: 1px solid currentcolor;
  border-radius: 2px 2px 0 0;
}
</style>
