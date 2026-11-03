<script lang="ts" setup>
import type { ModelHomeApi } from '#/api/governance/model-home';
import type { RouteTarget } from '#/views/dashboard/shared/route-resolver';

import { computed, onActivated, onDeactivated, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDate, formatDateTime } from '@vben/utils';

import { Alert, Button, Skeleton } from 'antdv-next';

import { getModelOverview } from '#/api/governance/model-home';
import { useObjectRequest } from '#/hooks/use-object-request';
import {
  DASHBOARD_TARGETS,
  useDashboardNav,
} from '#/views/dashboard/shared/route-resolver';

import OverviewArtwork from './overview-artwork.vue';
import {
  displayCount,
  distributionPercent,
  distributionTotal,
} from './overview-values';

defineOptions({ name: 'ModelHome' });

const access = useAccessStore();
const user = useUserStore();
const { hasAccessByCodes } = useAccess();
const { navTo, resolve } = useDashboardNav();
const overview = ref<ModelHomeApi.ModelOverview>();
const loading = ref(false);
const failed = ref(false);
const active = ref(true);

const scope = computed(() =>
  JSON.stringify([
    user.userInfo?.userId,
    user.userRoles,
    access.accessCodes,
    access.accessToken,
    access.refreshToken,
  ]),
);

function resetOverview() {
  overview.value = undefined;
  loading.value = false;
  failed.value = false;
}

const requests = useObjectRequest(() => scope.value, resetOverview);

async function loadOverview() {
  const current = requests.begin();
  if (!active.value || !access.accessToken) {
    resetOverview();
    return;
  }
  loading.value = true;
  failed.value = false;
  try {
    const result = await getModelOverview();
    if (current()) overview.value = result;
  } catch {
    if (current()) {
      overview.value = undefined;
      failed.value = true;
    }
  } finally {
    if (current()) loading.value = false;
  }
}

watch(scope, loadOverview, { flush: 'sync', immediate: true });
onDeactivated(() => {
  active.value = false;
  requests.begin();
  resetOverview();
});
onActivated(() => {
  if (!active.value) {
    active.value = true;
    void loadOverview();
  }
});

const targets = {
  conceptModel: { names: ['ModelingConceptModel'], titles: ['概念模型'] },
  indicatorModel: { names: ['ModelingIndicatorModel'], titles: ['指标模型'] },
  dataDomain: { names: ['ModelingDataDomain'], titles: ['数据域'] },
  subjectDomain: { names: ['ModelingSubjectDomain'], titles: ['主题域'] },
  layer: { names: ['DwLayer'], titles: ['数仓分层'] },
  materialized: {
    names: ['ModelingModelMaterialized'],
    titles: ['模型物化'],
  },
} satisfies Record<string, RouteTarget>;

const metricDefinitions: {
  color: string;
  icon: string;
  key: ModelHomeApi.MetricKey;
  label: string;
  permission?: string;
  target?: RouteTarget;
}[] = [
  {
    key: 'dataDomain',
    label: '数据域',
    icon: 'lucide:layers-3',
    color: 'blue',
    target: targets.dataDomain,
    permission: 'modeling:data-domain:query',
  },
  {
    key: 'subjectDomain',
    label: '主题域',
    icon: 'lucide:network',
    color: 'teal',
    target: targets.subjectDomain,
    permission: 'modeling:subject-domain:query',
  },
  {
    key: 'conceptModel',
    label: '概念模型',
    icon: 'lucide:atom',
    color: 'indigo',
    target: targets.conceptModel,
    permission: 'modeling:concept-model:query',
  },
  {
    key: 'logicalModel',
    label: '逻辑模型',
    icon: 'lucide:boxes',
    color: 'orange',
    target: DASHBOARD_TARGETS.logicModel,
    permission: 'modeling:model:query',
  },
  {
    key: 'indicatorModel',
    label: '指标模型',
    icon: 'lucide:chart-pie',
    color: 'teal',
    target: targets.indicatorModel,
    permission: 'modeling:indicator-model:query',
  },
  {
    key: 'publishedModel',
    label: '已发布模型',
    icon: 'lucide:scan-search',
    color: 'violet',
    target: DASHBOARD_TARGETS.logicModel,
    permission: 'modeling:model:query',
  },
];

