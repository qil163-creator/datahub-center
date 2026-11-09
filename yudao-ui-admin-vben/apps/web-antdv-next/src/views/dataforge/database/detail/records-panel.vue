<script setup lang="ts">
import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { DatabaseMonitorApi } from '#/api/dataforge/database/monitor';

import {
  computed,
  onBeforeUnmount,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';

import { useVbenModal } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Descriptions,
  DescriptionsItem,
  Input,
  message,
  Tag,
} from 'antdv-next';

import { ACTION_ICON, TableAction, useVbenVxeGrid } from '#/adapter/vxe-table';
import {
  getDatabaseAlarm,
  getDatabaseAlarmPage,
  getDatabaseMonitorLogPage,
  handleDatabaseAlarm,
} from '#/api/dataforge/database/monitor';
import { createLatestWins } from '#/utils/resource-id';

import {
  ALARM_STATUS,
  LEVEL_COLORS,
  levelLabel,
  LOG_STATUS,
  metricLabel,
  metricValue,
  triggerCondition,
} from './monitor-enums';

const props = defineProps<{
  databaseId: FoundationDatabaseApi.Id;
  mode: 'alarm' | 'log';
}>();
const emit = defineEmits<{ change: [] }>();
type Row = DatabaseMonitorApi.Alarm | DatabaseMonitorApi.Log;
const access = useAccessStore();
const alarm = computed(() => props.mode === 'alarm');
const failed = ref(false);
const page = reactive({ pageNo: 1, pageSize: 20 });
const listGuard = createLatestWins();
const detailGuard = createLatestWins();
const operationGuard = createLatestWins();
const selectedLog = ref<DatabaseMonitorApi.Log>();
const selectedAlarm = ref<DatabaseMonitorApi.Alarm>();
const detailLoading = ref(false);
const detailFailed = ref(false);
const actionTarget = ref<DatabaseMonitorApi.Alarm>();
const action = ref<'HANDLE' | 'IGNORE'>('HANDLE');
const remark = ref('');

const [DetailModal, detailApi] = useVbenModal({
  showConfirmButton: false,
  cancelText: '关闭',
  onOpenChange(open) {
    if (!open) {
      detailGuard.invalidate();
      selectedLog.value = undefined;
      selectedAlarm.value = undefined;
      detailLoading.value = false;
    }
  },
});

const [HandleModal, handleApi] = useVbenModal({
  async onConfirm() {
    const target = actionTarget.value;
    if (!target || !remark.value.trim()) {
      message.warning('请填写处置说明');
      return;
    }
    const current = operationGuard.next();
    handleApi.lock();
    try {
      await handleDatabaseAlarm({
        databaseId: props.databaseId,
        id: target.id,
        action: action.value,
        remark: remark.value.trim(),
      });
      if (!current.isCurrent()) return;
      await handleApi.close();
      message.success(action.value === 'HANDLE' ? '告警已处理' : '告警已忽略');
      emit('change');
      await load();
    } finally {
      handleApi.unlock();
    }
  },
  onOpenChange(open) {
    if (!open) {
      operationGuard.invalidate();
      actionTarget.value = undefined;
      remark.value = '';
    }
  },
});

const columns: VxeTableGridOptions<Row>['columns'] = [
  { field: 'id', title: '编号', width: 125 },
  {
    field: alarm.value ? 'ruleName' : 'taskName',
    title: '任务名称',
    minWidth: 190,
  },
  {
    field: 'description',
    title: '描述',
    minWidth: 280,
    showOverflow: 'tooltip',
  },
  ...(alarm.value
    ? [
        {
          field: 'threshold',
          title: '触发规则',
          minWidth: 180,
          slots: { default: 'trigger' },
        },
        {
          field: 'currentValue',
          title: '当前值',
          minWidth: 115,
          slots: { default: 'value' },
        },
        {
          field: 'level',
          title: '预警级别',
          width: 105,
          slots: { default: 'level' },
        },
      ]
    : [
        {
          field: 'sourceModule',
          title: '来源模块',
          width: 115,
          slots: { default: 'source' },
        },
        {
          field: 'outputRows',
          title: '数据输出量（条）',
          minWidth: 155,
          formatter: ({ cellValue }: { cellValue: unknown }) =>
            String(cellValue ?? '—'),
        },
        {
          field: 'inputRows',
          title: '数据输入量（条）',
          minWidth: 155,
          formatter: ({ cellValue }: { cellValue: unknown }) =>
            String(cellValue ?? '—'),
        },
      ]),
  { field: 'status', title: '状态', width: 105, slots: { default: 'status' } },
  {
    field: alarm.value ? 'lastTime' : 'createTime',
    title: alarm.value ? '预警时间' : '创建时间',
    minWidth: 175,
    formatter: ({ cellValue }) => formatDateTime(cellValue),
  },
  {
    title: '操作',
    width: alarm.value ? 205 : 90,
    fixed: 'right',
    showOverflow: false,
    slots: { default: 'actions' },
  },
];

