<script lang="ts" setup>
import type { QualityIssueDataApi } from '#/api/governance/quality/issue';
import type { QualityTaskApi } from '#/api/governance/quality/task';
import type { QualityWorkOrderApi } from '#/api/governance/quality/workorder';

import { computed, onDeactivated, reactive, ref } from 'vue';

import { useVbenModal } from '@vben/common-ui';

import {
  DatePicker,
  Form,
  FormItem,
  Input,
  message,
  Select,
  TextArea,
} from 'antdv-next';

import { getIssueData, getIssueDataPage } from '#/api/governance/quality/issue';
import { getQualityTaskSimpleList } from '#/api/governance/quality/task';
import { createWorkOrder } from '#/api/governance/quality/workorder';
import { getSimpleUserList } from '#/api/system/user';
import { useObjectRequest } from '#/hooks/use-object-request';

import {
  clearReactiveObject,
  GOVERNANCE_ANTD_FORM_LABEL_COL,
  GOVERNANCE_ANTD_FORM_WRAPPER_COL,
  GOVERNANCE_FORM_MODAL_CLASS,
} from '../../../common/form-layout';

const emit = defineEmits<{ saved: [] }>();

const formRef = ref();

const formData = reactive<QualityWorkOrderApi.WorkOrder>({});
const tasks = ref<QualityTaskApi.Task[]>([]);
const issues = ref<QualityIssueDataApi.IssueData[]>([]);
const users = ref<{ id: QualityWorkOrderApi.Id; nickname: string }[]>([]);
const opened = ref(false);
const notificationMode = ref(false);
const opening = useObjectRequest(() => '', close);
const issueRequest = useObjectRequest(
  () => `${opened.value}/${formData.taskId}`,
);
const submission = useObjectRequest(() => opened.value);

const rules = computed(() => ({
  orderName: [{ required: true, message: '请输入工单名称' }],
  ownerUserId: notificationMode.value
    ? [{ required: true, message: '请选择责任人' }]
    : [],
}));

function close() {
  opening.begin();
  issueRequest.begin();
  submission.begin();
  opened.value = false;
  issues.value = [];
  tasks.value = [];
  users.value = [];
  void modalApi.close();
}

async function open(preset?: {
  issueIds?: QualityWorkOrderApi.Id[];
  orderName?: string;
  requireOwner?: boolean;
  taskId?: QualityWorkOrderApi.Id;
}) {
  close();
  clearReactiveObject(formData as Record<string, unknown>);
  issues.value = [];
  notificationMode.value = preset?.requireOwner ?? false;
  opened.value = true;
  const current = opening.begin();
  modalApi.unlock();
  modalApi.open();
  const [taskList, userList] = await Promise.all([
    getQualityTaskSimpleList(),
    getSimpleUserList(),
  ]);
  if (!current()) return;
  tasks.value = taskList;
  users.value = userList as any;
  if (preset?.taskId) {
    formData.taskId = preset.taskId;
    formData.orderName = preset.orderName;
    await handleTaskChange(preset.taskId);
    if (!current() || String(formData.taskId) !== String(preset.taskId)) return;
  }
  if (preset?.issueIds?.length) {
    const knownIds = new Set(issues.value.map((issue) => String(issue.id)));
    const missing = await Promise.all(
      preset.issueIds
        .filter((id) => !knownIds.has(String(id)))
        .map((id) => getIssueData(id)),
    );
    if (
      !current() ||
      (preset.taskId !== undefined &&
        String(formData.taskId) !== String(preset.taskId))
    )
      return;
    issues.value.push(...missing);
    formData.issueIds = preset.issueIds;
  }
  if (preset?.orderName) {
    formData.orderName = preset.orderName;
  }
}

async function handleTaskChange(value: any) {
  const current = issueRequest.begin();
  formData.issueIds = [];
  issues.value = [];
  if (!value) {
    issues.value = [];
    return;
  }
  const result = await getIssueDataPage({
    pageNo: 1,
    pageSize: 100,
    taskId: value,
    issueStatus: 'UNPROCESSED',
  });
  if (!current()) return;
  issues.value = result.list ?? [];
}

async function handleOk() {
  const current = submission.begin();
  await formRef.value?.validate();
  if (!current()) return;
  modalApi.lock();
  try {
    await createWorkOrder({
      ...formData,
      issueIds: formData.issueIds ? [...formData.issueIds] : undefined,
    });
    if (!current()) return;
    message.success('创建成功，工单已进入责任人待办');
    await modalApi.close();
    emit('saved');
  } finally {
    if (current()) modalApi.unlock();
  }
}

const [Modal, modalApi] = useVbenModal({
  onOpenChange(isOpen) {
    if (!isOpen && opened.value) close();
  },
  async onConfirm() {
    await handleOk();
  },
});

onDeactivated(close);
defineExpose({ open, close });
</script>

<template>
  <Modal
    :class="GOVERNANCE_FORM_MODAL_CLASS"
    :title="notificationMode ? '通知处理' : '创建问题工单'"
  >
    <Form
      ref="formRef"
      class="mx-4"
      layout="horizontal"
      :label-col="GOVERNANCE_ANTD_FORM_LABEL_COL"
      :model="formData"
      :rules="rules"
      :wrapper-col="GOVERNANCE_ANTD_FORM_WRAPPER_COL"
    >
      <FormItem label="工单名称" name="orderName">
        <Input
          v-model:value="formData.orderName"
          placeholder="请输入工单名称"
        />
      </FormItem>
      <FormItem label="责任人" name="ownerUserId">
        <Select
          v-model:value="formData.ownerUserId"
          :options="
            users.map((user) => ({ label: user.nickname, value: user.id }))
          "
          option-filter-prop="label"
          placeholder="请选择责任人"
          show-search
        />
      </FormItem>
      <FormItem label="所属任务" name="taskId">
        <Select
          v-model:value="formData.taskId"
          :options="
            tasks.map((task) => ({ label: task.taskName, value: task.id }))
          "
          allow-clear
          placeholder="请选择所属检核任务"
          show-search
          @change="handleTaskChange"
        />
      </FormItem>
      <FormItem label="关联问题" name="issueIds">
        <Select
          v-model:value="formData.issueIds"
          :options="
            issues.map((issue) => ({
              label: `#${issue.id} ${issue.tableName}.${issue.checkField ?? ''}（${issue.issueCount} 行）`,
              value: issue.id,
            }))
          "
          mode="multiple"
          placeholder="选择该任务产生的未处理问题（可多选）"
        />
      </FormItem>
      <FormItem label="整改截止时间" name="deadline">
        <DatePicker
          v-model:value="formData.deadline as any"
          class="w-full"
          format="YYYY-MM-DD HH:mm:ss"
          show-time
          value-format="YYYY-MM-DD HH:mm:ss"
        />
      </FormItem>
      <FormItem label="描述" name="description">
        <TextArea
          v-model:value="formData.description"
          :rows="3"
          placeholder="请输入描述"
        />
      </FormItem>
    </Form>
  </Modal>
</template>
