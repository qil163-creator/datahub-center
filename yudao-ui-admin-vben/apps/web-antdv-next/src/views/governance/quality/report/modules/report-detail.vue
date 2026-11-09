<script setup lang="ts">
import type { EchartsUIType } from '@vben/plugins/echarts';

import type { QualityReportApi } from '#/api/governance/quality/report';

import {
  computed,
  nextTick,
  onActivated,
  onDeactivated,
  ref,
  watch,
} from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { EchartsUI, useEcharts } from '@vben/plugins/echarts';
import { useAccessStore } from '@vben/stores';

import { Alert, Button, Spin, Table } from 'antdv-next';

import { ACTION_ICON, TableAction } from '#/adapter/vxe-table';
import { getQualityReport } from '#/api/governance/quality/report';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { useObjectRequest } from '#/hooks/use-object-request';

import ProblemData from '../../issue-data/modules/problem-data.vue';
import RepairForm from '../../repair-order/modules/form.vue';
import {
  chartScore,
  dimensionCards,
  dimensionLabel,
  percentWidth,
} from '../report-presentation';

const props = defineProps<{ instanceId: string }>();
const emit = defineEmits<{ back: [] }>();
const access = useAccessStore();
const emptyImage = '/images/quality-reference/empty.png';
const { hasAccessByCodes } = useAccess();
const report = ref<QualityReportApi.Detail>();
const days = ref<7 | 15 | 30>(7);
const loading = ref(false);
const failed = ref(false);
const problemRef = ref<InstanceType<typeof ProblemData>>();
const repairRef = ref<InstanceType<typeof RepairForm>>();
const radarRef = ref<EchartsUIType>();
const trendRef = ref<EchartsUIType>();
const { renderEcharts: renderRadar } = useEcharts(radarRef);
const { renderEcharts: renderTrend } = useEcharts(trendRef);
const cards = computed(() => dimensionCards(report.value?.dimensions ?? []));
const request = useObjectRequest(() => `${props.instanceId}/${days.value}`);
let suspended = false;

async function load() {
  if (suspended) return;
  const current = request.begin();
  loading.value = true;
  failed.value = false;
  report.value = undefined;
  try {
    const value = await getQualityReport(props.instanceId, days.value);
    if (!current()) return;
    report.value = value;
    loading.value = false;
    await nextTick();
    if (!current()) return;
    renderRadar({
      animation: false,
      tooltip: { trigger: 'item' },
      radar: {
        center: ['50%', '54%'],
        radius: '55%',
        axisNameGap: 6,
        splitNumber: 5,
        axisName: { color: '#333', fontSize: 12 },
        splitLine: { lineStyle: { color: '#dbe0e7' } },
        splitArea: { show: false },
        axisLine: { lineStyle: { color: '#dbe0e7' } },
        indicator: cards.value.map((item) => ({
          name: `${item.label}${item.data?.score === null || item.data?.score === undefined ? '(未配置)' : ''}`,
          max: 100,
        })),
      },
      series: [
        {
          type: 'radar',
          symbolSize: 3,
          lineStyle: { color: '#526fff' },
          areaStyle: { color: '#e9edff' },
          data: [
            {
              name: '质量评分',
              value: cards.value.map((item) => chartScore(item.data?.score)),
            },
          ],
        },
      ],
    });
    renderTrend({
      animation: false,
      tooltip: {
        trigger: 'axis',
        valueFormatter: (value: unknown) =>
          value === null || value === undefined ? '未执行' : `${value} 分`,
      },
      grid: { left: 72, right: 70, top: 24, bottom: 32 },
      xAxis: {
        type: 'category',
        boundaryGap: false,
        data: value.trend.map((item) => item.date),
        axisLine: { show: false },
        axisTick: { show: false },
        axisLabel: { color: '#333' },
        splitLine: {
          show: true,
          lineStyle: { color: '#eef0f4', type: 'dashed' },
        },
      },
      yAxis: {
        type: 'value',
        min: 0,
        max: 100,
        interval: 20,
        axisLabel: { color: '#777' },
        splitLine: { lineStyle: { color: '#eef0f4', type: 'dashed' } },
      },
      series: [
        {
          name: '质量评分',
          type: 'line',
          smooth: true,
          showSymbol:
            value.trend.filter((item) => item.score !== null).length === 1,
          symbol: 'circle',
          symbolSize: 7,
          connectNulls: false,
          lineStyle: { width: 2, color: '#526fff' },
          itemStyle: { color: '#526fff' },
          areaStyle: { color: '#e4e9ff' },
          data: value.trend.map((item) => chartScore(item.score)),
        },
      ],
    });
  } catch {
    if (current()) failed.value = true;
  } finally {
    if (current()) loading.value = false;
  }
}

function openProblem(row: QualityReportApi.Rule) {
  problemRef.value?.open(row);
}
function createRepair(row: QualityReportApi.Rule) {
  repairRef.value?.open({
    orderTitle: `修复 - ${row.ruleName}`,
    objectId: row.checkObjectId,
    tableName: row.tableName,
    checkField: row.checkField,
    targetField: row.checkField,
  });
}

