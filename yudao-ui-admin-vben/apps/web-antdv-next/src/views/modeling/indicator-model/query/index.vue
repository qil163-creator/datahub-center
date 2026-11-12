<script setup lang="ts">
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { QueryFormState } from './state';

import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { computed, nextTick, onMounted, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { EchartsUI, useEcharts } from '@vben/plugins/echarts';
import { useAccessStore, useUserStore } from '@vben/stores';

import {
  Alert,
  Button,
  Card,
  Col,
  Empty,
  Form,
  FormItem,
  Input,
  InputNumber,
  DateRangePicker as RangePicker,
  Row,
  Select,
  Spin,
  Table,
  Tag,
} from 'antdv-next';

import {
  getActiveIndicatorOptions,
  getIndicatorQueryMeta,
  queryIndicatorData,
} from '#/api/modeling/indicator-model';
import {
  indexMaskedColumns,
  renderMaskedColumnTitle,
} from '#/components/masked-column';
import { useObjectRequest } from '#/hooks/use-object-request';

import { options, PERIOD_LABELS, TYPE_LABELS } from '../data';
import {
  buildQueryRequest,
  caliberSummary,
  chartModel,
  QUERY_DEFAULT_LIMIT,
  QUERY_MAX_DIMENSIONS,
  QUERY_MAX_FILTERS,
  QUERY_MAX_INDICATORS,
  QUERY_MAX_LIMIT,
  rowKey,
} from './state';

defineOptions({ name: 'ModelingIndicatorQuery' });

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const scope = () =>
  JSON.stringify([user.userInfo?.userId, user.userRoles, access.accessCodes]);
const canQuery = computed(() =>
  hasAccessByCodes(['modeling:indicator-model:data-query']),
);

const form = ref<QueryFormState>({
  indicatorIds: [],
  dimensions: [],
  filters: [],
  limit: QUERY_DEFAULT_LIMIT,
});
const indicators = ref<IndicatorModelApi.Option[]>([]);
const optionsLoading = ref(false);
const optionsError = ref('');
const meta = ref<IndicatorModelApi.QueryMeta>();
const metaLoading = ref(false);
const metaError = ref('');
const result = ref<IndicatorModelApi.QueryResult>();
const querying = ref(false);
const queryError = ref('');

const optionGuard = useObjectRequest(scope, () => {
  indicators.value = [];
  optionsLoading.value = false;
});
const metaGuard = useObjectRequest(scope, () => {
  meta.value = undefined;
  metaLoading.value = false;
});
const queryGuard = useObjectRequest(scope, () => {
  result.value = undefined;
  querying.value = false;
});

const chartRef = ref<EchartsUIType>();
const { renderEcharts } = useEcharts(chartRef);

const indicatorOptions = computed(() =>
  indicators.value.map((item) => ({
    value: String(item.id),
    label: `[${TYPE_LABELS[item.indicatorType] ?? item.indicatorType}] ${item.name} (${item.code})${item.timePeriod ? ` · ${PERIOD_LABELS[item.timePeriod]}` : ''}`,
  })),
);
const dimensionOptions = computed(() =>
  (meta.value?.dimensions ?? []).map((field) => ({
    value: field.fieldUid,
    label: `${field.cnName || field.fieldName} (${field.fieldName})`,
  })),
);
const grainOptions = computed(() => {
  const periods = meta.value?.derivedPeriods ?? [];
  const all = options(PERIOD_LABELS);
  return periods.length > 0
    ? all.filter((item) =>
        periods.includes(item.value as IndicatorModelApi.Period),
      )
    : all;
});
const timeSupported = computed(() => meta.value?.timeSupported ?? false);
const masked = computed(() => indexMaskedColumns(result.value?.maskedColumns));
const columns = computed(() =>
  (result.value?.columns ?? []).map((column) => ({
    key: column.key,
    dataIndex: column.key,
    title: renderMaskedColumnTitle(
      column.unit ? `${column.title}（${column.unit}）` : column.title,
      masked.value.get(column.key),
    ),
    align: column.kind === 'INDICATOR' ? ('right' as const) : ('left' as const),
    customRender: ({ text }: { text: null | string }) => text ?? '-',
  })),
);
const chart = computed(() => chartModel(result.value));

async function loadIndicators() {
  const current = optionGuard.begin();
  optionsLoading.value = true;
  optionsError.value = '';
  try {
    const list = await getActiveIndicatorOptions();
    if (current()) indicators.value = list;
  } catch {
    if (current()) optionsError.value = '指标选项加载失败，请重试。';
  } finally {
    if (current()) optionsLoading.value = false;
  }
}

/** 指标变化后刷新共同维度；旧响应不能覆盖新选择。 */
async function loadMeta() {
  const current = metaGuard.begin();
  meta.value = undefined;
  metaError.value = '';
  const ids = [...form.value.indicatorIds];
  if (ids.length === 0) {
    metaLoading.value = false;
    return;
  }
  metaLoading.value = true;
  try {
    const value = await getIndicatorQueryMeta(ids);
    if (!current()) return;
    meta.value = value;
    const allowed = new Set(value.dimensions.map((field) => field.fieldUid));
    form.value.dimensions = form.value.dimensions.filter((uid) =>
      allowed.has(uid),
    );
    form.value.filters = form.value.filters.filter(
      (item) => !item.fieldUid || allowed.has(item.fieldUid),
    );
    if (!value.timeSupported) {
      form.value.range = undefined;
      form.value.timeGrain = undefined;
    } else if (
      form.value.timeGrain &&
      value.derivedPeriods.length > 0 &&
      !value.derivedPeriods.includes(form.value.timeGrain)
    ) {
      form.value.timeGrain = value.derivedPeriods[0];
    } else if (!form.value.timeGrain && value.derivedPeriods.length === 1) {
      form.value.timeGrain = value.derivedPeriods[0];
    }
  } catch {
    if (current())
      metaError.value =
        '所选指标暂不可查询（来源未物化或定义不可用），请调整指标后重试。';
  } finally {
    if (current()) metaLoading.value = false;
  }
}

watch(
  () => form.value.indicatorIds.join(','),
  () => {
    queryGuard.begin();
    querying.value = false;
    result.value = undefined;
    queryError.value = '';
    void loadMeta();
  },
);

async function runQuery() {
  if (form.value.indicatorIds.length === 0 || !meta.value) return;
  const current = queryGuard.begin();
  querying.value = true;
  queryError.value = '';
  try {
    const value = await queryIndicatorData(buildQueryRequest(form.value));
    if (!current()) return;
    result.value = value;
    await nextTick();
    if (current()) renderChart();
  } catch {
    if (current()) {
      result.value = undefined;
      queryError.value = '查询失败，请根据提示调整条件后重试。';
    }
  } finally {
    if (current()) querying.value = false;
  }
}

function renderChart() {
  const model = chart.value;
  if (!model) return;
  void renderEcharts({
    tooltip: { trigger: 'axis' },
    legend: { type: 'scroll', top: 0 },
    grid: { left: 48, right: 24, top: 40, bottom: 32, containLabel: true },
    xAxis: { type: 'category', data: model.categories },
    yAxis: { type: 'value' },
    series: model.series.map((item) => ({
      name: item.name,
      type: 'line',
      connectNulls: false,
      showSymbol: model.categories.length <= 60,
      data: item.data,
    })),
  } as any);
}

function addFilter() {
  if (form.value.filters.length >= QUERY_MAX_FILTERS) return;
  form.value.filters.push({ fieldUid: undefined, value: '' });
}
function removeFilter(index: number) {
  form.value.filters.splice(index, 1);
}
function resetForm() {
  form.value = {
    indicatorIds: [],
    dimensions: [],
    filters: [],
    limit: QUERY_DEFAULT_LIMIT,
  };
}

onMounted(() => {
  if (canQuery.value) void loadIndicators();
});
watch(canQuery, (value) => {
  if (value) void loadIndicators();
});
</script>

<template>
  <Page auto-content-height>
    <Alert
      v-if="!canQuery"
      type="warning"
      show-icon
      message="没有指标数据查询权限（modeling:indicator-model:data-query）。"
    />
    <template v-else>
      <Card class="mb-3" size="small" title="查询条件">
        <Alert
          v-if="optionsError"
          class="mb-3"
          type="error"
          :message="optionsError"
        >
          <template #action>
            <Button size="small" @click="loadIndicators">重试</Button>
          </template>
        </Alert>
        <Form layout="vertical">
          <Row :gutter="16">
            <Col :xs="24" :lg="12">
              <FormItem label="指标" required>
                <Select
                  v-model:value="form.indicatorIds"
                  mode="multiple"
                  :options="indicatorOptions"
                  :loading="optionsLoading"
                  :max-count="QUERY_MAX_INDICATORS"
                  show-search
                  option-filter-prop="label"
                  :placeholder="`选择启用指标，最多 ${QUERY_MAX_INDICATORS} 个`"
                />
              </FormItem>
            </Col>
            <Col :xs="24" :lg="12">
              <FormItem label="维度">
                <Select
                  v-model:value="form.dimensions"
                  mode="multiple"
                  :options="dimensionOptions"
                  :loading="metaLoading"
                  :disabled="!meta"
                  :max-count="QUERY_MAX_DIMENSIONS"
                  show-search
                  option-filter-prop="label"
                  :placeholder="
                    meta
                      ? `所选指标共有的来源字段，最多 ${QUERY_MAX_DIMENSIONS} 个`
                      : '请先选择指标'
                  "
                />
              </FormItem>
            </Col>
            <Col :xs="24" :md="10" :lg="8">
              <FormItem label="统计时间范围">
                <RangePicker
                  v-model:value="form.range"
                  value-format="YYYY-MM-DD"
                  class="w-full"
                  :disabled="!timeSupported"
                />
              </FormItem>
            </Col>
            <Col :xs="12" :md="7" :lg="4">
              <FormItem label="时间粒度">
                <Select
                  v-model:value="form.timeGrain"
                  :options="grainOptions"
                  :disabled="!timeSupported"
                  allow-clear
                  placeholder="不按时间分组"
                />
              </FormItem>
            </Col>
            <Col :xs="12" :md="7" :lg="4">
              <FormItem label="最大行数">
                <InputNumber
                  v-model:value="form.limit"
                  class="w-full"
                  :min="1"
                  :max="QUERY_MAX_LIMIT"
                  :precision="0"
                />
              </FormItem>
            </Col>
            <Col :xs="24" :lg="8">
              <FormItem label="维度过滤（等值）">
                <div
                  v-for="(item, index) in form.filters"
                  :key="index"
                  class="mb-2 flex gap-2"
                >
                  <Select
                    v-model:value="item.fieldUid"
                    class="w-1/2"
                    :options="dimensionOptions"
                    show-search
                    option-filter-prop="label"
                    placeholder="字段"
                  />
                  <Input
                    v-model:value="item.value"
                    class="flex-1"
                    :maxlength="500"
                    placeholder="值"
                  />
                  <Button danger type="link" @click="removeFilter(index)">
                    删除
                  </Button>
                </div>
                <Button
                  size="small"
                  :disabled="!meta || form.filters.length >= QUERY_MAX_FILTERS"
                  @click="addFilter"
                >
                  添加过滤
                </Button>
              </FormItem>
            </Col>
          </Row>
          <Alert
            v-if="metaError"
            class="mb-3"
            type="error"
            :message="metaError"
          />
          <Alert
            v-else-if="meta && !meta.timeSupported"
            class="mb-3"
            type="info"
            show-icon
            message="部分指标未配置统计时间字段，本次只能按维度汇总。"
          />
          <div class="flex gap-2">
            <Button
              type="primary"
              :loading="querying"
              :disabled="form.indicatorIds.length === 0 || !meta || metaLoading"
              @click="runQuery"
            >
              查询
            </Button>
            <Button @click="resetForm">重置</Button>
          </div>
        </Form>
      </Card>

      <Spin :spinning="querying">
        <Alert
          v-if="queryError"
          class="mb-3"
          type="error"
          :message="queryError"
        />
        <template v-if="result">
          <Alert
            v-if="result.truncated"
            class="mb-3"
            type="warning"
            show-icon
            :message="`结果超过 ${form.limit ?? QUERY_DEFAULT_LIMIT} 行已截断，请缩小范围或减少维度。`"
          />
          <Card v-if="chart" class="mb-3" size="small" title="趋势">
            <Alert
              v-if="chart.omitted"
              class="mb-2"
              type="info"
              message="系列较多，图中只显示前 20 条，完整数据见下方表格。"
            />
            <EchartsUI ref="chartRef" height="360px" />
          </Card>
          <Card class="mb-3" size="small" title="查询结果">
            <Table
              :columns="columns"
              :data-source="result.rows"
              :row-key="
                (row: Record<string, null | string>) =>
                  rowKey(row, result!.columns)
              "
              :pagination="{ pageSize: 20, showSizeChanger: true }"
              size="small"
              :scroll="{ x: 'max-content' }"
            />
          </Card>
          <Card size="small" title="生效口径">
            <div
              v-for="caliber in result.calibers"
              :key="String(caliber.indicatorId)"
              class="mb-3"
            >
              <div class="mb-1 font-medium">
                <Tag color="blue">
                  {{
                    TYPE_LABELS[caliber.indicatorType] ?? caliber.indicatorType
                  }}
                </Tag>
                {{ caliber.name }} ({{ caliber.code }})
                <div v-if="caliber.formula" class="font-mono text-xs">
                  = {{ caliber.formula }}
                </div>
              </div>
              <ul class="ml-5 list-disc text-xs text-gray-600">
                <li v-for="line in caliberSummary(caliber)" :key="line">
                  {{ line }}
                </li>
              </ul>
            </div>
            <div class="text-xs text-gray-500">
              时间粒度：{{
                result.timeGrain ? PERIOD_LABELS[result.timeGrain] : '不分组'
              }}； 时间范围：{{
                result.timeStart
                  ? `${result.timeStart} ~ ${result.timeEnd}`
                  : '全部'
              }}
            </div>
          </Card>
        </template>
        <Empty
          v-else-if="!querying && !queryError"
          description="选择指标后点击查询"
        />
      </Spin>
    </template>
  </Page>
</template>
