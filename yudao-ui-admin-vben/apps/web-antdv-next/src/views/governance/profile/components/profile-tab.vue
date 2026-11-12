<script setup lang="ts">
import type { TablePaginationConfig } from 'antdv-next';

import type { GovernanceProfileApi as Api } from '#/api/governance/profile';

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';

import {
  Alert,
  Button,
  Card,
  Empty,
  message,
  Spin,
  Table,
  Tag,
  Tooltip,
} from 'antdv-next';

import {
  createProfileRun,
  getLatestProfile,
  getProfileRunPage,
} from '#/api/governance/profile';
import { useObjectRequest } from '#/hooks/use-object-request';
import { metadataTime } from '#/views/governance/metadata-common/helpers';
import {
  setRulePrefill,
  suggestionToRule,
} from '#/views/governance/quality/rule/prefill';

import {
  COLUMN_KIND_LABELS,
  display,
  formatSuggestionParams,
  isActiveRun,
  maskReasonLabel,
  PROFILE_POLL_INTERVAL,
  QUALITY_DIM_LABELS,
  ratioToPercent,
  ratioWidth,
  RULE_TYPE_LABELS,
  RUN_STATUS_META,
} from '../helpers';

defineOptions({ name: 'GovernanceProfileTab' });

const props = defineProps<{ metaTableId?: Api.Id; readonly?: boolean }>();

const { hasAccessByCodes } = useAccess();
// 已定稿视图只读：可查看探查结果，不在此发起探查。
const canRun = computed(
  () => !props.readonly && hasAccessByCodes(['governance:profile:run']),
);

const latest = ref<Api.Latest>();
const loading = ref(false);
const error = ref('');
const starting = ref(false);
const history = ref<Api.Run[]>([]);
const historyTotal = ref(0);
const historyPage = ref(1);
const historyPageSize = ref(10);
const historyLoading = ref(false);

let timer: ReturnType<typeof setTimeout> | undefined;
let latestController: AbortController | undefined;
let historyController: AbortController | undefined;

const tableKey = () => String(props.metaTableId ?? '');
const latestRequest = useObjectRequest(tableKey, reset);
const historyRequest = useObjectRequest(tableKey);

const latestRun = computed(() => latest.value?.latestRun ?? undefined);
const resultRun = computed(() => latest.value?.resultRun ?? undefined);
const polling = computed(() => isActiveRun(latestRun.value));

const historyColumns = [
  { title: '编号', dataIndex: 'id', width: 90 },
  { title: '状态', key: 'status', width: 110 },
  { title: '抽样行数', dataIndex: 'sampleRows', width: 120 },
  { title: '总行数', dataIndex: 'totalRows', width: 120 },
  { title: '字段数', dataIndex: 'columnCount', width: 90 },
  { title: '执行人', key: 'executor', width: 140 },
  { title: '开始时间', key: 'startTime', width: 170 },
  { title: '结束时间', key: 'endTime', width: 170 },
  { title: '失败原因', dataIndex: 'failReason', ellipsis: true },
];
const suggestionColumns = [
  { title: '字段', dataIndex: 'columnName', width: 160 },
  { title: '建议规则', key: 'ruleType', width: 130 },
  { title: '质量维度', key: 'qualityDim', width: 100 },
  { title: '推荐理由', dataIndex: 'reason', width: 260 },
  { title: '建议参数', key: 'params', ellipsis: true },
  { title: '操作', key: 'actions', width: 110 },
];
const historyPagination = computed<TablePaginationConfig>(() => ({
  current: historyPage.value,
  pageSize: historyPageSize.value,
  total: historyTotal.value,
  showSizeChanger: false,
  onChange: (page: number) => {
    historyPage.value = page;
    void loadHistory();
  },
}));

function reset() {
  stopPolling();
  latestController?.abort();
  historyController?.abort();
  latest.value = undefined;
  error.value = '';
  history.value = [];
  historyTotal.value = 0;
  historyPage.value = 1;
}

