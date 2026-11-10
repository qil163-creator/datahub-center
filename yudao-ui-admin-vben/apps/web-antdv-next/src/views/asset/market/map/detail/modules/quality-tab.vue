<script lang="ts" setup>
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { DataAssetApi } from '#/api/asset/dataasset';
import type { ImportedAssetApi } from '#/api/asset/dataasset/imported';
import type { AssetQualityTaskApi } from '#/api/asset/qualitytask';
import type { QualityReportApi } from '#/api/governance/quality/report';
import type { QualityTaskApi } from '#/api/governance/quality/task';

import { computed, nextTick, onBeforeUnmount, ref, toRaw, watch } from 'vue';

import { useAccess } from '@vben/access';
import { useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { EchartsUI, useEcharts } from '@vben/plugins/echarts';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Empty,
  message,
  Modal,
  Select,
  Spin,
  Table,
  Tag,
} from 'antdv-next';

import {
  deleteAssetQualityTask,
  executeAssetQualityTask,
  updateAssetQualityTask,
} from '#/api/asset/qualitytask';
import {
  getQualityReport,
  getQualityReportPage,
} from '#/api/governance/quality/report';
import {
  getQualityTask,
  getQualityTaskRuleCatalog,
} from '#/api/governance/quality/task';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';
import ProblemData from '#/views/governance/quality/issue-data/modules/problem-data.vue';
import {
  chartScore,
  dimensionLabel,
} from '#/views/governance/quality/report/report-presentation';
import RuleEditor from '#/views/governance/quality/task/modules/rule-editor.vue';

import LegacyTaskForm from './legacy-quality-task-form.vue';
import {
  qualityDimensions,
  qualityTrend,
  sourceDimension,
} from './quality-presentation';
import QualitySchedule from './quality-schedule.vue';
import SourceDialog from './quality-source-dialog.vue';
import QualityTaskForm from './quality-task-form.vue';

import '#/views/governance/quality/task/qdata-quality.css';