const metrics = computed(() =>
  metricDefinitions.map((definition) => {
    const metric = overview.value?.metrics.find(
      (item) => item.key === definition.key,
    );
    return {
      ...definition,
      metric,
      path:
        metric?.available &&
        definition.permission &&
        hasAccessByCodes([definition.permission])
          ? resolve(definition.target)
          : undefined,
    };
  }),
);

function metricHint(metric?: ModelHomeApi.Metric) {
  if (failed.value) return '加载失败，请重试';
  if (!metric) return loading.value ? '正在加载统计' : '暂无统计';
  if (!metric.available || metric.recentCount === null)
    return metric.description;
  return `${metric.key === 'publishedModel' ? '近 7 日发布' : '近 7 日新增'} ${displayCount(metric.recentCount)} 个`;
}

const steps = [
  {
    title: '业务规划',
    icon: 'lucide:clipboard-list',
    lines: ['业务分类 / 数据域', '主题域与业务归属', '统一业务语言'],
    target: targets.dataDomain,
    principle: '统一业务语言',
  },
  {
    title: '数仓分层',
    icon: 'lucide:layers-3',
    lines: ['ODS / DIM / DWD', 'DWS / ADS', '分层与命名规范'],
    target: targets.layer,
    principle: '统一模型结构',
  },
  {
    title: '逻辑建模',
    icon: 'lucide:boxes',
    lines: ['事实表 / 维度表', '字段与模型关系', '模型版本管理'],
    target: DASHBOARD_TARGETS.logicModel,
    principle: '统一模型定义',
  },
  {
    title: '标准与规范',
    icon: 'lucide:chart-no-axes-combined',
    lines: ['标准数据元 / 码表', '字段标准关联', '统一数据口径'],
    target: DASHBOARD_TARGETS.standardElement,
    principle: '统一标准规范',
  },
  {
    title: '发布与落地',
    icon: 'lucide:send',
    lines: ['模型发布 / 版本快照', '目标数据源物化', '下游应用与引用'],
    target: targets.materialized,
    principle: '统一发布落地',
  },
];

const downstream = [
  {
    title: '数据集成',
    icon: 'lucide:database-zap',
    description: '字段定义与模型结构，为数据同步提供目标依据',
    target: DASHBOARD_TARGETS.access,
  },
  {
    title: '数据开发',
    icon: 'lucide:database',
    description: '提供 DWD / DWS / ADS 模型参考与字段口径',
    target: DASHBOARD_TARGETS.devWorkbench,
  },
  {
    title: '数据治理',
    icon: 'lucide:shield-check',
    description: '关联数据标准，支持质量规则与元数据管理',
    target: DASHBOARD_TARGETS.qualityRule,
  },
  {
    title: '数据资产',
    icon: 'lucide:gem',
    description: '沉淀模型结构与业务语义，辅助资产理解',
    target: DASHBOARD_TARGETS.dataAsset,
  },
  {
    title: '数据服务',
    icon: 'lucide:radar',
    description: '提供规范化的数据结构，支撑服务开发',
    target: DASHBOARD_TARGETS.dataApi,
  },
  {
    title: '主题域与 BI',
    icon: 'lucide:chart-column',
    description: '以统一的模型与口径，支持主题分析与报表',
    target: DASHBOARD_TARGETS.jimuReport,
  },
];

const distributions = computed(() =>
  [
    {
      key: 'layer',
      title: '模型分层分布',
      available: overview.value?.layerDistributionAvailable,
      rows: overview.value?.layerDistribution ?? [],
      empty: '暂无模型，创建模型后查看分层分布',
      target: DASHBOARD_TARGETS.logicModel,
    },
    {
      key: 'materialized',
      title: '模型物化到物理表分布',
      available: overview.value?.materializedDistributionAvailable,
      rows: overview.value?.materializedDistribution ?? [],
      empty: '暂无已物化模型，发布并物化后查看分布',
      target: targets.materialized,
    },
  ].map((item) => ({ ...item, total: distributionTotal(item.rows) })),
);

const activities = computed(() => overview.value?.recentActivities ?? []);
const modelListPath = computed(() =>
  hasAccessByCodes(['modeling:model:query'])
    ? resolve(DASHBOARD_TARGETS.logicModel)
    : undefined,
);

