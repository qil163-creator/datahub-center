<script lang="ts" setup>
import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useVbenDrawer } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Button,
  Input,
  message,
  Modal,
  Select,
  Space,
  Table,
  Tag,
} from 'antdv-next';

import {
  getTaskProgress,
  getWorkItemListByTask,
  updateWorkItemAssignees,
  urgeTask,
  urgeWorkItem,
} from '#/api/dataforge/fill';
import { getSimpleUserList } from '#/api/system/user';
import { useDescription } from '#/components/description';

import { TASK_STATUS_TEXT, WORK_ITEM_STATUS_TEXT } from '../../shared';

defineOptions({ name: 'DataforgeFillTaskDetail' });

const router = useRouter();
const access = useAccessStore();
const user = useUserStore();
let requestGeneration = 0;
let drawerOpen = false;

const loading = ref(false);
const task = ref<DataforgeFillApi.Task>();
const progress = ref<DataforgeFillApi.TaskProgress>();
const workItems = ref<DataforgeFillApi.WorkItem[]>([]);

const [TaskDescriptions, taskDescApi] = useDescription({
  bordered: true,
  column: { lg: 3, md: 2, sm: 2, xl: 3, xs: 2, xxl: 3 },
  size: 'small',
  useCard: false,
  data: {},
  schema: [
    { field: 'name', label: '任务名称', span: 2 },
    {
      field: 'status',
      label: '状态',
      render: (value) => TASK_STATUS_TEXT[Number(value)]?.text ?? value ?? '-',
    },
    { field: 'periodKey', label: '所属期' },
    {
      field: 'openTime',
      label: '开放时间',
      render: (value) => formatDateTime(value) || '-',
    },
    {
      field: 'deadlineTime',
      label: '截止时间',
      render: (value) => formatDateTime(value) || '-',
    },
    { field: 'totalOrgCount', label: '应填组织' },
    { field: 'mergedCount', label: '已合并' },
    { field: 'overdueCount', label: '逾期组织' },
    {
      field: 'completionRate',
      label: '完成率',
      render: (value) =>
        value === undefined || value === null ? '-' : `${value}%`,
    },
  ],
});

const columns = [
  {
    dataIndex: 'orgName',
    key: 'orgName',
    title: '报送组织',
    width: 180,
    ellipsis: true,
  },
  { key: 'status', title: '状态', width: 150 },
  { key: 'assignees', title: '当前填报人', width: 200 },
  {
    dataIndex: 'draftRowCount',
    key: 'draftRowCount',
    title: '草稿行数',
    width: 85,
  },
  {
    dataIndex: 'attemptCount',
    key: 'attemptCount',
    title: '提交次数',
    width: 85,
  },
  {
    dataIndex: 'lastModifyTime',
    key: 'lastModifyTime',
    title: '最后修改',
    width: 160,
  },
  { key: 'actions', title: '操作', width: 340, fixed: 'right' as const },
];

const statusSummary = computed(() => {
  const counts = progress.value?.statusCounts ?? {};
  return Object.entries(counts)
    .map(([status, count]) => ({
      count: Number(count),
      status: Number(status),
    }))
    .filter((item) => item.count > 0)
    .toSorted((a, b) => a.status - b.status);
});

const [DetailDrawer, detailDrawerApi] = useVbenDrawer({
  class: 'w-[1160px] max-w-full',
  contentClass: 'p-5',
  placement: 'right',
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(isOpen: boolean) {
    if (!isOpen) resetDetail();
  },
});

function isCurrent(generation: number) {
  return drawerOpen && generation === requestGeneration;
}

async function openDetail(row: DataforgeFillApi.Task) {
  const generation = ++requestGeneration;
  drawerOpen = true;
  task.value = row;
  progress.value = undefined;
  workItems.value = [];
  resetAssignees();
  urging.value = false;
  taskDescApi.setDescProps({ data: { ...row } });
  detailDrawerApi.open();
  loading.value = true;
  try {
    const [nextProgress, nextWorkItems] = await Promise.all([
      getTaskProgress(row.id),
      getWorkItemListByTask(row.id),
    ]);
    if (!isCurrent(generation)) return;
    progress.value = nextProgress;
    workItems.value = nextWorkItems;
    taskDescApi.setDescProps({
      data: {
        ...row,
        completionRate: progress.value?.completionRate,
        mergedCount: progress.value?.mergedCount,
        overdueCount: progress.value?.overdueCount,
        totalOrgCount: progress.value?.totalOrgCount ?? row.totalOrgCount,
      },
    });
  } finally {
    if (isCurrent(generation)) loading.value = false;
  }
}

async function reload() {
  if (task.value) await openDetail(task.value);
}

function viewData(item: DataforgeFillApi.WorkItem) {
  void detailDrawerApi.close();
  router.push({
    path: '/dataforge/fill/workbench',
    query: { workItemId: item.id },
  });
}