function stopPolling() {
  if (timer) clearTimeout(timer);
  timer = undefined;
}

async function loadLatest(silent = false) {
  const metaTableId = props.metaTableId;
  if (metaTableId === undefined || metaTableId === null || metaTableId === '') return;
  stopPolling();
  latestController?.abort();
  const controller = new AbortController();
  latestController = controller;
  const current = latestRequest.begin();
  const wasActive = polling.value;
  if (!silent) loading.value = true;
  try {
    const result = await getLatestProfile(metaTableId, controller.signal);
    if (!current()) return;
    latest.value = result;
    error.value = '';
    if (isActiveRun(result.latestRun)) {
      timer = setTimeout(() => void loadLatest(true), PROFILE_POLL_INTERVAL);
    } else if (wasActive) {
      void loadHistory();
    }
  } catch {
    if (current() && !controller.signal.aborted) {
      error.value = '探查结果加载失败，请稍后重试';
    }
  } finally {
    if (current()) loading.value = false;
  }
}

async function loadHistory() {
  const metaTableId = props.metaTableId;
  if (metaTableId === undefined || metaTableId === null || metaTableId === '') return;
  historyController?.abort();
  const controller = new AbortController();
  historyController = controller;
  const current = historyRequest.begin();
  historyLoading.value = true;
  try {
    const page = await getProfileRunPage(
      { metaTableId, pageNo: historyPage.value, pageSize: historyPageSize.value },
      controller.signal,
    );
    if (!current()) return;
    history.value = page.list;
    historyTotal.value = page.total;
  } catch {
    // 记录列表失败不影响结果展示；全局请求错误已提示
  } finally {
    if (current()) historyLoading.value = false;
  }
}

async function startRun() {
  const metaTableId = props.metaTableId;
  if (metaTableId === undefined || metaTableId === null || metaTableId === '' || starting.value) return;
  starting.value = true;
  try {
    await createProfileRun(metaTableId);
    message.success('已发起数据探查');
    historyPage.value = 1;
    await Promise.all([loadLatest(true), loadHistory()]);
  } finally {
    starting.value = false;
  }
}

const router = useRouter();
const canCreateRule = computed(() => hasAccessByCodes(['governance:quality-rule:create']));

function applySuggestion(suggestion: Api.RuleSuggestion) {
  setRulePrefill(suggestionToRule(suggestion));
  void router.push({ name: 'GovernanceQualityRule' });
}

async function copyParams(suggestion: Api.RuleSuggestion) {
  try {
    await navigator.clipboard.writeText(formatSuggestionParams(suggestion.params));
    message.success('已复制建议参数');
  } catch {
    message.warning('复制失败，请手动选择参数文本');
  }
}

function runTime(value?: number) {
  return value ? metadataTime(value, 'YYYY-MM-DD HH:mm:ss') : '-';
}

function rowKeyOf(record: Api.RuleSuggestion) {
  return `${record.columnName}/${record.ruleType}`;
}

watch(
  () => props.metaTableId,
  () => {
    void loadLatest();
    void loadHistory();
  },
  { immediate: true },
);

onBeforeUnmount(() => {
  stopPolling();
  latestController?.abort();
  historyController?.abort();
});
</script>