const [Grid, gridApi] = useVbenVxeGrid<Row>({
  gridOptions: {
    columns,
    height: 'auto',
    minHeight: 320,
    rowConfig: { keyField: 'id', isHover: true },
    proxyConfig: { enabled: false },
    pagerConfig: { currentPage: 1, pageSize: 20, total: 0 },
    toolbarConfig: {
      refresh: true,
      refreshOptions: { queryMethod: load },
      custom: true,
    },
  },
  gridEvents: {
    pageChange: ({
      currentPage,
      pageSize,
    }: {
      currentPage: number;
      pageSize: number;
    }) => {
      page.pageNo = currentPage;
      page.pageSize = pageSize;
      void load();
    },
  },
});

async function load() {
  const current = listGuard.next();
  failed.value = false;
  gridApi.setLoading(true);
  try {
    const params = { ...page, databaseId: props.databaseId };
    const result = alarm.value
      ? await getDatabaseAlarmPage(params)
      : await getDatabaseMonitorLogPage(params);
    if (!current.isCurrent()) return;
    gridApi.setGridOptions({
      data: result.list,
      pagerConfig: {
        currentPage: page.pageNo,
        pageSize: page.pageSize,
        total: result.total,
      },
    });
  } catch {
    if (current.isCurrent()) {
      failed.value = true;
      gridApi.setGridOptions({ data: [], pagerConfig: { total: 0 } });
    }
  } finally {
    if (current.isCurrent()) gridApi.setLoading(false);
  }
}

async function showDetail(row: Row) {
  const current = detailGuard.next();
  detailFailed.value = false;
  selectedAlarm.value = undefined;
  selectedLog.value = undefined;
  detailApi.open();
  if (!alarm.value) {
    selectedLog.value = row as DatabaseMonitorApi.Log;
    return;
  }
  detailLoading.value = true;
  try {
    const result = await getDatabaseAlarm(props.databaseId, row.id);
    if (current.isCurrent()) selectedAlarm.value = result;
  } catch {
    if (current.isCurrent()) detailFailed.value = true;
  } finally {
    if (current.isCurrent()) detailLoading.value = false;
  }
}

function openHandle(row: Row, nextAction: 'HANDLE' | 'IGNORE') {
  actionTarget.value = row as DatabaseMonitorApi.Alarm;
  action.value = nextAction;
  remark.value = '';
  handleApi.open();
}

function invalidate() {
  listGuard.invalidate();
  detailGuard.invalidate();
  operationGuard.invalidate();
}

watch(
  () => [props.databaseId, props.mode, access.accessToken, access.refreshToken],
  () => {
    invalidate();
    detailApi.close();
    handleApi.close();
    page.pageNo = 1;
    gridApi.setGridOptions({
      data: [],
      pagerConfig: { currentPage: 1, total: 0 },
    });
    void load();
  },
  { flush: 'sync' },
);
onMounted(load);
onBeforeUnmount(invalidate);
</script>