// ---------------------------------------------------------------- 调整填报人
const assigneeOpen = ref(false);
const assigneeSaving = ref(false);
const currentItem = ref<DataforgeFillApi.WorkItem>();
const addUserIds = ref<number[]>([]);
const removeUserIds = ref<number[]>([]);
const reason = ref('');
const userOptions = ref<{ label: string; value: number }[]>([]);
let assigneeRequest = 0;

function resetAssignees() {
  ++assigneeRequest;
  assigneeOpen.value = false;
  assigneeSaving.value = false;
  currentItem.value = undefined;
  addUserIds.value = [];
  removeUserIds.value = [];
  reason.value = '';
}

function resetDetail() {
  ++requestGeneration;
  drawerOpen = false;
  loading.value = false;
  task.value = undefined;
  progress.value = undefined;
  workItems.value = [];
  taskDescApi.setDescProps({ data: {} });
  resetAssignees();
  urging.value = false;
}

async function openAssigneeModal(item: DataforgeFillApi.WorkItem) {
  const generation = requestGeneration;
  Modal.confirm({
    content: `将调整「${item.orgName ?? item.id}」的填报人。实例始终至少保留一名有效填报人，历史记录不受影响。`,
    onOk: async () => {
      if (!isCurrent(generation)) return;
      const request = ++assigneeRequest;
      currentItem.value = item;
      addUserIds.value = [];
      removeUserIds.value = [];
      reason.value = '';
      assigneeOpen.value = true;
      if (userOptions.value.length === 0) {
        const users = await getSimpleUserList();
        if (!isCurrent(generation) || request !== assigneeRequest) return;
        userOptions.value = (users as any[]).map((user) => ({
          label: user.nickname,
          value: user.id,
        }));
      }
    },
    title: '调整填报人确认',
  });
}

function toggleRemove(userId: number) {
  const index = removeUserIds.value.indexOf(userId);
  if (index === -1) {
    removeUserIds.value.push(userId);
  } else {
    removeUserIds.value.splice(index, 1);
  }
}

async function handleAssigneeSave() {
  if (!currentItem.value || assigneeSaving.value) return;
  if (addUserIds.value.length === 0 && removeUserIds.value.length === 0) {
    message.warning('请至少新增或移除一名填报人');
    return;
  }
  assigneeSaving.value = true;
  const generation = requestGeneration;
  const request = assigneeRequest;
  try {
    await updateWorkItemAssignees({
      addUserIds: addUserIds.value.length > 0 ? addUserIds.value : undefined,
      reason: reason.value || undefined,
      removeUserIds:
        removeUserIds.value.length > 0 ? removeUserIds.value : undefined,
      workItemId: currentItem.value.id,
    });
    if (!isCurrent(generation) || request !== assigneeRequest) return;
    message.success('填报人已调整');
    assigneeOpen.value = false;
    await reload();
  } finally {
    if (isCurrent(generation) && request === assigneeRequest)
      assigneeSaving.value = false;
  }
}

/** 允许调整填报人的状态：待填报/填报中/整改中 */
const ASSIGNEE_EDITABLE = new Set([0, 1, 5]);

// ---------------------------------------------------------------- 催办
const urging = ref(false);

async function handleUrgeItem(item: DataforgeFillApi.WorkItem) {
  const generation = requestGeneration;
  Modal.confirm({
    content: `将向「${item.orgName ?? item.id}」的当前填报人发送催办。1 小时内重复催办会被服务端频控。`,
    onOk: async () => {
      if (!isCurrent(generation)) return;
      await urgeWorkItem(item.id);
      if (!isCurrent(generation)) return;
      message.success(`已向「${item.orgName}」的填报人发送催办`);
    },
    title: '催办确认',
  });
}

async function handleUrgeAll() {
  if (!task.value || urging.value) return;
  const generation = requestGeneration;
  const taskId = task.value.id;
  Modal.confirm({
    content:
      '将催办本任务下尚未完成的组织。1 小时内已催办过的组织会被服务端跳过。',
    onOk: async () => {
      if (!isCurrent(generation) || urging.value) return;
      urging.value = true;
      try {
        const count = await urgeTask(taskId);
        if (!isCurrent(generation)) return;
        message.success(
          count > 0
            ? `已催办 ${count} 个未完成组织`
            : '没有需要催办的组织（或均在 1 小时频控内）',
        );
      } finally {
        if (isCurrent(generation)) urging.value = false;
      }
    },
    title: '一键催办确认',
  });
}

watch(
  () => [access.accessToken, access.refreshToken, user.userInfo?.userId],
  () => {
    resetDetail();
    userOptions.value = [];
    void detailDrawerApi.close();
  },
);
onBeforeUnmount(resetDetail);