const activityIcons = {
  CREATE: 'lucide:file-plus-2',
  PUBLISH: 'lucide:send',
};

function activityTime(time: number) {
  const elapsed = Math.max(0, (overview.value?.generatedAt ?? time) - time);
  if (elapsed < 60_000) return '刚刚';
  if (elapsed < 3_600_000) return `${Math.floor(elapsed / 60_000)} 分钟前`;
  if (elapsed < 86_400_000) return `${Math.floor(elapsed / 3_600_000)} 小时前`;
  if (elapsed < 2_592_000_000)
    return `${Math.floor(elapsed / 86_400_000)} 天前`;
  return formatDate(time, 'MM-DD');
}
</script>

<template>
  <Page auto-content-height content-class="bg-background-deep">
    <div class="model-home" :aria-busy="loading">
      <section class="overview-banner" aria-labelledby="overview-title">
        <div class="overview-banner__copy">
          <h1 id="overview-title">构建统一规范的数据模型体系</h1>
          <p>
            贯通模型规划、分层建设、标准定义与发布应用，持续沉淀可复用的数据资产
          </p>
        </div>
        <OverviewArtwork class="overview-banner__artwork" />
        <div class="overview-banner__aside">
          <span>让数据创造更大价值</span>
          <Button
            :loading="loading"
            class="refresh-button"
            size="small"
            @click="loadOverview"
          >
            <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
            刷新概览
          </Button>
        </div>
      </section>

      <Alert
        v-if="failed"
        type="error"
        show-icon
        class="overview-alert"
        message="建模概览加载失败"
        description="请检查网络后重试，统计数据暂不可用。"
      >
        <template #action>
          <Button size="small" @click="loadOverview">
            <template #icon>
              <IconifyIcon icon="lucide:refresh-cw" />
            </template>
            重试
          </Button>
        </template>
      </Alert>

      <section class="metric-grid" aria-label="建模核心指标">
        <button
          v-for="item in metrics"
          :key="item.key"
          :disabled="!item.path || loading"
          :title="item.metric?.description"
          :class="{ 'metric-card--link': item.path }"
          class="metric-card"
          type="button"
          @click="navTo(item.path)"
        >
          <div class="metric-card__body">
            <div class="metric-card__summary">
              <span class="metric-card__label">{{
                item.metric?.label ?? item.label
              }}</span>
              <Skeleton
                v-if="loading"
                active
                :paragraph="false"
                :title="{ width: 72 }"
              />
              <div v-else class="metric-card__number">
                <strong>{{
                  displayCount(
                    item.metric?.available ? item.metric.count : null,
                  )
                }}</strong><span v-if="item.metric?.available">个</span>
              </div>
            </div>
            <span
              class="metric-card__icon"
              :class="`metric-card__icon--${item.color}`"
              ><IconifyIcon :icon="item.icon" /></span>
          </div>
          <div class="metric-card__footer">
            <span>{{ metricHint(item.metric) }}</span><IconifyIcon v-if="item.path" icon="lucide:arrow-up-right" />
          </div>
        </button>
      </section>

      <div class="overview-middle">
        <section
          class="overview-panel process-panel"
          aria-labelledby="process-title"
        >
          <header class="panel-heading">
            <h2 id="process-title">建模规范主流程</h2>
            <span class="panel-heading__note">从业务规划到数据应用</span>
          </header>
          <div class="process-steps">
            <div
              v-for="(step, index) in steps"
              :key="step.title"
              class="process-step-wrap"
            >
              <button
                type="button"
                class="process-step"
                :disabled="!resolve(step.target)"
                @click="navTo(step.target)"
              >
                <span class="process-step__number">{{ index + 1 }}</span>
                <span class="process-step__icon"><IconifyIcon :icon="step.icon" /></span>
                <h3>{{ step.title }}</h3>
                <p v-for="line in step.lines" :key="line">{{ line }}</p>
              </button>
              <IconifyIcon
                v-if="index < steps.length - 1"
                class="process-arrow"
                icon="lucide:move-right"
              />
            </div>
          </div>
          <div class="process-principles">
            <span v-for="step in steps" :key="step.title"><IconifyIcon :icon="step.icon" />{{ step.principle }}</span>
          </div>
        </section>

        <section
          class="overview-panel downstream-panel"
          aria-labelledby="downstream-title"
        >
          <header class="panel-heading">
            <h2 id="downstream-title">建模产出如何支持后续模块</h2>
          </header>
          <div class="downstream-content">
            <div class="downstream-origin">
              <OverviewArtwork compact /><span>数据建模产出</span>
            </div>
            <div class="downstream-list">
              <button
                v-for="item in downstream"
                :key="item.title"
                type="button"
                :disabled="!resolve(item.target)"
                class="downstream-item"
                @click="navTo(item.target)"
              >
                <span class="downstream-item__icon"><IconifyIcon :icon="item.icon" /></span>
                <strong>{{ item.title }}</strong><span class="downstream-item__description">{{
                  item.description
                }}</span>
              </button>
            </div>
          </div>
        </section>
      </div>

      <div class="overview-bottom">
        <section
          v-for="distribution in distributions"
          :key="distribution.key"
          class="overview-panel distribution-panel"
          :aria-labelledby="`${distribution.key}-title`"
        >
          <header class="panel-heading">
            <h2 :id="`${distribution.key}-title`">{{ distribution.title }}</h2>
            <span class="panel-heading__total">总数
              <b>{{
                distribution.available && !loading
                  ? displayCount(distribution.total)
                  : '—'
              }}</b></span>
          </header>
          <div class="distribution-body">
            <Skeleton
              v-if="loading"
              active
              :paragraph="{ rows: 7 }"
              :title="false"
            />
            <div
              v-else-if="
                failed || !distribution.available || distribution.total === '0'
              "
              class="panel-empty"
            >
              <IconifyIcon
                :icon="
                  failed
                    ? 'lucide:cloud-off'
                    : distribution.available
                      ? 'lucide:chart-bar'
                      : 'lucide:lock-keyhole'
                "
              />
              <span>{{
                failed
                  ? '统计数据加载失败'
                  : distribution.available
                    ? distribution.empty
                    : '暂无查看该统计的权限'
              }}</span>
              <Button
                v-if="
                  !failed &&
                  distribution.available &&
                  resolve(distribution.target)
                "
                type="link"
                size="small"
                @click="navTo(distribution.target)"
              >
                前往模型管理 <IconifyIcon icon="lucide:arrow-right" />
              </Button>
            </div>
            <div v-else class="distribution-list">
              <div
                v-for="row in distribution.rows"
                :key="row.key"
                class="distribution-row"
              >
                <div class="distribution-row__label">
                  <span :title="row.name">{{ row.name
                    }}<template v-if="row.code && row.code !== row.name">
                      / {{ row.code }}</template></span><span>{{ displayCount(row.count) }}
                    <small>({{
                        distributionPercent(
                          row.count,
                          distribution.total,
                        ).toFixed(1)
                      }}%)</small></span>
                </div>
                <div
                  class="distribution-track"
                  role="meter"
                  :aria-label="row.name"
                  :aria-valuenow="
                    distributionPercent(row.count, distribution.total)
                  "
                  :aria-valuemin="0"
                  :aria-valuemax="100"
                >
                  <span
                    :style="{
                      width: `${distributionPercent(row.count, distribution.total)}%`,
                    }"
                  ></span>
                </div>
              </div>
            </div>
          </div>
        </section>

        <section
          class="overview-panel activity-panel"
          aria-labelledby="activity-title"
        >
          <header class="panel-heading">
            <h2 id="activity-title">最近动态</h2>
            <Button
              v-if="modelListPath"
              class="panel-heading__link"
              type="link"
              size="small"
              @click="navTo(modelListPath)"
            >
              查看模型<IconifyIcon icon="lucide:chevron-right" />
            </Button>
          </header>
          <div class="activity-body">
            <Skeleton
              v-if="loading"
              active
              :paragraph="{ rows: 8 }"
              :title="false"
            />
            <div
              v-else-if="
                failed ||
                !overview?.recentActivitiesAvailable ||
                activities.length === 0
              "
              class="panel-empty"
            >
              <IconifyIcon
                :icon="failed ? 'lucide:cloud-off' : 'lucide:history'"
              /><span>{{
                failed
                  ? '动态加载失败'
                  : overview?.recentActivitiesAvailable
                    ? '暂无模型动态，创建或发布模型后将在此展示'
                    : '暂无查看模型动态的权限'
              }}</span>
            </div>
            <ol v-else class="activity-list">
              <li v-for="activity in activities" :key="activity.id">
                <span
                  class="activity-icon"
                  :class="{
                    'activity-icon--publish': activity.type === 'PUBLISH',
                  }"
                  ><IconifyIcon :icon="activityIcons[activity.type]" /></span>
                <span class="activity-title" :title="activity.title">
                  {{ activity.title }}</span>
                <time
                  :datetime="new Date(activity.occurredAt).toISOString()"
                  :title="formatDateTime(activity.occurredAt)"
                  >{{ activityTime(activity.occurredAt) }}</time>
              </li>
            </ol>
          </div>
        </section>
      </div>
    </div>
  </Page>
