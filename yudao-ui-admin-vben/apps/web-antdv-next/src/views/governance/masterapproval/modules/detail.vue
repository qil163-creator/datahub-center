<script setup lang="ts">
import type { GovernanceMasterManageApi } from '#/api/governance/mastermanage';
import type { DetailFieldItem } from '#/views/modeling/modelcommon/detail-field-form.vue';

import { computed, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Modal as ConfirmModal,
  Form,
  FormItem,
  Input,
  message,
  Select,
  Tag,
} from 'antdv-next';

import {
  approveMasterData,
  getMasterApproval,
  reconcileMasterApproval,
  rejectMasterData,
  resolveMasterApproval,
} from '#/api/governance/mastermanage';
import { formatJsonText } from '#/views/governance/common/detail-helpers';
import DetailFieldForm from '#/views/modeling/modelcommon/detail-field-form.vue';

import { approvalStatus, operationLabel } from '../../mastercommon/enums';
import { useMasterScope } from '../../mastercommon/use-master-scope';

const emit = defineEmits<{ success: [] }>();
const row = ref<GovernanceMasterManageApi.Approval>();
const remark = ref('');
const failed = ref(false);
const saving = ref(false);
const outcome = ref<'APPLIED' | 'NO_EFFECT'>();
const evidenceRef = ref('');
const reason = ref('');
const fields = computed<DetailFieldItem[]>(() => {
  const detail = row.value;
  return [
    { key: 'id', label: '申请ID', value: detail?.id },
    {
      key: 'modelName',
      label: '所属模型',
      value: detail?.modelName || detail?.modelId,
    },
    { key: 'dataId', label: '数据ID', value: detail?.dataId, span: 2 },
    {
      key: 'operation',
      label: '审批类型',
      value: detail ? operationLabel(detail.operation) : undefined,
    },
    { key: 'status', label: '审批状态' },
    {
      key: 'applicant',
      label: '申请人',
      value: detail?.applicantName || detail?.applicantId,
    },
    {
      key: 'createTime',
      label: '申请时间',
      value: formatDateTime(detail?.createTime),
    },
    {
      key: 'reviewer',
      label: '审批人',
      value: detail?.reviewerName || detail?.reviewerId,
    },
    {
      key: 'reviewTime',
      label: '审批时间',
      value: formatDateTime(detail?.reviewTime),
    },
    {
      key: 'reviewComment',
      label: '审批备注',
      value: detail?.reviewComment,
      span: 2,
      type: 'textarea',
      rows: 2,
    },
    {
      key: 'beforeRows',
      label: '变更前',
      value: formatJsonText(detail?.beforeRows),
      span: 2,
      type: 'textarea',
      rows: 6,
    },
    {
      key: 'rows',
      label: '变更后',
      value: formatJsonText(detail?.rows),
      span: 2,
      type: 'textarea',
      rows: 6,
    },
  ];
});
const scope = useMasterScope(() => {
  void drawerApi.close();
});
async function load(id: GovernanceMasterManageApi.Id) {
  const current = scope.begin('detail');
  failed.value = false;
  drawerApi.setState({ loading: true });
  try {
    const detail = await getMasterApproval(id);
    if (current()) row.value = detail;
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) drawerApi.setState({ loading: false });
  }
}
async function review(approve: boolean) {
  if (!row.value || saving.value) return;
  const current = scope.begin('write');
  saving.value = true;
  drawerApi.lock();
  try {
    await (approve ? approveMasterData : rejectMasterData)({
      id: row.value.id,
      remark: remark.value,
    });
    if (!current()) return;
    message.success(approve ? '审批已通过' : '审批已驳回');
    emit('success');
    await load(row.value.id);
  } finally {
    if (current()) {
      saving.value = false;
      drawerApi.unlock();
    }
  }
}
async function reconcile() {
  if (!row.value || saving.value) return;
  const current = scope.begin('write');
  saving.value = true;
  try {
    await reconcileMasterApproval(row.value.id);
    if (!current()) return;
    emit('success');
    await load(row.value.id);
  } finally {
    if (current()) saving.value = false;
  }
}
function resolve() {
  if (
    !row.value ||
    !outcome.value ||
    !reason.value.trim() ||
    !evidenceRef.value.trim()
  ) {
    message.warning('请填写核查结果、原因与证据引用');
    return;
  }
  const data = {
    id: row.value.id,
    outcome: outcome.value,
    reason: reason.value,
    evidenceRef: evidenceRef.value,
  };
  const authorized = scope.begin('confirmation');
  ConfirmModal.confirm({
    title: '确认核查结果',
    content: '请先核对目标数据。确认后将登记核查结论并结束本次待核查状态。',
    async onOk() {
      if (!authorized()) return;
      const current = scope.begin('write');
      saving.value = true;
      try {
        await resolveMasterApproval(data);
        if (!current()) return;
        emit('success');
        await load(data.id);
      } finally {
        if (current()) saving.value = false;
      }
    },
  });
}
const [Drawer, drawerApi] = useVbenDrawer({
  class: 'w-[960px] max-w-full',
  placement: 'right',
  showConfirmButton: false,
  cancelText: '关闭',
  async onOpenChange(open) {
    scope.invalidate();
    drawerApi.unlock();
    drawerApi.setState({ loading: false });
    row.value = undefined;
    remark.value = '';
    failed.value = false;
    saving.value = false;
    outcome.value = undefined;
    reason.value = '';
    evidenceRef.value = '';
    if (open)
      await load(
        (drawerApi.getData() as { id: GovernanceMasterManageApi.Id }).id,
      );
  },
});
</script>