defineOptions({ name: 'AssetMarketMapQualityTab' });
const props = defineProps<{
  asset: DataAssetApi.DataAsset;
  assetId: DataAssetApi.Id;
  columns: DataAssetApi.MetadataColumn[];
  importedSnapshot?: ImportedAssetApi.Snapshot;
  tasks: AssetQualityTaskApi.QualityTask[];
}>();
const emit = defineEmits<{ refresh: [] }>();
const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const owner = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
    props.assetId,
  ]),
);
const signedIn = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId),
);
const manageable = computed(
  () =>
    signedIn.value &&
    !props.importedSnapshot &&
    props.asset.techAvailable !== 0 &&
    hasAccessByCodes(['asset:quality-task:manage']),
);
const canReport = computed(
  () =>
    signedIn.value && hasAccessByCodes(['governance:quality-audit-log:query']),
);
const canEngine = computed(
  () => signedIn.value && hasAccessByCodes(['governance:quality-task:query']),
);
const selectedId = ref<string>();
const task = computed(
  () =>
    props.tasks.find((row) => String(row.id) === selectedId.value) ??
    props.tasks[0],
);
const sourceTask = computed(() => props.importedSnapshot?.quality?.task);
const hasTask = computed(() => !!task.value || !!sourceTask.value);
const report = ref<QualityReportApi.Detail>();
const engine = ref<QualityTaskApi.Task>();
const catalog = ref<QualityTaskApi.CatalogRule[]>([]);
const loading = ref(false);
const failed = ref(false);
const busy = ref(false);
const days = ref<7 | 15 | 30>(7);
const guard = createLatestWins();
const mutation = createLatestWins();
const sourceDialog = ref<InstanceType<typeof SourceDialog>>();
const problem = ref<InstanceType<typeof ProblemData>>();
const editor = ref<InstanceType<typeof RuleEditor>>();
const trendRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(trendRef);
const dimensions = computed(() =>
  qualityDimensions(props.importedSnapshot, report.value),
);
const trend = computed(() =>
  qualityTrend(props.importedSnapshot, report.value),
);
const score = computed(() => {
  if (props.importedSnapshot)
    return trend.value.toReversed().find((row) => row.score !== null)?.score;
  return report.value?.instance.score ?? task.value?.qualityScore;
});
const lastRunTime = computed(
  () =>
    task.value?.lastFinishTime ??
    props.importedSnapshot?.quality?.statistics?.items?.[0]?.checkDate,
);
const sourceRules = computed(() =>
  (props.importedSnapshot?.quality?.evaluations ?? []).map((row) => {
    const result = props.importedSnapshot?.quality?.statistics?.items?.find(
      (item) => String(item.sourceEvaluateId) === String(row.sourceId),
    );
    return {
      key: String(row.sourceId),
      name: row.name,
      datasourceName: props.importedSnapshot?.sourceName,
      fields: row.columnName || '整表',
      dimension: sourceDimension(row.dimensionType),
      ruleName: row.ruleName || row.ruleCode,
      issueCount: result?.problemTotal,
      proportion: result?.proportion,
      evaluation: row,
    };
  }),
);
const liveRules = computed(() =>
  (report.value?.rules ?? []).map((row) => ({
    key: String(row.id),
    name: row.ruleName,
    datasourceName: row.datasourceName,
    fields: row.fieldLabels.join(' / ') || row.checkField || '整表',
    dimension: row.qualityDim,
    ruleName: row.ruleTypeName || row.ruleType,
    issueCount: row.failCount,
    proportion: row.problemRatio,
    report: row,
  })),
);
const rules = computed(() =>
  props.importedSnapshot ? sourceRules.value : liveRules.value,
);
const [TaskFormModal, taskFormApi] = useVbenModal({
  connectedComponent: QualityTaskForm,
  destroyOnClose: true,
});
const [LegacyFormModal, legacyFormApi] = useVbenModal({
  connectedComponent: LegacyTaskForm,
  destroyOnClose: true,
});
const [ScheduleModal, scheduleApi] = useVbenModal({
  connectedComponent: QualitySchedule,
  destroyOnClose: true,
});
function editTask() {
  if (!manageable.value) return;
  if (task.value?.rules.length && !task.value.evaluations?.length) {
    legacyFormApi
      .setData({
        assetId: props.assetId,
        columns: props.columns,
        task: task.value,
      })
      .open();
  } else taskFormApi.setData({ asset: props.asset, task: task.value }).open();
}
function createTask() {
  if (manageable.value) taskFormApi.setData({ asset: props.asset }).open();
}
async function renderTrend() {
  await nextTick();
  if (!signedIn.value) return;
  renderEcharts({
    animation: false,
    tooltip: { trigger: 'axis' },
    grid: { left: 45, right: 30, top: 30, bottom: 38 },
    xAxis: {
      type: 'category',
      data: trend.value.map((row) => row.date),
      axisLabel: { color: '#666', fontSize: 11 },
      axisLine: { lineStyle: { color: '#e4e7ed' } },
    },
    yAxis: {
      type: 'value',
      min: 0,
      max: 100,
      interval: 20,
      splitLine: { lineStyle: { color: '#eef0f4' } },
    },
    series: [
      {
        name: '质量趋势',
        type: 'line',
        showSymbol: true,
        symbolSize: 6,
        lineStyle: { color: '#526fff', width: 2 },
        itemStyle: { color: '#526fff' },
        areaStyle: { color: '#edf0ff' },
        data: trend.value.map((row) => chartScore(row.score)),
      },
    ],
  });
}
async function load() {
  const ticket = guard.next();
  report.value = undefined;
  engine.value = undefined;
  catalog.value = [];
  failed.value = false;
  problem.value?.close();
  if (!signedIn.value || props.importedSnapshot || !task.value?.qualityTaskId) {
    loading.value = false;
    await renderTrend();
    return;
  }
  const id = task.value.qualityTaskId;
  loading.value = true;
  const results = await Promise.allSettled([
    canReport.value
      ? getQualityReportPage({ taskId: id, pageNo: 1, pageSize: 1 }).then(
          async (page) =>
            page.list[0]?.id === undefined
              ? undefined
              : getQualityReport(page.list[0].id, days.value),
        )
      : Promise.resolve(undefined),
    canEngine.value ? getQualityTask(id) : Promise.resolve(undefined),
    canEngine.value ? getQualityTaskRuleCatalog() : Promise.resolve([]),
  ]);
  if (!ticket.isCurrent()) return;
  const [reportResult, engineResult, catalogResult] = results;
  if (reportResult.status === 'fulfilled') report.value = reportResult.value;
  if (engineResult.status === 'fulfilled') engine.value = engineResult.value;
  if (catalogResult.status === 'fulfilled') catalog.value = catalogResult.value;
  failed.value = results.some((result) => result.status === 'rejected');
  loading.value = false;
  await renderTrend();
}
async function execute() {
  if (!manageable.value || !task.value?.id || busy.value) return;
  const ticket = mutation.next();
  busy.value = true;
  try {
    await executeAssetQualityTask(task.value.id);
    if (!ticket.isCurrent()) return;
    message.success('已提交质量任务执行');
    emit('refresh');
  } finally {
    if (ticket.isCurrent()) busy.value = false;
  }
}
function toggleTask() {
  if (!manageable.value || !task.value?.id || busy.value) return;
  const currentOwner = owner.value;
  const data = structuredClone(toRaw(task.value));
  const enabled = data.enabled === 1 ? 0 : 1;
  Modal.confirm({
    title: enabled ? '启动任务' : '停止任务',
    content: `确认${enabled ? '启动' : '停止'}质量任务“${data.taskName}”？`,
    async onOk() {
      if (owner.value !== currentOwner || !manageable.value) return;
      try {
        await updateAssetQualityTask({ ...data, enabled });
      } finally {
        if (owner.value === currentOwner) emit('refresh');
      }
    },
  });
}
function deleteTask() {
  if (!manageable.value || !task.value?.id) return;
  const currentOwner = owner.value;
  const id = task.value.id;
  Modal.confirm({
    title: '删除质量任务',
    content: `确认删除“${task.value.taskName}”及全部评测规则？`,
    okButtonProps: { danger: true },
    async onOk() {
      if (owner.value !== currentOwner || !manageable.value) return;
      await deleteAssetQualityTask(id);
      if (owner.value === currentOwner) emit('refresh');
    },
  });
}
function openRule(row: {
  evaluation?: ImportedAssetApi.Evaluation;
  report?: QualityReportApi.Rule;
}) {
  if (row.evaluation) {
    sourceDialog.value?.open('rule', row.evaluation);
    return;
  }
  const evaluation = engine.value?.evaluations?.find(
    (value) => String(value.id) === String(row.report?.evaluationId),
  );
  if (evaluation && canEngine.value)
    editor.value?.open(evaluation, undefined, true);
}
function openProblem(row?: QualityReportApi.Rule) {
  if (
    row &&
    signedIn.value &&
    hasAccessByCodes(['governance:quality-issue-data:query'])
  )
    problem.value?.open(row);
}
function closeChildren() {
  sourceDialog.value?.close();
  problem.value?.close();
  editor.value?.close();
  taskFormApi.close();
  legacyFormApi.close();
  scheduleApi.close();
}
watch(
  owner,
  () => {
    guard.invalidate();
    mutation.invalidate();
    closeChildren();
    busy.value = false;
  },
  { flush: 'sync' },
);
watch(
  () => [
    owner.value,
    props.tasks,
    props.importedSnapshot,
    selectedId.value,
    days.value,
  ],
  load,
  { immediate: true },
);
onBeforeUnmount(() => {
  guard.invalidate();
  mutation.invalidate();
  closeChildren();
});
const dimensionColumns = [
  { title: '质量维度', key: 'dimension', width: 110 },
  { title: '规则数', dataIndex: 'ruleCount', key: 'ruleCount', width: 80 },
  { title: '问题数量', dataIndex: 'issueCount', key: 'issueCount' },
  { title: '数据量占比', key: 'proportion' },
  { title: '较上期变化', key: 'change' },
];
const ruleColumns = [
  { title: '评测名称', dataIndex: 'name', key: 'name', width: 220 },
  {
    title: '数据库名称',
    dataIndex: 'datasourceName',
    key: 'datasourceName',
    width: 210,
  },
  { title: '字段名/中文名', dataIndex: 'fields', key: 'fields', width: 240 },
  { title: '质量维度', key: 'dimension', width: 130 },
  { title: '稽查名称', dataIndex: 'ruleName', key: 'ruleName', width: 220 },
  { title: '问题数据量占比', key: 'proportion', width: 180 },
  { title: '操作', key: 'actions', width: 180 },
];
</script>

