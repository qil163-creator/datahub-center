<script setup lang="ts">
import type { MetricCardItem } from '../../../dashboard/shared/data';

import type { AssetOperationApi } from '#/api/asset/operation';

import { computed, onMounted, ref } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { downloadFileFromBlobPart } from '@vben/utils';

import {
  Alert,
  Button,
  Card,
  Empty,
  message,
  Progress,
  Table,
  Tag,
} from 'antdv-next';

import {
  exportAssetOperation,
  getAssetInventory,
  refreshAssetOperation,
} from '#/api/asset/operation';

import ChartCard from '../../../dashboard/shared/chart-card.vue';
import MetricCards from '../../../dashboard/shared/metric-cards.vue';
import {
  ASSET_TYPES,
  categoryBar,
  distributionPie,
  formatIdle,
  formatScore,
  SUGGESTIONS,
  VALUE_LEVELS,
} from '../shared';
import { inventoryCards } from './model';

defineOptions({ name: 'AssetOperationInventory' });

const { hasAccessByCodes } = useAccess();
const canRefresh = computed(() =>
  hasAccessByCodes(['asset:operation:refresh']),
);
const canExport = computed(() => hasAccessByCodes(['asset:operation:export']));

const loading = ref(false);
const refreshing = ref(false);
const exporting = ref(false);
const report = ref<AssetOperationApi.Inventory>();

const hasData = computed(() => !!report.value?.statDate);
const cards = computed<MetricCardItem[]>(() =>
  report.value ? inventoryCards(report.value) : [],
);
const levelOptions = computed(() =>
  report.value && hasData.value
    ? distributionPie(report.value.levelDistribution, VALUE_LEVELS)
    : undefined,
);
const suggestionOptions = computed(() =>
  report.value && hasData.value
    ? distributionPie(report.value.suggestionDistribution, SUGGESTIONS)
    : undefined,
);
const categoryOptions = computed(() =>
  report.value?.categoryDistribution.length
    ? categoryBar(report.value.categoryDistribution)
    : undefined,
);
const typeSummary = computed(() =>
  Object.entries(report.value?.typeDistribution ?? {})
    .map(([type, count]) => `${ASSET_TYPES[Number(type)] ?? '其他'} ${count}`)
    .join(' · '),
);

const rankColumns = [
  { title: '资产', key: 'asset' },
  { title: '热度', key: 'heat', width: 80 },
  { title: '价值', key: 'value', width: 110 },
];
const idleColumns = [
  { title: '资产', key: 'asset' },
  { title: '闲置', key: 'idle', width: 90 },
  { title: '建议', key: 'suggestion', width: 110 },
];

async function load() {
  loading.value = true;
  try {
    report.value = await getAssetInventory();
  } finally {
    loading.value = false;
  }
}

async function refresh() {
  if (!canRefresh.value || refreshing.value) return;
  refreshing.value = true;
  try {
    const count = await refreshAssetOperation();
    message.success(`运营指标已重新计算，共 ${count} 个资产`);
    await load();
  } finally {
    refreshing.value = false;
  }
}

async function exportList() {
  if (!canExport.value || exporting.value) return;
  exporting.value = true;
  try {
    const data = await exportAssetOperation({
      sortField: 'valueScore',
      sortOrder: 'desc',
    });
    downloadFileFromBlobPart({
      fileName: `资产盘点清单-${report.value?.statDate ?? ''}.xls`,
      source: data,
    });
  } finally {
    exporting.value = false;
  }
}

onMounted(load);
</script>

