<script lang="ts" setup>
import type { Dayjs } from 'dayjs';

import type { DataforgeFillApi } from '#/api/dataforge/fill';

import { computed, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Checkbox,
  DatePicker,
  Drawer,
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
  cancelChild,
  dispatchChildren,
  getDispatchCandidates,
  getWorkItemChildren,
  returnChild,
  reviewChild,
  rollupChildren,
  updateChildDeadline,
  urgeChildren,
} from '#/api/dataforge/fill';
import { getSimpleUserList } from '#/api/system/user';

import ApprovalView from '../approval-view/index.vue';
import { WORK_ITEM_STATUS_TEXT } from '../shared';
import { childActions, defaultChildDeadline, subtreeDeptIds } from './hierarchy';

/**
 * 工作台“下级单位”：向下级下发、审核下级提交、退回、撤销、汇总下级数据。
 * 汇总前先由工作台冲刷本端未同步的编辑（flush），汇总后工作台重新加载草稿（changed）。
 */
defineOptions({ name: 'DataforgeFillChildrenPanel' });

const props = defineProps<{
  /** 本单位截止时间（毫秒时间戳或字符串） */
  deadlineTime?: number | string;
  /** 本单位仍可编辑（尚未上报） */
  editable: boolean;
  flush: () => Promise<void>;
  getVersion: () => number;
  workItemId: number | string;
}>();

const emit = defineEmits<{ changed: [] }>();

const open = defineModel<boolean>('open', { default: false });

const loading = ref(false);
const data = ref<DataforgeFillApi.Children>();
let loadSeq = 0;

async function load() {
  const seq = ++loadSeq;
  loading.value = true;
  try {
    const result = await getWorkItemChildren(props.workItemId);
    if (seq === loadSeq) data.value = result;
  } finally {
    if (seq === loadSeq) loading.value = false;
  }
}

watch(open, (value) => {
  if (value) void load();
});
watch(
  () => props.workItemId,
  () => {
    data.value = undefined;
    if (open.value) void load();
  },
);

defineExpose({ reload: () => (open.value ? load() : Promise.resolve()) });

const rollup = computed(() => data.value?.rollup);
const children = computed(() => data.value?.children ?? []);

const columns = [
  { dataIndex: 'orgName', key: 'orgName', title: '下级单位', width: 160, ellipsis: true },
  { key: 'status', title: '状态', width: 150 },
  { key: 'deadlineTime', title: '截止时间', width: 140 },
  { key: 'assignees', title: '填报人', width: 150, ellipsis: true },
  { key: 'included', title: '汇总', width: 90 },
  { key: 'actions', title: '操作', width: 260 },
];

// ---------------------------------------------------------------- 汇总、催办
const rollingUp = ref(false);
async function handleRollup() {
  if (rollingUp.value) return;
  rollingUp.value = true;
  try {
    await props.flush();
    const resp = await rollupChildren(props.workItemId, props.getVersion());
    message.success(`已汇总 ${resp.childCount} 个下级，生成 ${resp.rollupRowCount} 行（草稿共 ${resp.draftRowCount} 行）`);
    emit('changed');
    await load();
  } finally {
    rollingUp.value = false;
  }
}

const urging = ref(false);
async function handleUrge() {
  urging.value = true;
  try {
    const count = await urgeChildren(props.workItemId);
    message.success(count > 0 ? `已催办 ${count} 个下级` : '没有需要催办的下级（或在催办频控内）');
  } finally {
    urging.value = false;
  }
}

// ---------------------------------------------------------------- 查看与审核
const viewOpen = ref(false);
const viewChild = ref<DataforgeFillApi.ChildItem>();
const reviewReason = ref('');
const reviewing = ref(false);

function openView(child: DataforgeFillApi.ChildItem) {
  viewChild.value = child;
  reviewReason.value = '';
  viewOpen.value = true;
}

async function handleReview(approve: boolean) {
  const child = viewChild.value;
  if (!child || reviewing.value) return;
  if (!approve && !reviewReason.value.trim()) {
    message.warning('驳回时请填写原因');
    return;
  }
  reviewing.value = true;
  try {
    await reviewChild({
      approve,
      childWorkItemId: child.workItemId,
      reason: reviewReason.value.trim() || undefined,
    });
    message.success(approve ? '已审核通过，记得“汇总下级数据”' : '已驳回，下级将收到整改通知');
    viewOpen.value = false;
    await load();
  } finally {
    reviewing.value = false;
  }
}