defineExpose({ open: openDetail });
</script>

<template>
  <DetailDrawer :title="task ? `任务进度：${task.name}` : '任务进度'">
    <TaskDescriptions class="mb-4" />

    <div class="mb-3 flex flex-wrap items-center justify-between gap-3">
      <Space wrap>
        <Tag
          v-for="item in statusSummary"
          :key="item.status"
          :color="WORK_ITEM_STATUS_TEXT[item.status]?.color"
        >
          {{ WORK_ITEM_STATUS_TEXT[item.status]?.text }} {{ item.count }}
        </Tag>
      </Space>
      <Space>
        <Button
          v-access:code="'dataforge:fill-task:urge'"
          :loading="urging"
          size="small"
          @click="handleUrgeAll"
        >
          <template #icon><IconifyIcon icon="lucide:bell-ring" /></template>
          一键催办
        </Button>
        <Button :loading="loading" size="small" @click="reload"
          ><template #icon><IconifyIcon icon="lucide:refresh-cw" /></template
          >刷新</Button
        >
      </Space>
    </div>

    <Table
      :columns="columns"
      :data-source="workItems"
      :loading="loading"
      :pagination="false"
      :scroll="{ x: 1110 }"
      row-key="id"
      size="small"
      table-layout="fixed"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'status'">
          <Tag :color="WORK_ITEM_STATUS_TEXT[record.status]?.color">
            {{ WORK_ITEM_STATUS_TEXT[record.status]?.text ?? record.status }}
          </Tag>
          <Tag v-if="record.overdue === 1" color="error">逾期</Tag>
          <Tag v-if="record.submissionType === 2" color="warning">零上报</Tag>
        </template>
        <template v-else-if="column.key === 'assignees'">
          <Space :size="4" wrap>
            <Tag
              v-for="assignee in record.assignees ?? []"
              :key="assignee.userId"
            >
              {{ assignee.userName ?? assignee.userId }}
            </Tag>
          </Space>
        </template>
        <template v-else-if="column.key === 'lastModifyTime'">
          {{ formatDateTime(record.lastModifyTime) || '-' }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <Button size="small" type="link" @click="viewData(record)">
            <template #icon><IconifyIcon icon="lucide:eye" /></template>
            查看数据
          </Button>
          <Button
            v-if="ASSIGNEE_EDITABLE.has(record.status)"
            v-access:code="'dataforge:fill-work-item:assignee'"
            size="small"
            type="link"
            @click="openAssigneeModal(record)"
          >
            <template #icon
              ><IconifyIcon icon="lucide:user-round-pen"
            /></template>
            调整填报人
          </Button>
          <Button
            v-if="ASSIGNEE_EDITABLE.has(record.status)"
            v-access:code="'dataforge:fill-task:urge'"
            size="small"
            type="link"
            @click="handleUrgeItem(record)"
          >
            <template #icon><IconifyIcon icon="lucide:bell-ring" /></template>
            催办
          </Button>
        </template>
      </template>
    </Table>

    <Modal
      v-model:open="assigneeOpen"
      :confirm-loading="assigneeSaving"
      :title="`调整填报人：${currentItem?.orgName ?? ''}`"
      @ok="handleAssigneeSave"
    >
      <div class="flex flex-col gap-3 py-2">
        <div>
          <div class="mb-1 text-sm font-medium">当前填报人（点击标记移除）</div>
          <Space :size="4" wrap>
            <Tag
              v-for="assignee in currentItem?.assignees ?? []"
              :key="assignee.userId"
              :color="
                removeUserIds.includes(assignee.userId) ? 'error' : 'default'
              "
              class="cursor-pointer select-none"
              @click="toggleRemove(assignee.userId)"
            >
              {{ assignee.userName ?? assignee.userId }}
              <span v-if="removeUserIds.includes(assignee.userId)">
                （将移除）
              </span>
            </Tag>
            <span
              v-if="(currentItem?.assignees ?? []).length === 0"
              class="text-muted-foreground text-xs"
            >
              暂无
            </span>
          </Space>
        </div>
        <div>
          <div class="mb-1 text-sm font-medium">新增填报人</div>
          <Select
            v-model:value="addUserIds"
            :options="userOptions"
            class="w-full"
            mode="multiple"
            option-filter-prop="label"
            placeholder="选择要新增的填报人"
            show-search
          />
        </div>
        <div>
          <div class="mb-1 text-sm font-medium">原因说明</div>
          <Input
            v-model:value="reason"
            placeholder="如：原填报人休假，重新分配"
          />
        </div>
        <p class="text-muted-foreground text-xs">
          实例始终至少保留一名有效填报人；历史修改与提交记录不受人员调整影响。
        </p>
      </div>
    </Modal>
  </DetailDrawer>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
