<script lang="ts" setup>
import type { OperationState, WriteOperation } from '#/api/supply/operation';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Card,
  Form,
  FormItem,
  Input,
  message,
  Modal,
  Select,
  Space,
  Tag,
} from 'antdv-next';

import {
  getWriteOperations,
  resolveWriteOperation,
} from '#/api/supply/operation';
import TableViewport from '#/components/table-viewport/index.vue';

import { formatSupplyDateTime } from '../../utils/format';
import { createLatestGuard } from '../../utils/latest';

defineOptions({ name: 'SupplyWriteOperation' });
const access = useAccessStore();
const guard = createLatestGuard();
let alive = true;
const rows = ref<WriteOperation[]>([]);
const state = ref<OperationState>();
const apiId = ref('');
const operationKey = ref('');
const loading = ref(false);
const more = ref(false);
const afterId = ref<number | string>();
const selected = ref<WriteOperation>();
const decision = ref<'CONFIRMED_NOT_APPLIED' | 'CONFIRMED_SUCCEEDED'>(
  'CONFIRMED_SUCCEEDED',
);
const reason = ref('');
const evidence = ref('');
const saving = ref(false);
const labels: Record<OperationState, string> = {
  RUNNING: '执行中',
  SUCCEEDED: '已确认成功',
  UNKNOWN: '结果未知',
  CONFIRMED_SUCCEEDED: '人工确认成功',
  CONFIRMED_NOT_APPLIED: '人工确认未写入',
};
const options = Object.entries(labels).map(([value, label]) => ({
  value,
  label,
}));
const canResolve = computed(() => selected.value?.state === 'UNKNOWN');

async function load(reset = true) {
  if (!alive) return;
  if (apiId.value && !/^[1-9]\d*$/.test(apiId.value)) {
    message.warning('API编号应为正整数');
    return;
  }
  if (reset) afterId.value = undefined;
  const stale = guard.next();
  loading.value = true;
  try {
    const result = await getWriteOperations({
      apiId: apiId.value || undefined,
      operationKey: operationKey.value.trim() || undefined,
      state: state.value,
      afterId: afterId.value,
    });
    if (stale()) return;
    rows.value = result.slice(0, 50);
    more.value = result.length > 50;
  } finally {
    if (!stale()) loading.value = false;
  }
}
function nextPage() {
  afterId.value = rows.value.at(-1)?.id;
  void load(false);
}
function open(row: WriteOperation) {
  selected.value = row;
  reason.value = '';
  evidence.value = '';
  decision.value = 'CONFIRMED_SUCCEEDED';
}
async function save() {
  if (!selected.value || saving.value || !canResolve.value) return;
  if (!reason.value.trim() || !evidence.value.trim()) {
    message.warning('请填写核查原因及目标库核查证据引用');
    return;
  }
  const session = `${access.accessToken}/${access.refreshToken}`;
  saving.value = true;
  try {
    await resolveWriteOperation({
      id: selected.value.id,
      decision: decision.value,
      reason: reason.value.trim(),
      evidence: evidence.value.trim(),
    });
    if (!alive || session !== `${access.accessToken}/${access.refreshToken}`)
      return;
    message.success('已记录核查结论，原业务操作编号仍不会再次执行');
    selected.value = undefined;
    await load();
  } finally {
    saving.value = false;
  }
}
watch(
  () => `${access.accessToken}/${access.refreshToken}`,
  () => {
    guard.invalidate();
    rows.value = [];
    selected.value = undefined;
    afterId.value = undefined;
    void load();
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  alive = false;
  guard.invalidate();
});
</script>