<template>
  <div class="database-records-panel">
    <Alert
      v-if="failed"
      type="error"
      show-icon
      message="记录加载失败，请点击表格右上角刷新重试。"
      class="mb-3"
    />
    <Grid :table-title="alarm ? '告警记录列表' : '读写日志列表'">
      <template #source="{ row }">
        <Tag v-if="'sourceModule' in row" color="blue">
          {{
            row.sourceModule === 'ACCESS'
              ? '数据集成'
              : row.sourceModule === 'DEV'
                ? '数据开发'
                : row.sourceModule
          }}
        </Tag>
      </template>
      <template #trigger="{ row }">
        <span v-if="'metric' in row">
          {{ metricLabel(row.metric) }}：{{ triggerCondition(row) }}
        </span>
      </template>
      <template #value="{ row }">
        <span v-if="'metric' in row">{{
          metricValue(row.metric, row.currentValue)
        }}</span>
      </template>
      <template #level="{ row }">
        <Tag v-if="'level' in row" :color="LEVEL_COLORS[row.level]">
          {{ levelLabel(row.level) }}
        </Tag>
      </template>
      <template #status="{ row }">
        <Tag
          :color="
            alarm
              ? ALARM_STATUS[row.status as DatabaseMonitorApi.AlarmStatus]
                  ?.color
              : LOG_STATUS[row.status as DatabaseMonitorApi.LogStatus]?.color
          "
        >
          {{
            alarm
              ? (ALARM_STATUS[row.status as DatabaseMonitorApi.AlarmStatus]
                  ?.label ?? row.status)
              : (LOG_STATUS[row.status as DatabaseMonitorApi.LogStatus]
                  ?.label ?? row.status)
          }}
        </Tag>
      </template>
      <template #actions="{ row }">
        <TableAction
          :actions="[
            {
              label: '详情',
              type: 'link',
              icon: ACTION_ICON.VIEW,
              auth: ['dataforge:database:query'],
              onClick: () => showDetail(row),
            },
            {
              label: '处理',
              type: 'link',
              icon: ACTION_ICON.EDIT,
              auth: ['dataforge:database:update'],
              ifShow: alarm,
              disabled: row.status !== 'PENDING',
              onClick: () => openHandle(row, 'HANDLE'),
            },
            {
              icon: 'lucide:circle-slash',
              label: '忽略',
              type: 'link',
              auth: ['dataforge:database:update'],
              ifShow: alarm,
              disabled: row.status !== 'PENDING',
              onClick: () => openHandle(row, 'IGNORE'),
            },
          ]"
        />
      </template>
    </Grid>
    <DetailModal
      :title="alarm ? '告警记录详情' : '读写日志详情'"
      class="w-[760px]"
    >
      <div v-if="detailLoading" class="py-10 text-center text-muted-foreground">
        正在加载详情…
      </div>
      <Alert
        v-else-if="detailFailed"
        type="error"
        show-icon
        message="详情加载失败，请关闭后重试。"
      />
      <Descriptions v-else-if="selectedLog" bordered :column="2" size="small">
        <DescriptionsItem label="任务名称" :span="2">
          {{ selectedLog.taskName }}
        </DescriptionsItem>
        <DescriptionsItem label="执行编号">
          {{ selectedLog.recordId }}
        </DescriptionsItem>
        <DescriptionsItem label="来源模块">
          {{ selectedLog.sourceModule === 'ACCESS' ? '数据集成' : '数据开发' }}
        </DescriptionsItem>
        <DescriptionsItem label="描述" :span="2">
          {{ selectedLog.description || '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="输出量（条）">
          {{ selectedLog.outputRows ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="输入量（条）">
          {{ selectedLog.inputRows ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="状态">
          {{ LOG_STATUS[selectedLog.status]?.label ?? selectedLog.status }}
        </DescriptionsItem>
        <DescriptionsItem label="耗时（ms）">
          {{ selectedLog.durationMs ?? '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="开始时间">
          {{
            selectedLog.startTime == null
              ? '—'
              : formatDateTime(selectedLog.startTime)
          }}
        </DescriptionsItem>
        <DescriptionsItem label="结束时间">
          {{
            selectedLog.endTime == null
              ? '—'
              : formatDateTime(selectedLog.endTime)
          }}
        </DescriptionsItem>
      </Descriptions>
      <Descriptions v-else-if="selectedAlarm" bordered :column="2" size="small">
        <DescriptionsItem label="规则名称" :span="2">
          {{ selectedAlarm.ruleName }}
        </DescriptionsItem>
        <DescriptionsItem label="描述" :span="2">
          {{ selectedAlarm.description || '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="触发规则">
          {{ triggerCondition(selectedAlarm) }}
        </DescriptionsItem>
        <DescriptionsItem label="当前值">
          {{ metricValue(selectedAlarm.metric, selectedAlarm.currentValue) }}
        </DescriptionsItem>
        <DescriptionsItem label="告警级别">
          {{ levelLabel(selectedAlarm.level) }}
        </DescriptionsItem>
        <DescriptionsItem label="状态">
          {{
            ALARM_STATUS[selectedAlarm.status]?.label ?? selectedAlarm.status
          }}
        </DescriptionsItem>
        <DescriptionsItem label="首次告警">
          {{ formatDateTime(selectedAlarm.firstTime) }}
        </DescriptionsItem>
        <DescriptionsItem label="最近告警">
          {{ formatDateTime(selectedAlarm.lastTime) }}
        </DescriptionsItem>
        <DescriptionsItem label="累计次数">
          {{ selectedAlarm.occurrences }}
        </DescriptionsItem>
        <DescriptionsItem label="处理人">
          {{ selectedAlarm.handledBy || '—' }}
        </DescriptionsItem>
        <DescriptionsItem label="处理时间">
          {{
            selectedAlarm.handledTime == null
              ? '—'
              : formatDateTime(selectedAlarm.handledTime)
          }}
        </DescriptionsItem>
        <DescriptionsItem label="处置说明" :span="2">
          {{ selectedAlarm.handleRemark || '—' }}
        </DescriptionsItem>
      </Descriptions>
    </DetailModal>
    <HandleModal
      :title="action === 'HANDLE' ? '处理告警' : '忽略告警'"
      class="w-[560px]"
    >
      <p class="mb-4">{{ actionTarget?.ruleName }}</p>
      <label for="database-alarm-remark" class="mb-2 block">
        处置说明 <span class="text-red-500">*</span>
      </label>
      <Input.TextArea
        id="database-alarm-remark"
        v-model:value="remark"
        :maxlength="500"
        :rows="4"
        show-count
        placeholder="请填写处理结果或忽略原因"
      />
    </HandleModal>
  </div>
</template>