<template>
  <div class="quality-tab">
    <TaskFormModal @success="emit('refresh')" /><LegacyFormModal
      @success="emit('refresh')"
    /><ScheduleModal @refresh="emit('refresh')" />
    <SourceDialog ref="sourceDialog" :snapshot="importedSnapshot" />
    <RuleEditor
      ref="editor"
      :catalog="catalog"
      :objects="engine?.objects ?? []"
      :allow-execution="canEngine"
    />
    <ProblemData ref="problem" />
    <Empty
      v-if="!hasTask"
      class="quality-tab__empty"
      image="/images/quality-reference/empty.png"
      description="暂无记录"
    >
      <Button v-if="manageable" type="primary" @click="createTask">
        新增
      </Button>
    </Empty>
    <template v-else>
      <div class="quality-tab__toolbar">
        <strong>质量评估任务：</strong>
        <Select
          v-if="tasks.length > 1"
          v-model:value="selectedId"
          :options="
            tasks.map((row) => ({ label: row.taskName, value: String(row.id) }))
          "
          class="quality-tab__selector"
        />
        <span v-else>{{ task?.taskName || sourceTask?.taskName }}</span>
        <span class="quality-tab__last">上一次评估时间：{{ formatDateTime(lastRunTime) || '-' }}</span>
        <div class="quality-tab__buttons" v-if="importedSnapshot">
          <Button type="primary" @click="sourceDialog?.open('task')">
            质量任务详情