<template>
  <Page
    title="资产盘点报告"
    :description="
      hasData
        ? `统计日期 ${report?.statDate}，使用类指标为近 ${report?.windowDays} 天；资产构成：${typeSummary}`
        : '尚未生成运营指标'
    "
  >
    <template #extra>
      <Button
        v-if="canExport && hasData"
        :loading="exporting"
        @click="exportList"
      >
        导出盘点清单
      </Button>
      <Button
        v-if="canRefresh"
        class="ml-2"
        type="primary"
        :loading="refreshing"
        @click="refresh"
      >
        刷新指标
      </Button>
    </template>

    <Card v-if="!loading && !hasData">
      <Empty
        description="尚未生成运营指标。每日凌晨自动计算，也可点击“刷新指标”立即生成。"
      />
    </Card>

    <template v-else>
      <Alert
        v-if="report && report.apiStatsAvailable === false"
        class="mb-4"
        type="warning"
        show-icon
        message="本次计算未取得数据服务的接口调用统计，接口调用按 0 计。"
      />
      <MetricCards :items="cards" :loading="loading" />

      <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
        <Card title="编目与质量覆盖" :loading="loading">
          <div class="coverage-row">
            <span>描述覆盖率</span>
            <Progress
              :percent="Number(report?.descriptionRate ?? 0)"
              size="small"
            />
          </div>
          <div class="coverage-row">
            <span>责任人覆盖率</span>
            <Progress :percent="Number(report?.ownerRate ?? 0)" size="small" />
          </div>
          <div class="coverage-row">
            <span>质量评估覆盖率</span>
            <Progress
              :percent="Number(report?.qualityAssessedRate ?? 0)"
              size="small"
            />
          </div>
          <div class="text-muted-foreground mt-3 text-xs leading-6">
            平均完整度 {{ formatScore(report?.avgCompleteness) }} · 平均质量
            {{ formatScore(report?.avgQuality) }} · 平均价值
            {{ formatScore(report?.avgValue) }}
          </div>
        </Card>
        <ChartCard
          title="价值等级分布"
          :loading="loading"
          :options="levelOptions"
          height="240px"
        />
        <ChartCard
          title="运营建议分布"
          :loading="loading"
          :options="suggestionOptions"
          height="240px"
        />
      </div>

      <div class="mt-4">
        <ChartCard
          title="类目分布"
          extra="资产数前 12 的类目"
          :loading="loading"
          :options="categoryOptions"
          height="300px"
        />
      </div>

      <div class="mt-4 grid grid-cols-1 gap-4 xl:grid-cols-3">
        <Card title="热度 Top 10" :loading="loading">
          <Table
            :columns="rankColumns"
            :data-source="report?.topHot ?? []"
            :pagination="false"
            row-key="assetId"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'asset'">{{
                record.assetName
              }}</template>
              <template v-else-if="column.key === 'heat'">
                {{ formatScore(record.heatScore) }}
              </template>
              <template v-else-if="column.key === 'value'">
                {{ formatScore(record.valueScore) }}
                <Tag
                  :color="
                    VALUE_LEVELS[
                      record.valueLevel as AssetOperationApi.ValueLevel
                    ]?.color
                  "
                >
                  {{
                    VALUE_LEVELS[
                      record.valueLevel as AssetOperationApi.ValueLevel
                    ]?.label
                  }}
                </Tag>
              </template>
            </template>
          </Table>
        </Card>
        <Card title="价值 Top 10" :loading="loading">
          <Table
            :columns="rankColumns"
            :data-source="report?.topValue ?? []"
            :pagination="false"
            row-key="assetId"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'asset'">{{
                record.assetName
              }}</template>
              <template v-else-if="column.key === 'heat'">
                {{ formatScore(record.heatScore) }}
              </template>
              <template v-else-if="column.key === 'value'">
                {{ formatScore(record.valueScore) }}
                <Tag
                  :color="
                    VALUE_LEVELS[
                      record.valueLevel as AssetOperationApi.ValueLevel
                    ]?.color
                  "
                >
                  {{
                    VALUE_LEVELS[
                      record.valueLevel as AssetOperationApi.ValueLevel
                    ]?.label
                  }}
                </Tag>
              </template>
            </template>
          </Table>
        </Card>
        <Card title="闲置最久的已发布资产" :loading="loading">
          <Table
            :columns="idleColumns"
            :data-source="report?.longestIdle ?? []"
            :pagination="false"
            row-key="assetId"
            size="small"
          >
            <template #bodyCell="{ column, record }">
              <template v-if="column.key === 'asset'">{{
                record.assetName
              }}</template>
              <template v-else-if="column.key === 'idle'">
                {{ formatIdle(record.idleDays) }}
              </template>
              <template v-else-if="column.key === 'suggestion'">
                <Tag
                  :color="
                    SUGGESTIONS[
                      record.suggestion as AssetOperationApi.Suggestion
                    ]?.color
                  "
                >
                  {{
                    SUGGESTIONS[
                      record.suggestion as AssetOperationApi.Suggestion
                    ]?.label
                  }}
                </Tag>
              </template>
            </template>
          </Table>
          <div class="text-muted-foreground mt-2 text-xs">
            闲置：已发布、90 天无申请/预览/查询/接口调用且无有效授权。
          </div>
        </Card>
      </div>
    </template>
  </Page>
</template>

<style scoped>
.coverage-row {
  display: grid;
  grid-template-columns: 96px 1fr;
  gap: 8px;
  align-items: center;
  margin-bottom: 8px;
  font-size: 13px;
}
</style>