const columns = computed(() => [
  { title: '评测名称', dataIndex: 'ruleName', key: 'ruleName', width: 270 },
  {
    title: '数据库名称',
    dataIndex: 'datasourceName',
    key: 'datasourceName',
    width: 220,
  },
  {
    title: '字段名/中文名',
    dataIndex: 'fieldLabels',
    key: 'fieldLabels',
    width: 280,
  },
  { title: '质量维度', dataIndex: 'qualityDim', key: 'qualityDim', width: 150 },
  { title: '稽查名称', dataIndex: 'ruleType', key: 'ruleType', width: 230 },
  { title: '问题数量', dataIndex: 'failCount', key: 'failCount', width: 150 },
  {
    title: '数据量占比',
    dataIndex: 'problemRatio',
    key: 'problemRatio',
    width: 260,
  },
  { title: '操作', key: 'actions', width: 220, fixed: 'right' as const },
]);
watch(
  () => props.instanceId,
  () => {
    problemRef.value?.close();
    repairRef.value?.close();
  },
);
watch(
  () => [props.instanceId, days.value, access.accessToken, access.refreshToken],
  load,
  { immediate: true },
);
onDeactivated(() => {
  suspended = true;
  request.begin();
  problemRef.value?.close();
  repairRef.value?.close();
});
onActivated(() => {
  if (!suspended) return;
  suspended = false;
  void load();
});
</script>

<template>
  <div class="quality-report-detail">
    <Spin v-if="loading" class="quality-report-loading" />
    <Alert v-else-if="failed" type="error" message="报告详情加载失败">
      <template #action>
        <Button @click="load">
          <template #icon>
            <IconifyIcon icon="lucide:refresh-cw" />
          </template>
          重试 </Button
        ><DetailBackButton @click="emit('back')" />
      </template>
    </Alert>
    <template v-else-if="report">
      <section class="quality-report-panel">
        <div class="quality-report-section-title">
          <h2>数据质量维度统计</h2>
          <DetailBackButton @click="emit('back')" />
        </div>
        <div class="quality-report-dimensions">
          <p class="quality-report-overall">
            整体数据质量评分：<strong>{{
              report.instance.score ?? '-'
            }}</strong>
            分
          </p>
          <div class="quality-report-dimension-content">
            <EchartsUI
              ref="radarRef"
              height="180px"
              class="quality-report-radar"
            />
            <div
              class="quality-report-dimension-cards"
              :style="{
                gridTemplateColumns: `repeat(${cards.length}, minmax(0, 1fr))`,
              }"
            >
              <article
                v-for="item in cards"
                :key="item.value"
                class="quality-report-dimension-card"
              >
                <div class="quality-report-dimension-main">
                  <img
                    v-if="item.image"
                    :src="`/images/quality-reference/${item.image}.png`"
                    alt=""
                    aria-hidden="true"
                    draggable="false"
                  />
                  <div>
                    <h3>{{ item.label }}</h3>
                    <p>
                      规则数：<strong>{{ item.data?.ruleCount ?? 0 }}</strong>
                      个
                    </p>
                    <p>
                      问题数占比：<strong>{{
                        item.data?.problemRatio ?? '-'
                      }}</strong
                      ><span v-if="item.data?.problemRatio != null"> %</span>
                    </p>
                  </div>
                </div>
                <p class="quality-report-comparison">
                  同比上期
                  {{
                    item.data?.previousProblemRatio == null
                      ? '-'
                      : `${item.data.previousProblemRatio}%`
                  }}
                </p>
              </article>
            </div>
          </div>
        </div>
      </section>
      <section class="quality-report-panel quality-report-trend-panel">
        <div class="quality-report-section-title">
          <h2>数据质量纬度统计</h2>
          <div class="quality-report-days">
            <Button
              v-for="day in [7, 15, 30] as const"
              :key="day"
              size="small"
              :type="days === day ? 'primary' : 'default'"
              :class="days === day ? '' : 'quality-report-query'"
              @click="days = day"
            >
              近{{ day }}天
            </Button>
          </div>
        </div>
        <EchartsUI ref="trendRef" height="260px" class="quality-report-trend" />
      </section>
      <section class="quality-report-panel quality-report-rules-panel">
        <div class="quality-report-section-title"><h2>规则列表</h2></div>
        <Table
          :columns="columns"
          :data-source="report.rules"
          :pagination="false"
          :row-key="(row: QualityReportApi.Rule) => String(row.id)"
          :scroll="{ x: 1700 }"
          class="quality-report-table"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'fieldLabels'">
              {{ record.fieldLabels?.join('，') || record.checkField || '-' }}
            </template>
            <template v-else-if="column.key === 'ruleType'">
              {{ record.ruleTypeName || record.ruleType || '-' }}
            </template>
            <template v-else-if="column.key === 'qualityDim'">
              <span class="quality-report-dimension-tag">{{
                dimensionLabel(record.qualityDim)
              }}</span>
            </template>
            <template v-else-if="column.key === 'failCount'">
              {{ record.failCount ?? '-' }}个
            </template>
            <template v-else-if="column.key === 'problemRatio'">
              <div class="quality-report-ratio">
                <span
                  ><i
                    :style="{ width: percentWidth(record.problemRatio) }"
                  ></i></span
                >{{ record.problemRatio ?? '-'
                }}<template v-if="record.problemRatio != null">%</template>
              </div>
            </template>
            <template v-else-if="column.key === 'actions'">
              <TableAction
                :actions="[
                  {
                    label: '查看问题数据',
                    icon: ACTION_ICON.VIEW,
                    type: 'link',
                    auth: ['governance:quality-issue-data:query'],
                    onClick: () => openProblem(record as QualityReportApi.Rule),
                  },
                ]"
                :drop-down-actions="
                  hasAccessByCodes(['governance:quality-repair-order:create'])
                    ? [
                        {
                          label: '发起修复',
                          icon: 'lucide:wrench',
                          disabled: record.resultType !== 'FAIL',
                          onClick: () =>
                            createRepair(record as QualityReportApi.Rule),
                        },
                      ]
                    : []
                "
              />
            </template>
          </template>
          <template #emptyText>
            <div class="quality-report-empty">
              <img :src="emptyImage" alt="" /><span>暂无记录</span>
            </div>
          </template>
        </Table>
      </section>
    </template>
    <ProblemData ref="problemRef" />
    <RepairForm ref="repairRef" />
  </div>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
