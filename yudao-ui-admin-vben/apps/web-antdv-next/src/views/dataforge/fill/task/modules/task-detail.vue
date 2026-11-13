<script lang="ts" setup>
import type { Dayjs } from 'dayjs';

import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useVbenDrawer } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Button,
  DatePicker,
  Input,
  message,
  Modal,
  Select,
  Space,
  Table,
  Tag,
} from 'antdv-next';
import dayjs from 'dayjs';

import {
  closeTask,
  extendTaskDeadline,
  getTask,
  getTaskProgress,
  getWorkItemListByTask,
  switchTaskProcessDefinition,
  updateWorkItemAssignees,
  urgeTask,
  urgeWorkItem,
} from '#/api/dataforge/fill';
import { getSimpleUserList } from '#/api/system/user';
import { useDescription } from '#/components/description';

import { TASK_STATUS_TEXT, WORK_ITEM_STATUS_TEXT } from '../../shared';

defineOptions({ name: 'DataforgeFillTaskDetail' });

const emit = defineEmits<{ changed: [] }>();

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
      field: 'processDefinitionId',
      label: '审批流程版本',
      render: (value) => value || '首次启动审批时冻结',
    },
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
  { key: 'deadlineTime', title: '截止时间', width: 150 },
  {
    dataIndex: 'lastModifyTime',
    key: 'lastModifyTime',
    title: '最后修改',
    width: 160,
  },
  { key: 'actions', title: '操作', width: 400, fixed: 'right' as const },
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
  resetExtend();
  urging.value = false;
  closing.value = false;
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

/** 写操作后重新读取任务本身（状态、截止时间会变化），并通知列表刷新 */
async function reloadTask(generation: number, taskId: number) {
  emit('changed');
  const latest = await getTask(taskId);
  if (!isCurrent(generation)) return;
  await openDetail(latest);
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
  resetExtend();
  urging.value = false;
  closing.value = false;
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
    content: `将向「${item.orgName ?? item.id}」的当前填报人发送催办。在计划配置的催办频控时间内（默认 60 分钟）重复催办会被服务端拒绝。`,
    onOk: async () => {
      if (!isCurrent(generation)) return;
      await urgeWorkItem(item.id);
      if (!isCurrent(generation)) return;
      message.success(`已向「${item.orgName}」的填报人发送催办`);
    },
    title: '催办确认',
  });
}

// ---------------------------------------------------------------- 审批流程版本
const switching = ref(false);

function handleSwitchProcess() {
  if (!task.value || switching.value) return;
  const generation = requestGeneration;
  const taskId = task.value.id;
  Modal.confirm({
    content:
      '冻结的审批流程版本被新部署停用后，本任务的新提交会停在“待启动审批”。切换后，等待中的提交将按当前生效版本重新发起审批；已发起的审批不受影响。',
    onOk: async () => {
      if (!isCurrent(generation) || switching.value) return;
      switching.value = true;
      try {
        const definitionId = await switchTaskProcessDefinition(taskId);
        if (!isCurrent(generation) || !task.value) return;
        task.value = { ...task.value, processDefinitionId: definitionId };
        message.success('已切换到当前审批流程版本');
        await reload();
      } finally {
        if (isCurrent(generation)) switching.value = false;
      }
    },
    title: '切换审批流程版本',
  });
}

async function handleUrgeAll() {
  if (!task.value || urging.value) return;
  const generation = requestGeneration;
  const taskId = task.value.id;
  Modal.confirm({
    content:
      '将催办本任务下尚未完成的组织。在计划配置的催办频控时间内（默认 60 分钟）已催办过的组织会被服务端跳过。',
    onOk: async () => {
      if (!isCurrent(generation) || urging.value) return;
      urging.value = true;
      try {
        const count = await urgeTask(taskId);
        if (!isCurrent(generation)) return;
        message.success(
          count > 0
            ? `已催办 ${count} 个未完成组织`
            : '没有需要催办的组织（或均在催办频控时间内）',
        );
      } finally {
        if (isCurrent(generation)) urging.value = false;
      }
    },
    title: '一键催办确认',
  });
}

// ---------------------------------------------------------------- 延期截止时间
/** 进行中或待开始的任务才允许延期、结束填报 */
const taskOpen = computed(() => task.value?.status === 0 || task.value?.status === 1);
const extendOpen = ref(false);
const extendSaving = ref(false);
/** 为空表示延期整个任务下全部未提交组织 */
const extendItem = ref<DataforgeFillApi.WorkItem>();
const extendDeadline = ref<Dayjs>();
const extendReason = ref('');
let extendRequest = 0;

function resetExtend() {
  ++extendRequest;
  extendOpen.value = false;
  extendSaving.value = false;
  extendItem.value = undefined;
  extendDeadline.value = undefined;
  extendReason.value = '';
}

function openExtendModal(item?: DataforgeFillApi.WorkItem) {
  ++extendRequest;
  extendItem.value = item;
  const current = item?.deadlineTime ?? task.value?.deadlineTime;
  const base = current && dayjs(current).isAfter(dayjs()) ? dayjs(current) : dayjs();
  extendDeadline.value = base.add(1, 'day');
  extendReason.value = '';
  extendSaving.value = false;
  extendOpen.value = true;
}

function disabledPastDate(current: Dayjs) {
  return current.isBefore(dayjs().startOf('day'));
}