</Button><Button type="primary" @click="sourceDialog?.open('schedule')">
            调度配置
          </Button>
        </div>
        <div class="quality-tab__buttons" v-else-if="manageable">
          <Button type="primary" @click="editTask">修改资产质量任务</Button><Button type="primary" @click="scheduleApi.setData({ task }).open()">
            调度配置
</Button><Button type="primary" :loading="busy" @click="execute">
            执行一次
</Button><Button type="primary" @click="toggleTask">
            {{ task?.enabled === 1 ? '停止任务' : '启动任务' }}
</Button><Button type="link" danger @click="deleteTask">删除</Button><Button type="link" @click="createTask">新增</Button>
        </div>
      </div>
      <Alert
        v-if="importedSnapshot"
        type="info"
        message="当前展示来源质量历史快照。"
        class="quality-tab__notice"
      />
      <Alert
        v-if="task?.remoteError"
        type="warning"
        :message="`质量定义尚未同步：${task.remoteError}`"
      />
      <Alert
        v-else-if="task?.pendingSync"
        type="warning"
        message="质量定义待同步，可执行原任务重试。"
      />
      <Alert
        v-if="task?.executionUnknown"
        type="warning"
        message="执行结果待核对；重试原任务会核对同一次运行。"
      />

      <Spin :spinning="loading">
        <div class="quality-tab__dashboard">
          <section class="quality-tab__panel">
            <h3>数据质量维度统计</h3>
            <p class="quality-tab__score">
              整体数据质量评分 <strong>{{ score ?? '-' }}</strong>
            </p>
            <Table
              :columns="dimensionColumns"
              :data-source="dimensions"
              :pagination="false"
              row-key="value"
            >
              <template #bodyCell="{ column, record, text }">
                <template v-if="column.key === 'dimension'">
                  <Tag color="blue" :bordered="false">
                    {{ record.label }}
                  </Tag>
</template><template v-else-if="column.key === 'proportion'">
                  {{
                    record.proportion == null ? '-' : `${record.proportion}%`
                  }}
</template><template v-else-if="column.key === 'change'">
                  {{ record.change ?? '-' }}