<template>
  <div class="flex h-full min-h-0 flex-1 flex-col overflow-hidden">
    <Alert
      class="mb-4 shrink-0"
      type="info"
      show-icon
      message="同一业务操作编号只受理一次。结果未知时先核查目标库，不能换编号盲目重试；人工核查只登记证据，不重新执行SQL。"
    />
    <Card
      class="min-h-0 flex-1 overflow-hidden"
      :styles="{
        body: {
          display: 'flex',
          flexDirection: 'column',
          height: '100%',
          minHeight: 0,
          overflow: 'hidden',
        },
      }"
    >
      <Space class="mb-4 shrink-0" wrap>
        <Input
          v-model:value="apiId"
          placeholder="API编号"
          allow-clear
          aria-label="API编号"
        />
        <Select
          v-model:value="state"
          :options="options"
          placeholder="操作状态"
          allow-clear
          class="w-48"
        />
        <Input
          v-model:value="operationKey"
          placeholder="业务操作编号（精确匹配）"
          :maxlength="128"
          allow-clear
          aria-label="业务操作编号"
        />
        <Button type="primary" :loading="loading" @click="load()">查询</Button>
      </Space>
      <TableViewport
        class="supply-font-table"
        :data-source="rows"
        :loading="loading"
        :pagination="false"
        row-key="id"
        :scroll="{ x: 1200 }"
        :columns="[
          { title: '操作编号', dataIndex: 'operationKey', width: 240 },
          { title: 'API / 应用', key: 'identity', width: 180 },
          { title: '发布编号', dataIndex: 'releaseId', width: 150 },
          { title: '状态', key: 'state', width: 150 },
          { title: '追踪编号', dataIndex: 'traceId', width: 180 },
          { title: '受理时间', key: 'time', width: 180 },
          { title: '操作', key: 'action', width: 180, fixed: 'right' },
        ]"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'identity'">
            {{ record.apiId }} / {{ record.appId }}
          </template>
          <Tag
            v-else-if="column.key === 'state'"
            :color="record.state === 'UNKNOWN' ? 'warning' : 'default'"
          >
            {{ labels[record.state as OperationState] ?? record.state }}
          </Tag>
          <template v-else-if="column.key === 'time'">
            {{ formatSupplyDateTime(record.createTime) }}
          </template>
          <Button
            v-else-if="column.key === 'action'"
            type="link"
            @click="open(record)"
          >
            <template #icon><IconifyIcon icon="lucide:file-search" /></template>
            详情 / 核查
          </Button>
        </template>
      </TableViewport>
      <Space class="mt-4 shrink-0">
        <Button :disabled="loading || !afterId" @click="load()">
          回到最新
        </Button>
        <Button :disabled="loading || !more" @click="nextPage"> 下一页 </Button>
      </Space>
    </Card>
    <Modal
      :open="!!selected"
      title="写操作核查"
      :footer="null"
      :mask-closable="!saving"
      :closable="!saving"
      @cancel="selected = undefined"
    >
      <template v-if="selected">
        <p class="mb-3">
          业务编号：{{ selected.operationKey }}；{{ labels[selected.state] }}
        </p>
        <template v-if="selected.resolvedAt">
          <p>
            核查人：{{ selected.resolvedBy }}；{{
              formatSupplyDateTime(selected.resolvedAt)
            }}
          </p>
          <p>{{ selected.resolution }}</p>
          <p class="whitespace-pre-wrap">{{ selected.evidence }}</p>
        </template>
        <Form v-else-if="canResolve" layout="vertical">
          <FormItem label="核查结论">
            <Select
              v-model:value="decision"
              :options="[
                { label: '已确认写入成功', value: 'CONFIRMED_SUCCEEDED' },
                { label: '已确认未写入', value: 'CONFIRMED_NOT_APPLIED' },
              ]"
            />
          </FormItem>
          <FormItem label="核查原因" required>
            <Input.TextArea
              v-model:value="reason"
              :maxlength="1000"
              :rows="3"
            />
          </FormItem>
          <FormItem label="目标库核查工单 / 证据引用" required>
            <Input.TextArea
              v-model:value="evidence"
              :maxlength="2000"
              :rows="4"
              placeholder="填写审计记录或核查工单编号，不填写SQL参数、密钥和个人信息"
            />
          </FormItem>
          <Button
            v-access:code="'supply:data-api:operation-resolve'"
            type="primary"
            :loading="saving"
            @click="save"
          >
            记录核查结论
          </Button>
        </Form>
        <p v-else>
          执行中的操作将在失联巡检后转为结果未知；已成功记录不允许改写。
        </p>
      </template>
    </Modal>
  </div>
</template>

<style scoped>
.supply-font-table :deep(.ant-table-thead > tr > th),
.supply-font-table :deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
