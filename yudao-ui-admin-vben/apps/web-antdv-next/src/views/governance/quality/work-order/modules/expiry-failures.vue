<script lang="ts" setup>
import type { ExpiryFailure } from '#/api/governance/quality/workorder-expiry-failure';

import { onBeforeUnmount, ref } from 'vue';

import { useVbenDrawer } from '@vben/common-ui';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Input,
  message,
  Modal,
  Segmented,
  Table,
  Tag,
} from 'antdv-next';

import {
  authorizeExpiryRetry,
  getExpiryFailurePage,
} from '#/api/governance/quality/workorder-expiry-failure';

const STATUS: Record<string, { color: string; label: string }> = {
  RETRYING: { color: 'processing', label: '退避重试中' },
  EXHAUSTED: { color: 'error', label: '次数耗尽，待人工' },
  RESOLVED: { color: 'success', label: '已关闭' },
};
const filterOptions = [
  { label: '待人工', value: 'EXHAUSTED' },
  { label: '重试中', value: 'RETRYING' },
  { label: '已关闭', value: 'RESOLVED' },
];
const columns = [
  { title: '原工单', key: 'order', width: 220, ellipsis: true },
  { title: '状态', key: 'status', width: 150 },
  { title: '失败次数', key: 'attempts', width: 100 },
  { title: '最近失败', key: 'lastError', ellipsis: true },
  { title: '下次自动处理', key: 'next', width: 170 },
  { title: '操作', key: 'actions', width: 110, fixed: 'right' as const },
];

const status = ref('EXHAUSTED');
const rows = ref<ExpiryFailure[]>([]);
const total = ref(0);
const pageNo = ref(1);
const loading = ref(false);
let seq = 0;

const retryTarget = ref<ExpiryFailure>();
const retryReason = ref('');
const retrying = ref(false);

const [Drawer, drawerApi] = useVbenDrawer({
  class: 'w-[1080px] max-w-full',
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(isOpen: boolean) {
    if (isOpen) {
      void load(1);
    } else {
      seq++;
      rows.value = [];
      retryTarget.value = undefined;
    }
  },
});

async function load(page = pageNo.value) {
  const current = ++seq;
  loading.value = true;
  try {
    const result = await getExpiryFailurePage({
      pageNo: page,
      pageSize: 20,
      status: status.value,
    });
    if (current !== seq) return;
    rows.value = result.list;
    total.value = result.total;
    pageNo.value = page;
  } finally {
    if (current === seq) loading.value = false;
  }
}

function openRetry(record: ExpiryFailure) {
  retryTarget.value = record;
  retryReason.value = '';
}

async function confirmRetry() {
  const target = retryTarget.value;
  if (!target || retrying.value) return;
  if (retryReason.value.trim().length < 5) {
    message.warning('请填写不少于 5 个字的排查结论');
    return;
  }
  retrying.value = true;
  try {
    await authorizeExpiryRetry({
      expectedVersion: target.version,
      id: target.id,
      reason: retryReason.value.trim(),
    });
    message.success('已授权重试，下一轮扫描将重新处理该提交');
    retryTarget.value = undefined;
    await load();
  } finally {
    retrying.value = false;
  }
}

function asRow(record: object) {
  return record as ExpiryFailure;
}

onBeforeUnmount(() => {
  seq++;
});

defineExpose({ open: () => drawerApi.open() });
</script>

<template>
  <Drawer title="例外到期处理失败">
    <Alert
      class="mb-3"
      type="info"
      message="例外到期后自动创建复核单失败的提交会在这里登记：自动按退避重试，5 次仍失败转为“待人工”。排查并修复原因后授权重试；到期动作已生效的提交会被自动跳过，不会重复创建复核单。"
    />
    <div class="mb-3 flex items-center justify-between">
      <Segmented
        v-model:value="status"
        :options="filterOptions"
        @change="load(1)"
      />
      <Button :loading="loading" size="small" @click="load()">刷新</Button>
    </div>
    <Table
      :columns="columns"
      :data-source="rows"
      :loading="loading"
      :pagination="{
        current: pageNo,
        pageSize: 20,
        total,
        onChange: (page: number) => load(page),
      }"
      :row-key="(record: object) => String(asRow(record).id)"
      :scroll="{ x: 900 }"
      size="small"
    >
      <template #bodyCell="{ column, record }">
        <template v-if="column.key === 'order'">
          {{ asRow(record).workOrderName || `工单 ${asRow(record).workOrderId}` }}
          <div class="text-xs text-gray-400">
            提交 {{ asRow(record).submissionId }}
          </div>
        </template>
        <template v-else-if="column.key === 'status'">
          <Tag :color="STATUS[asRow(record).status]?.color">
            {{ STATUS[asRow(record).status]?.label ?? asRow(record).status }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'attempts'">
          {{ asRow(record).attemptCount }} / {{ asRow(record).maxAttempts }}
        </template>
        <template v-else-if="column.key === 'lastError'">
          {{ asRow(record).lastError || '-' }}
        </template>
        <template v-else-if="column.key === 'next'">
          {{
            asRow(record).status === 'RETRYING'
              ? formatDateTime(asRow(record).nextAttemptAt) || '-'
              : '-'
          }}
        </template>
        <template v-else-if="column.key === 'actions'">
          <Button
            v-if="asRow(record).status !== 'RESOLVED'"
            v-access:code="'governance:quality-work-order:coordinate'"
            size="small"
            type="link"
            @click="openRetry(asRow(record))"
          >
            授权重试
          </Button>
        </template>
      </template>
    </Table>
    <Modal
      :confirm-loading="retrying"
      :open="!!retryTarget"
      title="授权重试例外到期处理"
      @cancel="retryTarget = undefined"
      @ok="confirmRetry"
    >
      <p class="mb-2">
        将清零自动处理次数并在下一轮扫描立即重新处理。请填写排查结论：
      </p>
      <Input.TextArea
        v-model:value="retryReason"
        :maxlength="500"
        :rows="3"
        placeholder="例如：已修复责任人配置，确认可重新创建复核单"
        show-count
      />
    </Modal>
  </Drawer>
</template>