<template>
  <div class="profile-tab">
    <Spin :spinning="loading">
      <Alert v-if="error" type="error" show-icon :message="error" class="profile-gap" />
      <div class="profile-toolbar">
        <div class="profile-toolbar__status">
          <template v-if="latestRun">
            <Tag :color="RUN_STATUS_META[latestRun.status].color">
              {{ RUN_STATUS_META[latestRun.status].label }}
            </Tag>
            <span>最近一次：{{ runTime(latestRun.createTime) }}</span>
            <span v-if="latestRun.executorName">· {{ latestRun.executorName }}</span>
            <span v-if="polling" class="profile-muted">· 正在执行，结果自动刷新</span>
          </template>
          <span v-else class="profile-muted">尚未探查过这张表</span>
        </div>
        <Button
          v-if="canRun"
          type="primary"
          :loading="starting"
          :disabled="polling"
          @click="startRun"
        >
          发起探查
        </Button>
      </div>
      <Alert
        v-if="latestRun?.status === 'FAILED'"
        type="warning"
        show-icon
        class="profile-gap"
        :message="`最近一次探查失败：${latestRun.failReason || '未知原因'}`"
      />

      <template v-if="resultRun">
        <div class="profile-summary profile-gap">
          <span>抽样 {{ display(resultRun.sampleRows) }} 行（上限 {{ display(resultRun.sampleLimit) }}）</span>
          <span>全表 {{ resultRun.totalRows === undefined || resultRun.totalRows === null ? '未知' : resultRun.totalRows }} 行</span>
          <span>字段 {{ display(resultRun.columnCount) }} 个</span>
          <span>完成于 {{ runTime(resultRun.endTime) }}</span>
          <span class="profile-muted">指标基于抽样，仅供参考</span>
        </div>

        <h4 class="profile-heading">字段指标</h4>
        <div class="profile-cards">
          <Card
            v-for="column in latest?.columns ?? []"
            :key="String(column.id)"
            size="small"
            class="profile-card"
          >
            <template #title>
              <span class="profile-card__name">{{ column.columnName }}</span>
              <Tag class="profile-card__kind">{{ COLUMN_KIND_LABELS[column.columnKind] }}</Tag>
              <Tooltip v-if="column.valueMasked" :title="maskReasonLabel(column.maskReason)">
                <Tag color="orange">已脱敏</Tag>
              </Tooltip>
            </template>
            <template #extra>
              <span class="profile-muted">{{ column.dataType || '-' }}</span>
            </template>
            <dl class="profile-metrics">
              <dt>空值率</dt>
              <dd>{{ ratioToPercent(column.nullRate) }}（{{ column.nullCount }}）</dd>
              <dt>唯一率</dt>
              <dd>{{ ratioToPercent(column.uniqueRate) }}（{{ display(column.distinctCount) }}）</dd>
              <template v-if="column.columnKind !== 'OTHER' && column.columnKind !== 'BOOLEAN'">
                <dt>最小值</dt>
                <dd :title="column.minValue ?? ''">{{ column.valueMasked ? (column.minValue || '已脱敏') : display(column.minValue) }}</dd>
                <dt>最大值</dt>
                <dd :title="column.maxValue ?? ''">{{ column.valueMasked ? (column.maxValue || '已脱敏') : display(column.maxValue) }}</dd>
              </template>
              <template v-if="column.columnKind === 'TEXT'">
                <dt>长度</dt>
                <dd>{{ display(column.minLength) }} ~ {{ display(column.maxLength) }}，均 {{ display(column.avgLength) }}</dd>
              </template>
              <template v-if="column.columnKind === 'NUMERIC'">
                <dt>平均值</dt>
                <dd>{{ display(column.avgValue) }}</dd>
              </template>
            </dl>
            <div v-if="column.topValues.length > 0" class="profile-top">
              <div
                v-for="item in column.topValues"
                :key="item.rankNo"
                class="profile-top__row"
              >
                <Tooltip :title="item.masked ? maskReasonLabel(column.maskReason) : item.value">
                  <span class="profile-top__label" :class="{ 'profile-muted': item.masked }">
                    {{ item.masked ? (item.value || `取值 #${item.rankNo}（已脱敏）`) : item.value }}
                  </span>
                </Tooltip>
                <span class="profile-top__bar">
                  <span class="profile-top__fill" :style="{ width: ratioWidth(item.ratio) }"></span>
                </span>
                <span class="profile-top__count">{{ item.count }} · {{ ratioToPercent(item.ratio, 1) }}</span>
              </div>
            </div>
          </Card>
        </div>

        <h4 class="profile-heading">规则推荐</h4>
        <Alert
          type="info"
          show-icon
          class="profile-gap"
          message="推荐由探查结果直接映射，不会自动创建规则。点击「带入质量规则」会打开预填的新增表单，确认后才保存。"
        />
        <Table
          :columns="suggestionColumns"
          :data-source="latest?.suggestions ?? []"
          :row-key="rowKeyOf"
          size="small"
          :pagination="false"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'ruleType'">
              {{ RULE_TYPE_LABELS[record.ruleType as Api.RuleType] ?? record.ruleType }}
            </template>
            <template v-else-if="column.key === 'qualityDim'">
              {{ QUALITY_DIM_LABELS[record.qualityDim] ?? record.qualityDim }}
            </template>
            <template v-else-if="column.key === 'params'">
              <code class="profile-code">{{ formatSuggestionParams(record.params) }}</code>
            </template>
            <template v-else-if="column.key === 'actions'">
              <Button
                v-if="canCreateRule"
                type="link"
                size="small"
                @click="applySuggestion(record as Api.RuleSuggestion)"
              >
                带入质量规则
              </Button>
              <Button type="link" size="small" @click="copyParams(record as Api.RuleSuggestion)">
                复制参数
              </Button>
            </template>
          </template>
        </Table>
      </template>
      <Empty
        v-else-if="!loading && !polling"
        class="profile-gap"
        description="暂无探查结果"
      />
    </Spin>

    <h4 class="profile-heading">探查记录</h4>
    <Table
      :columns="historyColumns"
      :data-source="history"
      :loading="historyLoading"
      :row-key="(record: Api.Run) => String(record.id)"
      size="small"
      :pagination="historyPagination"
    >
      <template #bodyCell="{ column, record, text }">
        <template v-if="column.key === 'status'">
          <Tag :color="RUN_STATUS_META[record.status as Api.RunStatus]?.color">
            {{ RUN_STATUS_META[record.status as Api.RunStatus]?.label ?? record.status }}
          </Tag>
        </template>
        <template v-else-if="column.key === 'executor'">
          {{ record.executorName || display(record.executorUserId) }}
        </template>
        <template v-else-if="column.key === 'startTime'">
          {{ runTime(record.startTime) }}
        </template>
        <template v-else-if="column.key === 'endTime'">
          {{ runTime(record.endTime) }}
        </template>
        <template v-else>{{ display(text) }}</template>
      </template>
    </Table>
  </div>