<template>
  <Drawer title="主数据审批详情">
    <div v-if="row" class="mx-4">
      <DetailFieldForm :fields="fields" label-width="100px">
        <template #status>
          <Tag :color="approvalStatus(row.status).color">
            {{ approvalStatus(row.status).label }}
          </Tag>
        </template>
      </DetailFieldForm>
      <Alert
        v-if="row.lastError"
        class="mt-4"
        type="warning"
        show-icon
        :message="row.lastError"
      />
      <template v-if="row.status === 'PENDING' && !row.bpmProcessKey">
        <Form class="mt-4" layout="vertical">
          <FormItem label="审批意见">
            <Input.TextArea
              v-model:value="remark"
              :rows="3"
              :maxlength="500"
              placeholder="请输入审批意见（可选）"
            />
          </FormItem>
        </Form>
        <div class="mt-4 flex justify-end gap-2">
          <Button
            v-access:code="'governance:master-approval:reject'"
            danger
            :loading="saving"
            @click="review(false)"
          >
            <template #icon><IconifyIcon icon="lucide:x" /></template>
            驳回
          </Button>
          <Button
            v-access:code="'governance:master-approval:approve'"
            type="primary"
            :loading="saving"
            @click="review(true)"
          >
            <template #icon><IconifyIcon icon="lucide:check" /></template>
            通过
          </Button>
        </div>
      </template>
      <div
        v-if="
          row.bpmProcessKey &&
          ['PENDING', 'WAIT_START', 'APPROVED', 'APPLYING'].includes(row.status)
        "
        class="mt-4"
      >
        <Alert
          type="info"
          message="此申请使用审批流程，请在流程任务中处理；可在此同步最新结果。"
          show-icon
        />
        <Button
          v-access:code="'governance:master-approval:reconcile'"
          class="mt-3"
          :loading="saving"
          @click="reconcile"
        >
          <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
          同步审批结果
        </Button>
      </div>
      <template v-if="row.status === 'UNKNOWN'">
        <Alert
          class="mt-4"
          type="warning"
          show-icon
          message="写入结果尚未确认，请核对目标数据后登记结果。"
        />
        <Form
          v-access:code="'governance:master-approval:resolve'"
          class="mt-4"
          layout="vertical"
        >
          <FormItem label="核查结果" required>
            <Select
              v-model:value="outcome"
              placeholder="选择核查结果"
              :options="[
                { label: '已生效', value: 'APPLIED' },
                { label: '未生效', value: 'NO_EFFECT' },
              ]"
            />
          </FormItem>
          <FormItem label="证据引用" required>
            <Input
              v-model:value="evidenceRef"
              :maxlength="500"
              placeholder="请输入核查证据引用"
            />
          </FormItem>
          <FormItem label="核查原因" required>
            <Input.TextArea
              v-model:value="reason"
              :maxlength="500"
              :rows="3"
              placeholder="请输入核查原因"
            />
          </FormItem>
          <div class="flex justify-end">
            <Button :loading="saving" type="primary" @click="resolve">
              <template #icon>
<IconifyIcon icon="lucide:clipboard-check" />
</template>
              登记核查结果
            </Button>
          </div>
        </Form>
      </template>
    </div>
  </Drawer>
</template>
