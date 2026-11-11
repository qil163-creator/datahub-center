<script lang="ts" setup>
import type {
  ModuleOverviewConfig,
  ModuleOverviewData,
  OverviewDistributionRow,
  OverviewMetricDefinition,
} from './types';

import { computed } from 'vue';

import { Page } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { formatDateTime } from '@vben/utils';

import { Button, Skeleton } from 'antdv-next';

import { useDashboardNav } from '../route-resolver';
import OverviewArtwork from './overview-artwork.vue';
import {
  clampRate,
  displayCount,
  formatRate,
  relativeTime,
  sharePercent,
  sumCounts,
} from './values';

/**
 * 模块概览通用布局：横幅 → 核心指标 → 主流程 / 下游支撑 → 两个分布面板 + 最近动态。
 * 视觉与建模概览一致；页面只负责声明静态配置并提供聚合数据。
 */
const props = defineProps<{
  config: ModuleOverviewConfig;
  data?: ModuleOverviewData;
  failed?: boolean;
  loading?: boolean;
}>();

const emit = defineEmits<{ refresh: [] }>();

const { navTo, resolve } = useDashboardNav();

const UNAVAILABLE = '暂无权限或服务不可用';

const metrics = computed(() =>
  props.config.metrics.map((definition: OverviewMetricDefinition) => {
    const metric = props.data?.metrics[definition.key];
    const available =
      metric !== undefined &&
      (metric.display !== undefined || metric.value !== undefined);
    return {
      ...definition,
      metric,
      available,
      // 已格式化的数值自带单位；计数只有真实可用时才展示单位
      showUnit:
        metric?.display === undefined &&
        metric?.value !== undefined &&
        metric.value !== null,
      path: resolve(definition.target),
    };
  }),
);

function metricHint(available: boolean, hint?: string) {
  if (props.failed) return '加载失败，请重试';
  if (props.loading) return '正在加载统计';
  if (!available) return UNAVAILABLE;
  return hint ?? '';
}

const distributions = computed(() =>
  props.config.distributions.map((definition) => {
    const value = props.data?.distributions[definition.key];
    const rows = value?.rows ?? [];
    const mode = definition.mode ?? 'share';
    const total = sumCounts(rows);
    const isEmpty =
      rows.length === 0 ||
      (mode === 'share'
        ? total === '0'
        : rows.every((row) => row.rate === null || row.rate === undefined));
    return {
      ...definition,
      mode,
      rows,
      total,
      available: value !== undefined,
      isEmpty,
    };
  }),
);

function rowPercent(
  row: OverviewDistributionRow,
  mode: 'rate' | 'share',
  total: string,
) {
  return mode === 'rate' ? clampRate(row.rate) : sharePercent(row.count, total);
}

const activityPath = computed(() => resolve(props.config.activity.target));
const activities = computed(() => props.data?.activity?.items ?? []);
const now = computed(() => props.data?.generatedAt ?? Date.now());
</script>