</template>

<style scoped>
.profile-tab {
  padding: 4px 0 12px;
}

.profile-gap {
  margin-bottom: 12px;
}

.profile-toolbar {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 12px;
}

.profile-toolbar__status,
.profile-summary {
  display: flex;
  flex-wrap: wrap;
  gap: 8px 16px;
  align-items: center;
}

.profile-muted {
  color: var(--ant-color-text-tertiary, #999);
}

.profile-heading {
  margin: 16px 0 8px;
  font-weight: 600;
}

.profile-cards {
  display: grid;
  grid-template-columns: repeat(auto-fill, minmax(300px, 1fr));
  gap: 12px;
}

.profile-card__name {
  margin-right: 8px;
  font-weight: 600;
}

.profile-metrics {
  display: grid;
  grid-template-columns: 64px 1fr;
  gap: 4px 8px;
  margin: 0;
}

.profile-metrics dt {
  color: var(--ant-color-text-tertiary, #999);
}

.profile-metrics dd {
  margin: 0;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-top {
  display: flex;
  flex-direction: column;
  gap: 4px;
  margin-top: 10px;
}

.profile-top__row {
  display: grid;
  grid-template-columns: 96px 1fr 88px;
  gap: 8px;
  align-items: center;
  font-size: 12px;
}

.profile-top__label {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.profile-top__bar {
  height: 8px;
  overflow: hidden;
  background: var(--ant-color-fill-secondary, #f0f0f0);
  border-radius: 4px;
}

.profile-top__fill {
  display: block;
  height: 100%;
  background: var(--ant-color-primary, #1677ff);
}

.profile-top__count {
  text-align: right;
}

.profile-code {
  font-size: 12px;
  word-break: break-all;
}
</style>