async function handleExtendSave() {
  if (!task.value || extendSaving.value) return;
  if (!extendDeadline.value || !extendDeadline.value.isAfter(dayjs())) {
    message.warning('新的截止时间必须晚于当前时间');
    return;
  }
  extendSaving.value = true;
  const generation = requestGeneration;
  const request = extendRequest;
  const taskId = task.value.id;
  try {
    const count = await extendTaskDeadline({
      deadlineTime: extendDeadline.value.valueOf(),
      reason: extendReason.value.trim() || undefined,
      taskId,
      workItemIds: extendItem.value ? [extendItem.value.id] : undefined,
    });
    if (!isCurrent(generation) || request !== extendRequest) return;
    message.success(
      count > 0
        ? `已延期 ${count} 个组织，并通知其填报人`
        : '没有需要延期的组织（截止时间均已晚于所选时间，或均已提交）',
    );
    extendOpen.value = false;
    await reloadTask(generation, taskId);
  } finally {
    if (isCurrent(generation) && request === extendRequest)
      extendSaving.value = false;
  }
}

// ---------------------------------------------------------------- 结束填报
const closing = ref(false);

function handleCloseTask() {
  if (!task.value || closing.value) return;
  const generation = requestGeneration;
  const taskId = task.value.id;
  Modal.confirm({
    content:
      '结束后，尚未提交的组织（待填报、填报中、整改中）将被取消且不能再填报，已合并后发起的更正会被撤销并保留原数据；审批或合并中的组织会继续走完，全部完成后任务自动结束。此操作不可撤回。',
    okButtonProps: { danger: true },
    okText: '结束填报',
    onOk: async () => {
      if (!isCurrent(generation) || closing.value) return;
      closing.value = true;
      try {
        const result = await closeTask(taskId);
        if (!isCurrent(generation)) return;
        const parts = [`取消 ${result.cancelledCount} 个未提交组织`];
        if (result.restoredCount > 0) {
          parts.push(`撤销 ${result.restoredCount} 个进行中的更正`);
        }
        message.success(
          result.inFlightCount > 0
            ? `已结束填报：${parts.join('，')}；另有 ${result.inFlightCount} 个组织仍在审批或合并中，完成后任务自动结束`
            : `已结束填报：${parts.join('，')}`,
        );
        await reloadTask(generation, taskId);
      } finally {
        if (isCurrent(generation)) closing.value = false;
      }
    },
    title: '结束填报确认',
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
          v-if="task?.processDefinitionKey"
          v-access:code="'dataforge:fill-task:create'"
          :loading="switching"
          size="small"
          @click="handleSwitchProcess"
        >
          <template #icon><IconifyIcon icon="lucide:git-branch" /></template>
          切换到当前流程版本
        </Button>
        <Button
          v-access:code="'dataforge:fill-task:urge'"
          :loading="urging"
          size="small"
          @click="handleUrgeAll"
        >
          <template #icon><IconifyIcon icon="lucide:bell-ring" /></template>
          一键催办
        </Button>
        <Button
          v-if="taskOpen"
          v-access:code="'dataforge:fill-task:extend'"
          size="small"
          @click="openExtendModal()"
        >
          <template #icon><IconifyIcon icon="lucide:clock-plus" /></template>
          延期
        </Button>
        <Button
          v-if="taskOpen"
          v-access:code="'dataforge:fill-task:cancel'"
          :loading="closing"
          danger
          size="small"
          @click="handleCloseTask"
        >
          <template #icon><IconifyIcon icon="lucide:circle-stop" /></template>
          结束填报
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
      :scroll="{ x: 1320 }"
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
        <template v-else-if="column.key === 'deadlineTime'">
          {{ formatDateTime(record.deadlineTime) || '-' }}
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
          <Button
            v-if="taskOpen && ASSIGNEE_EDITABLE.has(record.status)"
            v-access:code="'dataforge:fill-task:extend'"
            size="small"
            type="link"
            @click="openExtendModal(record)"
          >
            <template #icon><IconifyIcon icon="lucide:clock-plus" /></template>
            延期
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
    <Modal
      v-model:open="extendOpen"
      :confirm-loading="extendSaving"
      :title="
        extendItem
          ? `延期截止时间：${extendItem.orgName ?? extendItem.id}`
          : '延期截止时间：全部未提交组织'
      "
      ok-text="确认延期"
      @ok="handleExtendSave"
    >
      <div class="flex flex-col gap-3 py-2">
        <div>
          <div class="mb-1 text-sm font-medium">新的截止时间</div>
          <DatePicker
            v-model:value="extendDeadline"
            :disabled-date="disabledPastDate"
            :show-time="{ format: 'HH:mm' }"
            class="w-full"
            format="YYYY-MM-DD HH:mm"
            placeholder="选择新的截止时间"
          />
        </div>
        <div>
          <div class="mb-1 text-sm font-medium">延期原因</div>
          <Input
            v-model:value="extendReason"
            :maxlength="200"
            placeholder="如：部分单位系统维护，统一延期两天（记入操作日志）"
          />
        </div>
        <p class="text-muted-foreground text-xs">
          只推后、不提前：截止时间已晚于所选时间的组织保持不变。延期后清除逾期标记，硬截止锁定随之解除，并通知相关填报人。
          {{ extendItem ? '' : '同时更新任务的截止时间。' }}
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