// ---------------------------------------------------------------- 退回、撤销、调整截止
const actionOpen = ref(false);
const actionKind = ref<'cancel' | 'deadline' | 'return'>('return');
const actionChild = ref<DataforgeFillApi.ChildItem>();
const actionReason = ref('');
const actionDeadline = ref<Dayjs>();
const actionSaving = ref(false);
const actionTitle = computed(() => {
  const name = actionChild.value?.orgName ?? '';
  return actionKind.value === 'return'
    ? `退回：${name}`
    : actionKind.value === 'cancel'
      ? `撤销下发：${name}`
      : `调整截止时间：${name}`;
});

function openAction(kind: 'cancel' | 'deadline' | 'return', child: DataforgeFillApi.ChildItem) {
  actionKind.value = kind;
  actionChild.value = child;
  actionReason.value = '';
  actionDeadline.value = child.deadlineTime ? dayjs(child.deadlineTime) : undefined;
  actionOpen.value = true;
}

async function handleAction() {
  const child = actionChild.value;
  if (!child || actionSaving.value) return;
  if (actionKind.value === 'return' && !actionReason.value.trim()) {
    message.warning('退回时请填写原因');
    return;
  }
  if (actionKind.value === 'deadline' && !actionDeadline.value) {
    message.warning('请选择新的截止时间');
    return;
  }
  actionSaving.value = true;
  try {
    if (actionKind.value === 'return') {
      await returnChild({ childWorkItemId: child.workItemId, reason: actionReason.value.trim() });
      message.success('已退回，下级修改后需重新提交');
    } else if (actionKind.value === 'cancel') {
      const count = await cancelChild({
        childWorkItemId: child.workItemId,
        reason: actionReason.value.trim() || undefined,
      });
      message.success(`已撤销 ${count} 个实例`);
    } else {
      await updateChildDeadline({
        childWorkItemId: child.workItemId,
        deadlineTime: actionDeadline.value!.valueOf(),
      });
      message.success('截止时间已调整');
    }
    actionOpen.value = false;
    await load();
  } finally {
    actionSaving.value = false;
  }
}

// ---------------------------------------------------------------- 下发
interface DispatchRow {
  assigneeUserIds: string[];
  deadline?: Dayjs;
  deptId: string;
  name: string;
  selected: boolean;
}
const dispatchOpen = ref(false);
const dispatchLoading = ref(false);
const dispatchSaving = ref(false);
const saveAsDefault = ref(true);
const candidates = ref<DataforgeFillApi.DispatchCandidate[]>([]);
const dispatchRows = ref<DispatchRow[]>([]);
const users = ref<{ deptId?: number | string; id?: number | string; nickname: string }[]>([]);
let dispatchSeq = 0;

async function openDispatch() {
  const seq = ++dispatchSeq;
  dispatchOpen.value = true;
  dispatchLoading.value = true;
  dispatchRows.value = [];
  try {
    const [list, userList] = await Promise.all([
      getDispatchCandidates(props.workItemId),
      getSimpleUserList(),
    ]);
    if (seq !== dispatchSeq) return;
    candidates.value = list;
    users.value = userList as typeof users.value;
    const now = Date.now();
    dispatchRows.value = list
      .filter((candidate) => !candidate.inTask)
      .map((candidate) => {
        const deadline = defaultChildDeadline(props.deadlineTime, candidate.defaultOffsetHours, now);
        return {
          assigneeUserIds: (candidate.defaultAssignees ?? []).map((user) => String(user.userId)),
          deadline: deadline === undefined ? undefined : dayjs(deadline),
          deptId: String(candidate.deptId),
          name: candidate.name,
          selected: (candidate.defaultAssignees ?? []).length > 0,
        };
      });
  } finally {
    if (seq === dispatchSeq) dispatchLoading.value = false;
  }
}

function userOptions(deptId: string) {
  const scope = subtreeDeptIds(candidates.value, deptId);
  return users.value
    .filter((user) => user.deptId !== undefined && scope.has(String(user.deptId)))
    .map((user) => ({ label: user.nickname, value: String(user.id) }));
}

const dispatchColumns = [
  { key: 'selected', title: '', width: 48 },
  { dataIndex: 'name', key: 'name', title: '下级组织', width: 160, ellipsis: true },
  { key: 'assignees', title: '填报人（该组织及下属部门）', width: 260 },
  { key: 'deadline', title: '截止时间', width: 200 },
];

const parentDeadline = computed(() =>
  props.deadlineTime === undefined || props.deadlineTime === null ? undefined : dayjs(props.deadlineTime),
);
function disabledDeadline(current: Dayjs) {
  return (
    current.isBefore(dayjs().startOf('day')) ||
    (parentDeadline.value !== undefined && current.isAfter(parentDeadline.value, 'day'))
  );
}

