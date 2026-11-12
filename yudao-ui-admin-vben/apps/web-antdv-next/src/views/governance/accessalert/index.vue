<script lang="ts" setup>
import type { SafetySecurityApi } from '#/api/governance/security';

import { computed, onBeforeUnmount, onMounted, reactive, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';

import {
  Alert,
  Button,
  Card,
  message,
  Modal,
  RadioGroup,
  Select,
  Table,
  Tag,
  TextArea,
} from 'antdv-next';

import {
  getAccessAlertPage,
  handleAccessAlert,
} from '#/api/governance/security';
import { createLatestWins } from '#/utils/resource-id';

import {
  ALERT_RULE_OPTIONS,
  ALERT_STATUS_OPTIONS,
  formatTime,
  ruleLabel,
  ruleUnit,
  statusView,
} from './data';

defineOptions({ name: 'SafetyAccessAlert' });

const { hasAccessByCodes } = useAccess();
const canQuery = computed(() =>
  hasAccessByCodes(['governance:access-alert:query']),
);
const canHandle = computed(() =>
  hasAccessByCodes(['governance:access-alert:handle']),
);

const filters = reactive<{ ruleCode?: string; status?: string }>({
  status: 'OPEN',
});
const page = reactive({ pageNo: 1, pageSize: 20 });
const rows = ref<SafetySecurityApi.AccessAlert[]>([]);
const total = ref(0);
const loading = ref(false);
const guard = createLatestWins();

const columns = [
  { title: '规则', key: 'ruleCode', width: 160 },
  { title: '用户', key: 'user', width: 160 },
  { title: '统计窗口', key: 'window', width: 280 },
  { title: '统计值 / 阈值', key: 'metric', width: 140 },
  { title: '状态', key: 'status', width: 110 },
  { title: '处理说明', key: 'handle', ellipsis: true },
  { title: '操作', key: 'actions', width: 100, fixed: 'right' as const },
];

async function load() {
  if (!canQuery.value) return;
  const ticket = guard.next();
  loading.value = true;
  try {
    const result = await getAccessAlertPage({
      pageNo: page.pageNo,
      pageSize: page.pageSize,
      ...filters,
    });
    if (!ticket.isCurrent()) return;
    rows.value = result?.list ?? [];
    total.value = result?.total ?? 0;
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}

function search() {
  page.pageNo = 1;
  void load();
}

function changePage(pageNo: number, pageSize: number) {
  page.pageNo = pageNo;
  page.pageSize = pageSize;
  void load();
}

const target = ref<SafetySecurityApi.AccessAlert>();
const decision = ref<'CONFIRMED' | 'IGNORED'>('CONFIRMED');
const remark = ref('');
const submitting = ref(false);
const handleError = ref<string>();

function openHandle(record: SafetySecurityApi.AccessAlert) {
  target.value = record;
  decision.value = 'CONFIRMED';
  remark.value = '';
  handleError.value = undefined;
}

async function submitHandle() {
  const alert = target.value;
  if (!alert || submitting.value) return;
  if (!remark.value.trim()) {
    handleError.value = '请填写处理说明';
    return;
  }
  submitting.value = true;
  try {
    await handleAccessAlert({
      id: alert.id,
      status: decision.value,
      remark: remark.value.trim(),
      expectedRevision: alert.revision,
    });
    message.success('已处理');
    target.value = undefined;
  } finally {
    submitting.value = false;
    await load();
  }
}

onMounted(load);
onBeforeUnmount(() => guard.invalidate());
</script>

<template>
  <Page auto-content-height>
    <Alert
      v-if="!canQuery"
      type="warning"
      show-icon
      message="当前账号没有异常访问查询权限"
    />
    <Card v-else title="异常访问" size="small">
      <template #extra>
        <div class="flex flex-wrap gap-2">
          <Select
            v-model:value="filters.ruleCode"
            :options="[...ALERT_RULE_OPTIONS]"
            allow-clear
            class="w-40"
            placeholder="全部规则"
            size="small"
            @change="search"
          />
          <Select
            v-model:value="filters.status"
            :options="[...ALERT_STATUS_OPTIONS]"
            allow-clear
            class="w-32"
            placeholder="全部状态"
            size="small"
            @change="search"
          />
          <Button size="small" :loading="loading" @click="load">刷新</Button>
        </div>
      </template>
      <Alert
        class="mb-3"
        type="info"
        show-icon
        message="按用户、按统计窗口汇总敏感数据访问审计；超过阈值即告警。告警只含统计值，详细访问记录见“访问审计”。"
      />
      <Table
        :columns="columns"
        :data-source="rows"
        :loading="loading"
        :pagination="{
          current: page.pageNo,
          pageSize: page.pageSize,
          total,
          showSizeChanger: true,
          onChange: changePage,
        }"
        :row-key="(record: SafetySecurityApi.AccessAlert) => String(record.id)"
        :scroll="{ x: 1100 }"
        size="small"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'ruleCode'">
{{
            ruleLabel(record.ruleCode)
          }}
</template>
          <template v-else-if="column.key === 'user'">
            {{ record.userName || '-' }}（{{ record.userId }}）
          </template>
          <template v-else-if="column.key === 'window'">
            {{ formatTime(record.windowStart) }} ~
            {{ formatTime(record.windowEnd) }}
          </template>
          <template v-else-if="column.key === 'metric'">
            {{ record.metricValue }} / {{ record.threshold }}
            {{ ruleUnit(record.ruleCode) }}
          </template>
          <template v-else-if="column.key === 'status'">
            <Tag :color="statusView(record.status).color">
{{
              statusView(record.status).label
            }}
</Tag>
          </template>
          <template v-else-if="column.key === 'handle'">
{{
            record.handleRemark || '-'
          }}
</template>
          <template v-else-if="column.key === 'actions'">
            <Button
              v-if="canHandle && record.status === 'OPEN'"
              size="small"
              type="link"
              @click="openHandle(record)"
            >
              处理
            </Button>
          </template>
        </template>
      </Table>
    </Card>

    <Modal
      :confirm-loading="submitting"
      :open="!!target"
      title="处理异常访问告警"
      @cancel="target = undefined"
      @ok="submitHandle"
    >
      <RadioGroup
        v-model:value="decision"
        class="mb-3"
        :options="[
          { label: '确认风险', value: 'CONFIRMED' },
          { label: '忽略（误报）', value: 'IGNORED' },
        ]"
      />
      <TextArea
        v-model:value="remark"
        :maxlength="500"
        :rows="3"
        placeholder="处理说明（必填）"
        show-count
      />
      <div v-if="handleError" class="text-destructive mt-1 text-xs">
        {{ handleError }}
      </div>
    </Modal>
  </Page>
</template>