</template>

<style scoped>
.model-home {
  --overview-blue: #4966ff;
  --overview-tint: color-mix(in srgb, hsl(var(--card)) 95%, #4966ff);
  --overview-line: color-mix(in srgb, hsl(var(--border)) 65%, transparent);

  display: flex;
  flex-direction: column;
  gap: 14px;
  min-height: 100%;
  color: hsl(var(--foreground));
  background: hsl(var(--background-deep));
}

.overview-banner {
  position: relative;
  display: flex;
  flex-shrink: 0;
  align-items: center;
  justify-content: space-between;
  min-height: 110px;
  padding: 22px 30px;
  overflow: hidden;
  background: linear-gradient(
    108deg,
    #e8f3ff 0%,
    #e5f1ff 45%,
    #d9eaff 77%,
    #eaf4ff 100%
  );
  border-radius: 4px;
}

.overview-banner::after {
  position: absolute;
  right: -8%;
  bottom: -125px;
  left: -8%;
  height: 160px;
  content: '';
  background: #bcdcff40;
  border-radius: 50%;
  transform: rotate(-3deg);
}

.overview-banner__copy {
  position: relative;
  z-index: 1;
}

.overview-banner h1 {
  margin: 0 0 10px;
  font-size: clamp(18px, 1.3vw, 24px);
  font-weight: 500;
  color: #2e465c;
  letter-spacing: 0.02em;
}

.overview-banner p {
  margin: 0;
  font-size: 12px;
  line-height: 1.8;
  color: #677e94;
}

.overview-banner__artwork {
  position: absolute;
  top: -27px;
  right: 16%;
}

.overview-banner__aside {
  z-index: 1;
  display: flex;
  flex-direction: column;
  gap: 12px;
  align-items: flex-end;
}

.overview-banner__aside > span {
  font-size: 16px;
  font-style: italic;
  color: #6584ed;
  letter-spacing: 0.1em;
}

.refresh-button {
  color: #5775bf;
  background: #ffffffa6;
  border-color: #adc9f559;
}

.overview-alert {
  flex-shrink: 0;
}

.metric-grid {
  display: grid;
  flex-shrink: 0;
  grid-template-columns: repeat(6, minmax(0, 1fr));
  gap: 14px;
}

.metric-card {
  min-width: 0;
  padding: 18px 20px 12px;
  text-align: left;
  cursor: default;
  background: hsl(var(--card));
  border: 1px solid transparent;
  border-radius: 4px;
  transition:
    border-color 0.15s,
    box-shadow 0.15s;
}

.metric-card--link {
  cursor: pointer;
}

.metric-card--link:hover {
  border-color: #8da3ff66;
  box-shadow: 0 4px 16px #4865ca0a;
}

.metric-card__body {
  display: flex;
  gap: 8px;
  align-items: center;
  justify-content: space-between;
  min-height: 62px;
}

.metric-card__summary {
  min-width: 0;
}

.metric-card__label {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.metric-card__number {
  display: flex;
  gap: 5px;
  align-items: baseline;
  margin-top: 3px;
  white-space: nowrap;
}

.metric-card__number strong {
  font-family: Arial, sans-serif;
  font-size: clamp(25px, 2vw, 34px);
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1.2;
}

.metric-card__number > span {
  font-size: 12px;
  color: hsl(var(--muted-foreground));
}

.metric-card__icon {
  display: grid;
  flex-shrink: 0;
  place-items: center;
  width: 42px;
  height: 42px;
  font-size: 27px;
  color: #fff;
  background: linear-gradient(145deg, #5688f5, #90b7fd);
  border: 1px solid #ffffff4d;
  border-radius: 6px;
}

.metric-card__icon--teal {
  background: linear-gradient(145deg, #5fbbc2, #9ddde1);
}

.metric-card__icon--indigo {
  background: linear-gradient(145deg, #415eff, #7b8bff);
}

.metric-card__icon--orange {
  background: linear-gradient(145deg, #e69648, #f4c280);
}

.metric-card__icon--violet {
  background: linear-gradient(145deg, #697bf0, #97a1ff);
}

.metric-card__footer {
  display: flex;
  gap: 4px;
  align-items: center;
  justify-content: space-between;
  padding-top: 9px;
  margin-top: 10px;
  font-size: 11px;
  color: hsl(var(--muted-foreground));
  border-top: 1px solid var(--overview-line);
}

.metric-card__footer > span {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.metric-card__footer > svg {
  color: var(--overview-blue);
}

.overview-middle {
  display: grid;
  grid-template-columns: minmax(0, 1.6fr) minmax(0, 1fr);
  gap: 14px;
}

.overview-panel {
  min-width: 0;
  background: hsl(var(--card));
  border-radius: 4px;
}

.panel-heading {
  display: flex;
  gap: 10px;
  align-items: center;
  justify-content: space-between;
  height: 45px;
  padding: 0 18px;
  border-bottom: 1px solid var(--overview-line);
}

.panel-heading h2 {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
  margin: 0;
  font-size: 14px;
  font-weight: 500;
}

.panel-heading h2::before {
  flex-shrink: 0;
  width: 4px;
  height: 14px;
  content: '';
  background: var(--overview-blue);
  border-radius: 4px;
}

.panel-heading__note,
.panel-heading__total {
  flex-shrink: 0;
  font-size: 11px;
  color: hsl(var(--muted-foreground));
}

.panel-heading__total b {
  margin-left: 3px;
  font-weight: 500;
  color: var(--overview-blue);
}

.panel-heading__link {
  height: auto;
  padding: 0;
  font-size: 11px;
}

.process-steps {
  display: grid;
  grid-template-columns: repeat(5, minmax(0, 1fr));
  gap: 28px;
  padding: 18px 18px 14px;
}

.process-step-wrap {
  position: relative;
  min-width: 0;
}

.process-step {
  position: relative;
  display: flex;
  flex-direction: column;
  align-items: center;
  width: 100%;
  min-height: 192px;
  padding: 22px 8px 16px;
  cursor: pointer;
  background: linear-gradient(180deg, var(--overview-tint), hsl(var(--card)));
  border: 1px solid #7790ff1c;
  border-radius: 4px;
}

.process-step:disabled,
.downstream-item:disabled {
  cursor: default;
}

.process-step:not(:disabled):hover {
  border-color: #7790ff80;
}

.process-step__number {
  position: absolute;
  top: 0;
  left: 0;
  display: grid;
  place-items: center;
  width: 26px;
  height: 22px;
  font-size: 12px;
  color: #fff;
  background: var(--overview-blue);
  border-radius: 3px 0 4px;
}

.process-step__icon {
  display: grid;
  place-items: center;
  width: 56px;
  height: 56px;
  margin-bottom: 14px;
  font-size: 32px;
  color: #628aff;
  background: radial-gradient(circle at 35% 25%, #eef2ff, #dfe7ff80);
  border-radius: 50%;
  filter: drop-shadow(0 4px 5px #5276ef0f);
}

.process-step h3 {
  margin: 0 0 10px;
  font-size: 13px;
  font-weight: 500;
}

.process-step p {
  margin: 0;
  font-size: 11px;
  line-height: 1.9;
  color: hsl(var(--muted-foreground));
  text-align: center;
}

.process-arrow {
  position: absolute;
  top: 48%;
  right: -23px;
  width: 18px;
  height: 18px;
  color: #7b9bff;
}

.process-principles {
  display: flex;
  gap: 8px;
  justify-content: space-between;
  padding: 10px 12px;
  margin: 0 18px 18px;
  background: var(--overview-tint);
  border-radius: 3px;
}

.process-principles span {
  display: inline-flex;
  gap: 7px;
  align-items: center;
  font-size: 11px;
  color: hsl(var(--muted-foreground));
  white-space: nowrap;
}

.process-principles svg {
  font-size: 14px;
  color: var(--overview-blue);
}

.downstream-content {
  display: flex;
  gap: 12px;
  align-items: center;
  min-height: 280px;
  padding: 16px 18px;
}

.downstream-origin {
  display: flex;
  flex-shrink: 0;
  flex-direction: column;
  align-items: center;
  width: 145px;
  font-size: 13px;
}

.downstream-origin > span {
  margin-top: 3px;
}

.downstream-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 10px;
  min-width: 0;
}

.downstream-item {
  display: flex;
  gap: 10px;
  align-items: center;
  width: 100%;
  padding: 2px 0;
  text-align: left;
  cursor: pointer;
  background: transparent;
  border: 0;
}

.downstream-item:not(:disabled):hover strong {
  color: var(--overview-blue);
}

.downstream-item__icon {
  display: grid;
  flex-shrink: 0;
  place-items: center;
  width: 28px;
  height: 28px;
  font-size: 17px;
  color: #547aff;
  background: var(--overview-tint);
  border-radius: 4px;
}

.downstream-item strong {
  flex-shrink: 0;
  width: 72px;
  font-size: 12px;
  font-weight: 500;
}

.downstream-item__description {
  font-size: 11px;
  line-height: 1.6;
  color: hsl(var(--muted-foreground));
}

.overview-bottom {
  display: grid;
  flex: 1;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr) minmax(0, 0.68fr);
  gap: 14px;
  min-height: 290px;
}

.distribution-panel,
.activity-panel {
  display: flex;
  flex-direction: column;
}

.distribution-body,
.activity-body {
  display: flex;
  flex: 1;
  flex-direction: column;
  padding: 18px;
}

.distribution-list {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 18px;
  justify-content: space-between;
}

.distribution-row__label {
  display: flex;
  gap: 12px;
  justify-content: space-between;
  margin-bottom: 8px;
  font-size: 12px;
}

.distribution-row__label > span:first-child {
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.distribution-row__label > span:last-child {
  flex-shrink: 0;
  font-variant-numeric: tabular-nums;
  color: hsl(var(--muted-foreground));
}

.distribution-row__label small {
  margin-left: 4px;
  font-size: 11px;
}

.distribution-track {
  height: 8px;
  overflow: hidden;
  background: color-mix(in srgb, hsl(var(--card)) 90%, #74b2f1);
  border-radius: 20px;
}

.distribution-track > span {
  display: block;
  height: 100%;
  background: linear-gradient(90deg, #6f8bff, #4966ff);
  border-radius: inherit;
}

.panel-empty {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 12px;
  align-items: center;
  justify-content: center;
  min-height: 190px;
  font-size: 12px;
  color: hsl(var(--muted-foreground));
  text-align: center;
}

.panel-empty > svg {
  width: 36px;
  height: 36px;
  color: #a6b7da;
}

.panel-empty > span {
  max-width: 270px;
  line-height: 1.8;
}

.activity-list {
  display: flex;
  flex-direction: column;
  gap: 15px;
  padding: 0;
  margin: 0;
  list-style: none;
}

.activity-list li {
  display: flex;
  gap: 8px;
  align-items: center;
  min-width: 0;
  font-size: 12px;
}

.activity-icon {
  display: grid;
  flex-shrink: 0;
  place-items: center;
  width: 22px;
  height: 22px;
  font-size: 14px;
  color: #77a6ee;
  background: #edf5ff;
  border-radius: 4px;
}

.activity-icon--publish {
  color: #5c78f4;
  background: #edf0ff;
}

.activity-title {
  flex: 1;
  overflow: hidden;
  text-overflow: ellipsis;
  white-space: nowrap;
}

.activity-title > span {
  color: hsl(var(--muted-foreground));
}

.activity-list time {
  flex-shrink: 0;
  padding: 1px 4px;
  font-size: 10px;
  color: hsl(var(--muted-foreground));
  background: hsl(var(--muted));
  border-radius: 2px;
}

button:focus-visible {
  outline: 2px solid var(--overview-blue);
  outline-offset: 3px;
}

.dark .overview-banner {
  background: linear-gradient(110deg, #1d304b, #22395a 70%, #253858);
}

.dark .overview-banner h1 {
  color: #d9e8fc;
}

.dark .overview-banner p {
  color: #a1b6ce;
}

.dark .overview-banner::after {
  background: #6691db0a;
}

.dark .refresh-button {
  color: #c1d5f6;
  background: #284a74;
}

.dark .process-step__icon {
  background: #314879;
}

.dark .activity-icon {
  background: #253c59;
}

@media (min-width: 1900px) {
  .overview-banner {
    min-height: 120px;
  }

  .process-step {
    min-height: 215px;
  }

  .downstream-content {
    min-height: 303px;
  }
}

@media (max-width: 1500px) {
  .metric-card {
    padding-right: 14px;
    padding-left: 14px;
  }

  .metric-card__icon {
    width: 36px;
    height: 36px;
    font-size: 23px;
  }

  .overview-middle {
    grid-template-columns: minmax(0, 1.5fr) minmax(0, 1fr);
  }

  .downstream-origin {
    width: 110px;
  }

  .downstream-origin .model-artwork {
    transform: scale(0.82);
  }

  .downstream-item {
    flex-wrap: wrap;
    gap: 5px 8px;
  }

  .downstream-item__description {
    width: calc(100% - 36px);
    margin-top: -3px;
    margin-left: 36px;
  }

  .downstream-list {
    gap: 6px;
  }

  .process-steps {
    gap: 20px;
  }

  .process-arrow {
    right: -18px;
    width: 15px;
  }

  .process-principles {
    flex-wrap: wrap;
  }

  .panel-heading__note {
    display: none;
  }
}

@media (max-width: 1100px) {
  .metric-grid {
    grid-template-columns: repeat(3, minmax(0, 1fr));
  }

  .overview-middle {
    grid-template-columns: 1fr;
  }

  .overview-bottom {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }

  .activity-panel {
    grid-column: 1 / -1;
  }

  .overview-banner__aside > span {
    display: none;
  }

  .overview-banner__artwork {
    right: 10%;
    opacity: 0.6;
  }

  .downstream-content {
    min-height: 250px;
  }

  .downstream-origin {
    width: 170px;
  }

  .downstream-origin .model-artwork {
    transform: none;
  }

  .downstream-item {
    flex-wrap: nowrap;
    gap: 10px;
  }

  .downstream-item__description {
    width: auto;
    margin: 0;
  }

  .downstream-list {
    gap: 8px;
  }
}

@media (max-width: 640px) {
  .model-home {
    gap: 12px;
  }

  .overview-banner {
    align-items: flex-start;
    min-height: 145px;
    padding: 20px;
  }

  .overview-banner__copy {
    padding-right: 0;
  }

  .overview-banner__copy p {
    max-width: 85%;
    font-size: 11px;
  }

  .overview-banner__artwork {
    right: -100px;
    opacity: 0.22;
  }

  .overview-banner__aside {
    position: absolute;
    right: 16px;
    bottom: 12px;
  }

  .metric-grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
    gap: 10px;
  }

  .metric-card {
    padding: 14px 12px 10px;
  }

  .metric-card__number strong {
    font-size: 28px;
  }

  .process-steps {
    grid-template-columns: 1fr;
    gap: 12px;
  }

  .process-step {
    align-items: flex-start;
    min-height: 116px;
    padding: 16px 18px 14px 94px;
  }

  .process-step__icon {
    position: absolute;
    top: 33px;
    left: 20px;
    width: 48px;
    height: 48px;
    font-size: 28px;
  }

  .process-step h3 {
    margin-bottom: 5px;
  }

  .process-step p {
    text-align: left;
  }

  .process-arrow {
    top: auto;
    right: calc(50% - 8px);
    bottom: -15px;
    z-index: 1;
    transform: rotate(90deg);
  }

  .process-principles {
    gap: 10px 16px;
    justify-content: flex-start;
  }

  .downstream-content {
    gap: 0;
    padding: 16px;
  }

  .downstream-origin {
    display: none;
  }

  .downstream-list {
    gap: 12px;
  }

  .downstream-item {
    align-items: flex-start;
  }

  .downstream-item strong {
    padding-top: 4px;
  }

  .overview-bottom {
    grid-template-columns: 1fr;
  }

  .activity-panel {
    grid-column: auto;
  }

  .distribution-list {
    gap: 20px;
  }
}

@media (prefers-reduced-motion: reduce) {
  .metric-card {
    transition: none;
  }
}
</style>