<template>
  <Page auto-content-height content-class="bg-background-deep">
    <div class="module-overview" :aria-busy="loading">
      <section class="overview-banner" aria-labelledby="overview-title">
        <div class="overview-banner__copy">
          <h1 id="overview-title">{{ config.banner.title }}</h1>
          <p>{{ config.banner.description }}</p>
        </div>
        <OverviewArtwork
          :icons="config.banner.icons"
          class="overview-banner__artwork"
        />
        <div class="overview-banner__aside">
          <span>{{ config.banner.slogan }}</span>
          <Button
            :loading="loading"
            class="refresh-button"
            size="small"
            @click="emit('refresh')"
          >
            <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template>
            刷新概览
          </Button>
        </div>
      </section>

      <section class="metric-grid" :aria-label="`${config.name}核心指标`">
        <button
          v-for="item in metrics"
          :key="item.key"
          :disabled="!item.path || loading"
          :class="{ 'metric-card--link': item.path }"
          class="metric-card"
          type="button"
          @click="navTo(item.path)"
        >
          <div class="metric-card__body">
            <div class="metric-card__summary">
              <span class="metric-card__label">{{ item.label }}</span>
              <Skeleton
                v-if="loading"
                active
                :paragraph="false"
                :title="{ width: 72 }"
              />
              <div v-else class="metric-card__number">
                <strong>{{
                  item.metric?.display ?? displayCount(item.metric?.value)
                }}</strong><span v-if="item.showUnit">{{ item.unit ?? '个' }}</span>
              </div>
            </div>
            <span
              class="metric-card__icon"
              :class="`metric-card__icon--${item.color}`"
              ><IconifyIcon :icon="item.icon" /></span>
          </div>
          <div class="metric-card__footer">
            <span :title="metricHint(item.available, item.metric?.hint)">{{
              metricHint(item.available, item.metric?.hint)
            }}</span><IconifyIcon v-if="item.path" icon="lucide:arrow-up-right" />
          </div>
        </button>
      </section>

      <div class="overview-middle">
        <section
          class="overview-panel process-panel"
          aria-labelledby="process-title"
        >
          <header class="panel-heading">
            <h2 id="process-title">{{ config.process.title }}</h2>
            <span class="panel-heading__note">{{ config.process.note }}</span>
          </header>
          <div class="process-steps">
            <div
              v-for="(step, index) in config.process.steps"
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
                v-if="index < config.process.steps.length - 1"
                class="process-arrow"
                icon="lucide:move-right"
              />
            </div>
          </div>
          <div class="process-principles">
            <span v-for="step in config.process.steps" :key="step.title"><IconifyIcon :icon="step.icon" />{{ step.principle }}</span>
          </div>
        </section>

        <section
          class="overview-panel downstream-panel"
          aria-labelledby="downstream-title"
        >
          <header class="panel-heading">
            <h2 id="downstream-title">{{ config.downstream.title }}</h2>
          </header>
          <div class="downstream-content">
            <div class="downstream-origin">
              <OverviewArtwork compact /><span>{{ config.downstream.origin }}</span>
            </div>
            <div class="downstream-list">
              <button
                v-for="item in config.downstream.items"
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
            <span
              v-if="distribution.mode === 'share'"
              class="panel-heading__total"
              >总数
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
            <div v-else-if="failed" class="panel-empty">
              <IconifyIcon icon="lucide:cloud-off" /><span>加载失败，请点击“刷新概览”重试</span>
            </div>
            <div
              v-else-if="!distribution.available || distribution.isEmpty"
              class="panel-empty"
            >
              <IconifyIcon
                :icon="
                  distribution.available
                    ? 'lucide:chart-bar'
                    : 'lucide:lock-keyhole'
                "
              />
              <span>{{
                distribution.available ? distribution.empty : UNAVAILABLE
              }}</span>
              <Button
                v-if="
                  distribution.available &&
                  distribution.linkText &&
                  resolve(distribution.target)
                "
                type="link"
                size="small"
                @click="navTo(distribution.target)"
              >
                {{ distribution.linkText }}
                <IconifyIcon icon="lucide:arrow-right" />
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
                      / {{ row.code }}</template></span><span v-if="distribution.mode === 'rate'">{{
                    formatRate(row.rate)
                  }}</span><span v-else>{{ displayCount(row.count) }}
                    <small>({{
                        sharePercent(row.count, distribution.total).toFixed(1)
                      }}%)</small></span>
                </div>
                <div
                  class="distribution-track"
                  :class="{
                    'distribution-track--rate': distribution.mode === 'rate',
                  }"
                  role="meter"
                  :aria-label="row.name"
                  :aria-valuenow="
                    rowPercent(row, distribution.mode, distribution.total)
                  "
                  :aria-valuemin="0"
                  :aria-valuemax="100"
                >
                  <span
                    :style="{
                      width: `${rowPercent(row, distribution.mode, distribution.total)}%`,
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
            <h2 id="activity-title">{{ config.activity.title }}</h2>
            <Button
              v-if="activityPath && config.activity.linkText"
              class="panel-heading__link"
              type="link"
              size="small"
              @click="navTo(activityPath)"
            >
              {{ config.activity.linkText
              }}<IconifyIcon icon="lucide:chevron-right" />
            </Button>
          </header>
          <div class="activity-body">
            <Skeleton
              v-if="loading"
              active
              :paragraph="{ rows: 8 }"
              :title="false"
            />
            <div v-else-if="failed" class="panel-empty">
              <IconifyIcon icon="lucide:cloud-off" /><span>加载失败，请点击“刷新概览”重试</span>
            </div>
            <div
              v-else-if="!data?.activity || activities.length === 0"
              class="panel-empty"
            >
              <IconifyIcon icon="lucide:history" /><span>{{
                data?.activity ? config.activity.empty : UNAVAILABLE
              }}</span>
            </div>
            <ol v-else class="activity-list">
              <li v-for="activity in activities" :key="activity.id">
                <span
                  class="activity-icon"
                  :class="`activity-icon--${activity.tone ?? 'default'}`"
                  ><IconifyIcon :icon="activity.icon" /></span>
                <span class="activity-title" :title="activity.title">
                  {{ activity.title }}</span>
                <time
                  v-if="activity.time"
                  :datetime="new Date(activity.time).toISOString()"
                  :title="formatDateTime(activity.time)"
                  >{{ relativeTime(activity.time, now) }}</time>
                <span v-else-if="activity.tag" class="activity-tag">{{
                  activity.tag
                }}</span>
              </li>
            </ol>
          </div>
        </section>
      </div>
    </div>
  </Page>
</template>

<style scoped>
.module-overview {
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

.metric-card__icon--green {
  background: linear-gradient(145deg, #3fae7c, #86d4a8);
}

.metric-card__icon--rose {
  background: linear-gradient(145deg, #e86a7c, #f5a3ae);
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

.distribution-track--rate > span {
  background: linear-gradient(90deg, #6fd0c4, #2fa89a);
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

.activity-icon--processing {
  color: #5c78f4;
  background: #edf0ff;
}

.activity-icon--success {
  color: #2f9e6e;
  background: #e8f7ef;
}

.activity-icon--error {
  color: #d9534f;
  background: #fdeeee;
}

.activity-icon--warning {
  color: #d48806;
  background: #fff6e6;
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

.activity-list time,
.activity-tag {
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

.dark .activity-icon--success {
  background: #1f3d33;
}

.dark .activity-icon--error {
  background: #482a2c;
}

.dark .activity-icon--warning {
  background: #45391f;
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
  .module-overview {
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