async function handleDispatch() {
  const selected = dispatchRows.value.filter((row) => row.selected);
  if (selected.length === 0) {
    message.warning('请勾选要下发的下级组织');
    return;
  }
  const missing = selected.find((row) => row.assigneeUserIds.length === 0 || !row.deadline);
  if (missing) {
    message.warning(`【${missing.name}】请指定填报人和截止时间`);
    return;
  }
  dispatchSaving.value = true;
  try {
    const count = await dispatchChildren(props.workItemId, {
      orgs: selected.map((row) => ({
        assigneeUserIds: row.assigneeUserIds,
        deadlineTime: row.deadline!.valueOf(),
        reportingOrgId: row.deptId,
      })),
      saveAsDefault: saveAsDefault.value,
    });
    message.success(`已下发 ${count} 个下级组织`);
    dispatchOpen.value = false;
    await load();
  } finally {
    dispatchSaving.value = false;
  }
}

function assigneeText(child: DataforgeFillApi.ChildItem) {
  return (child.assignees ?? []).map((user) => user.userName ?? user.userId).join('、') || '-';
}
</script>

<template>
  <Drawer v-model:open="open" :width="1000" title="下级单位">
    <div class="flex flex-col gap-3">
      <Alert
        v-if="rollup && rollup.totalCount > 0"
        :type="rollup.readyToSubmit ? 'success' : 'warning'"
        show-icon
      >
        <template #message>
          {{ rollup.mode === 'GROUP' ? '分组合计' : '明细汇集' }}：共 {{ rollup.totalCount }} 个下级，已审核通过
          {{ rollup.rolledUpCount }} 个，等待审核 {{ rollup.reviewingCount }} 个
          <span v-if="rollup.lastRolledAt">，最近汇总 {{ formatDateTime(rollup.lastRolledAt) }}</span>
        </template>
        <template #description>
          {{ rollup.readyToSubmit ? '下级数据已全部汇总，可以提交上报。' : rollup.blockReason }}
        </template>
      </Alert>
      <Alert
        v-else-if="!loading"
        message="本单位还没有向下级下发。需要下级单位填报时，点击“向下级下发”。"
        show-icon
        type="info"
      />

      <Space wrap>
        <Button v-if="editable" type="primary" @click="openDispatch">
          <template #icon><IconifyIcon icon="lucide:git-fork" /></template>
          向下级下发
        </Button>
        <Button
          v-if="editable"
          :disabled="!rollup || (rollup.rolledUpCount === 0 && !rollup.stale)"
          :loading="rollingUp"
          @click="handleRollup"
        >
          <template #icon><IconifyIcon icon="lucide:sigma" /></template>
          汇总下级数据
        </Button>
        <Button :loading="urging" @click="handleUrge">
          <template #icon><IconifyIcon icon="lucide:bell-ring" /></template>
          催办下级
        </Button>
        <Button :loading="loading" @click="load">
          <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
          刷新
        </Button>
      </Space>
      <p v-if="!editable" class="text-muted-foreground m-0 text-xs">
        本单位已上报或当前不可编辑：可以查看下级，处理下级需等本单位被退回后进行。
      </p>

      <Table
        :columns="columns"
        :data-source="children"
        :loading="loading"
        :pagination="false"
        :row-key="(record: any) => String(record.workItemId)"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'status'">
            <Tag :color="WORK_ITEM_STATUS_TEXT[record.status]?.color">
              {{ record.awaitingReview ? '待本单位审核' : (WORK_ITEM_STATUS_TEXT[record.status]?.text ?? record.status) }}
            </Tag>
            <Tag v-if="record.overdue === 1" color="error">逾期</Tag>
            <Tag v-if="(record.childCount ?? 0) > 0">再下发 {{ record.childCount }}</Tag>
          </template>
          <template v-else-if="column.key === 'deadlineTime'">
            {{ formatDateTime(record.deadlineTime) || '-' }}
          </template>
          <template v-else-if="column.key === 'assignees'">{{ assigneeText(record) }}</template>
          <template v-else-if="column.key === 'included'">
            <Tag v-if="record.included" color="success">已汇总</Tag>
            <Tag v-else-if="record.status === 11" color="warning">待汇总</Tag>
            <span v-else class="text-muted-foreground">-</span>
          </template>
          <template v-else-if="column.key === 'actions'">
            <Space :size="4" wrap>
              <Button
                v-if="childActions(record, editable).view"
                size="small"
                :type="childActions(record, editable).review ? 'primary' : 'link'"
                @click="openView(record)"
              >
                {{ childActions(record, editable).review ? '审核' : '查看数据' }}
              </Button>
              <Button
                v-if="childActions(record, editable).returnBack"
                size="small"
                type="link"
                @click="openAction('return', record)"
              >
                退回
              </Button>
              <Button
                v-if="childActions(record, editable).deadline"
                size="small"
                type="link"
                @click="openAction('deadline', record)"
              >
                调整截止
              </Button>
              <Button
                v-if="childActions(record, editable).cancel"
                danger
                size="small"
                type="link"
                @click="openAction('cancel', record)"
              >
                撤销下发
              </Button>
            </Space>
          </template>
        </template>
      </Table>
    </div>

    <Modal v-model:open="viewOpen" :footer="null" :title="`下级数据：${viewChild?.orgName ?? ''}`" :width="1100">
      <ApprovalView
        v-if="viewOpen && viewChild?.currentSubmissionId !== undefined"
        :id="String(viewChild.currentSubmissionId)"
      />
      <div
        v-if="viewChild && childActions(viewChild, editable).review"
        class="mt-3 flex items-center gap-2 border-t pt-3"
      >
        <Input
          v-model:value="reviewReason"
          :maxlength="200"
          class="flex-1"
          placeholder="审核意见（驳回时必填）"
        />
        <Button :loading="reviewing" danger @click="handleReview(false)">驳回</Button>
        <Button :loading="reviewing" type="primary" @click="handleReview(true)">审核通过</Button>
      </div>
    </Modal>

    <Modal
      v-model:open="actionOpen"
      :confirm-loading="actionSaving"
      :title="actionTitle"
      @ok="handleAction"
    >
      <div class="flex flex-col gap-3 py-2">
        <DatePicker
          v-if="actionKind === 'deadline'"
          v-model:value="actionDeadline"
          :disabled-date="disabledDeadline"
          :show-time="{ format: 'HH:mm' }"
          class="w-full"
          format="YYYY-MM-DD HH:mm"
        />
        <Input
          v-else
          v-model:value="actionReason"
          :maxlength="200"
          :placeholder="actionKind === 'return' ? '退回原因（必填，下级会收到通知）' : '撤销原因（选填）'"
        />
        <p class="text-muted-foreground m-0 text-xs">
          <template v-if="actionKind === 'return'">退回后下级回到整改中，本单位的汇总会标记为过期，需下级重新提交并审核后再汇总。</template>
          <template v-else-if="actionKind === 'cancel'">撤销后该下级及其再往下未提交的实例都改为已取消，不再需要填报。</template>
          <template v-else>截止时间不能晚于本单位截止时间 {{ formatDateTime(deadlineTime) || '-' }}。</template>
        </p>
      </div>
    </Modal>

    <Modal
      v-model:open="dispatchOpen"
      :confirm-loading="dispatchSaving"
      :width="860"
      ok-text="下发"
      title="向下级下发"
      @ok="handleDispatch"
    >
      <div class="flex flex-col gap-3 py-2">
        <p class="text-muted-foreground m-0 text-xs">
          只能下发给本单位的下属部门；已在本期任务中的部门不再列出。下级截止时间不能晚于本单位截止时间
          {{ formatDateTime(deadlineTime) || '-' }}。
        </p>
        <Table
          :columns="dispatchColumns"
          :data-source="dispatchRows"
          :loading="dispatchLoading"
          :pagination="false"
          :scroll="{ y: 420 }"
          row-key="deptId"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'selected'">
              <Checkbox v-model:checked="record.selected" />
            </template>
            <template v-else-if="column.key === 'assignees'">
              <Select
                v-model:value="record.assigneeUserIds"
                :disabled="!record.selected"
                :options="userOptions(record.deptId)"
                class="w-full"
                mode="multiple"
                option-filter-prop="label"
                placeholder="选择填报人"
                size="small"
              />
            </template>
            <template v-else-if="column.key === 'deadline'">
              <DatePicker
                v-model:value="record.deadline"
                :disabled="!record.selected"
                :disabled-date="disabledDeadline"
                :show-time="{ format: 'HH:mm' }"
                format="YYYY-MM-DD HH:mm"
                size="small"
              />
            </template>
          </template>
        </Table>
        <Checkbox v-model:checked="saveAsDefault">
          保存为后续期次的默认下发名单（之后每期生成任务时自动下发给这些下级）
        </Checkbox>
      </div>
    </Modal>
  </Drawer>
</template>