</template><template v-else>{{ text ?? '-' }}</template>
              </template>
            </Table>
          </section>
          <section class="quality-tab__panel">
            <h3>质量趋势</h3>
            <Select
              v-if="!importedSnapshot && canReport"
              v-model:value="days"
              class="quality-tab__days"
              :options="[
                { label: '近7天', value: 7 },
                { label: '近15天', value: 15 },
                { label: '近30天', value: 30 },
              ]"
            /><EchartsUI
              v-if="trend.length"
              ref="trendRef"
              height="300px"
            /><Empty v-else description="暂无评估趋势" />
          </section>
        </div>
        <section class="quality-tab__panel quality-tab__rules">
          <h3>规则列表</h3>
          <Table
            :columns="ruleColumns"
            :data-source="rules"
            :pagination="false"
            :scroll="{ x: 1200 }"
            row-key="key"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'dimension'">
                <Tag color="blue" :bordered="false">
                  {{ dimensionLabel(record.dimension) }}
                </Tag>
</template><template v-else-if="column.key === 'proportion'">
                {{ record.issueCount ?? '-' }} 条
                <span>{{
                  record.proportion == null ? '-' : `${record.proportion}%`
                }}</span>
</template><template v-else-if="column.key === 'actions'">
                <Button
                  v-if="
                    importedSnapshot ||
                    (engine?.evaluations?.length && canEngine)
                  "
                  type="link"
                  size="small"
                  @click="openRule(record)"
                >
                  <template #icon><IconifyIcon icon="lucide:eye" /></template>查看
</Button><Button
                  v-if="
                    !importedSnapshot &&
                    hasAccessByCodes(['governance:quality-issue-data:query'])
                  "
                  type="link"
                  size="small"
                  @click="openProblem(record.report)"
                >
                  查看问题数据
                </Button>
              </template>
            </template>
          </Table>
        </section>
      </Spin>
    </template>
  </div>
</template>

<style scoped>
.quality-tab {
  min-height: 500px;
  padding: 4px 8px;
  color: hsl(var(--foreground));
}

.quality-tab__empty {
  padding-top: 60px;
}

.quality-tab__empty :deep(.ant-empty-image) {
  height: 150px;
}

.quality-tab__toolbar {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  min-height: 48px;
}

.quality-tab__toolbar strong {
  color: hsl(var(--foreground));
}

.quality-tab__selector {
  min-width: 240px;
}

.quality-tab__last {
  margin-left: 25px;
}

.quality-tab__buttons {
  display: flex;
  gap: 12px;
  margin-left: auto;
}

.quality-tab__notice {
  margin-bottom: 10px;
}

.quality-tab__dashboard {
  display: grid;
  grid-template-columns: 1fr 1fr;
  gap: 16px;
  padding: 10px;
  background: #f4f5f7;
}

.quality-tab__panel {
  position: relative;
  min-width: 0;
  padding: 12px 16px 20px;
  background: hsl(var(--background));
}

.quality-tab__panel h3 {
  position: relative;
  padding-left: 12px;
  margin: 0 0 20px;
  font-size: 14px;
  font-weight: 400;
  color: hsl(var(--foreground));
}

.quality-tab__panel h3::before {
  position: absolute;
  top: 4px;
  left: 0;
  width: 5px;
  height: 13px;
  content: '';
  background: hsl(var(--primary));
  border-radius: 2px;
}

.quality-tab__score {
  margin: 16px 0;
  text-align: center;
}

.quality-tab__score strong {
  padding-left: 10px;
  font-size: 24px;
  font-weight: 400;
  color: #67c23a;
}

.quality-tab__days {
  position: absolute;
  top: 8px;
  right: 16px;
  width: 100px;
}

.quality-tab__rules {
  margin-top: 16px;
  border: 10px solid #f4f5f7;
}

@media (width <= 1000px) {
  .quality-tab__dashboard {
    grid-template-columns: 1fr;
  }

  .quality-tab__last {
    margin-left: 0;
  }

  .quality-tab__buttons {
    flex-wrap: wrap;
    margin-left: 0;
  }
}
</style>

<style scoped src="../../../../table-font.css"></style>
