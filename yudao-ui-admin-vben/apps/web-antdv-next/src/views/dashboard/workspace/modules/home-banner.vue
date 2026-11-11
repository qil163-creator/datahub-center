<script lang="ts" setup>
import type { RouteTarget } from '../../shared/route-resolver';

import { IconifyIcon } from '@vben/icons';

import { Avatar, Tooltip } from 'antdv-next';

import OverviewArtwork from '../../shared/module-overview/overview-artwork.vue';
import { formatCount } from '../../shared/utils';

/** 首页横幅：问候 + 部门角色 + 个人待办统计，视觉与各模块概览页横幅保持一致 */
defineOptions({ name: 'WorkspaceHomeBanner' });

defineProps<{
  avatar?: string;
  description: string;
  loading: boolean;
  stats: {
    key: string;
    label: string;
    target: RouteTarget;
    /** undefined 表示不可用 */
    value?: number;
  }[];
  title: string;
}>();

const emit = defineEmits<{
  navigate: [target: RouteTarget];
  refresh: [];
}>();
</script>

<template>
  <section class="home-banner" aria-labelledby="home-banner-title">
    <div class="home-banner__main">
      <Avatar :size="64" :src="avatar" class="home-banner__avatar shrink-0" />
      <div class="min-w-0">
        <h1 id="home-banner-title" class="home-banner__title">{{ title }}</h1>
        <p class="home-banner__desc">{{ description }}</p>
      </div>
    </div>

    <OverviewArtwork
      :icons="['lucide:database', 'lucide:chart-no-axes-combined']"
      class="home-banner__artwork"
    />

    <div class="home-banner__aside">
      <button
        v-for="stat in stats"
        :key="stat.key"
        type="button"
        class="home-banner__stat"
        @click="emit('navigate', stat.target)"
      >
        <span class="home-banner__stat-value">{{ formatCount(stat.value) }}</span>
        <span class="home-banner__stat-label">{{ stat.label }}</span>
      </button>
      <Tooltip title="刷新看板">
        <button
          type="button"
          class="home-banner__refresh"
          :disabled="loading"
          aria-label="刷新看板"
          @click="emit('refresh')"
        >
          <IconifyIcon
            icon="lucide:refresh-cw"
            class="size-4"
            :class="{ 'animate-spin': loading }"
          />
        </button>
      </Tooltip>
    </div>
  </section>
</template>

<style scoped>
.home-banner {
  position: relative;
  display: flex;
  gap: 24px;
  align-items: center;
  justify-content: space-between;
  min-height: 132px;
  padding: 24px 32px;
  overflow: hidden;
  background: linear-gradient(
    108deg,
    #e8f3ff 0%,
    #e5f1ff 45%,
    #d9eaff 77%,
    #eaf4ff 100%
  );
  border-radius: 8px;
}

.home-banner::after {
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

.home-banner__main {
  position: relative;
  z-index: 1;
  display: flex;
  gap: 18px;
  align-items: center;
  min-width: 0;
}

.home-banner__avatar {
  border: 3px solid #ffffffb3;
  box-shadow: 0 6px 16px #4966ff1f;
}

.home-banner__title {
  margin: 0 0 8px;
  font-size: clamp(18px, 1.4vw, 22px);
  font-weight: 600;
  color: #2e465c;
  letter-spacing: 0.02em;
}

.home-banner__desc {
  margin: 0;
  font-size: 13px;
  line-height: 1.7;
  color: #677e94;
}

.home-banner__artwork {
  position: absolute;
  top: -24px;
  right: 400px;
}

.home-banner__aside {
  position: relative;
  z-index: 1;
  display: flex;
  flex-shrink: 0;
  gap: 8px;
  align-items: center;
}

.home-banner__stat {
  display: flex;
  flex-direction: column;
  gap: 4px;
  align-items: center;
  min-width: 84px;
  padding: 8px 12px;
  cursor: pointer;
  background: transparent;
  border: none;
  border-radius: 8px;
  transition: background-color 0.2s;
}

.home-banner__stat:hover {
  background: #ffffff8c;
}

.home-banner__stat-value {
  font-size: 24px;
  font-weight: 600;
  font-variant-numeric: tabular-nums;
  line-height: 1.1;
  color: #2e465c;
}

.home-banner__stat-label {
  font-size: 12px;
  color: #677e94;
}

.home-banner__refresh {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  width: 34px;
  height: 34px;
  margin-left: 8px;
  color: #5775bf;
  cursor: pointer;
  background: #ffffffa6;
  border: 1px solid #adc9f559;
  border-radius: 50%;
  transition: background-color 0.2s;
}

.home-banner__refresh:hover:not(:disabled) {
  background: #fff;
}

.home-banner__refresh:disabled {
  cursor: default;
}

.home-banner__stat:focus-visible,
.home-banner__refresh:focus-visible {
  outline: 2px solid #4966ff;
  outline-offset: 2px;
}

.dark .home-banner {
  background: linear-gradient(110deg, #1d304b, #22395a 70%, #253858);
}

.dark .home-banner::after {
  background: #6691db0a;
}

.dark .home-banner__title,
.dark .home-banner__stat-value {
  color: #d9e8fc;
}

.dark .home-banner__desc,
.dark .home-banner__stat-label {
  color: #a1b6ce;
}

.dark .home-banner__avatar {
  border-color: #ffffff26;
}

.dark .home-banner__stat:hover {
  background: #ffffff14;
}

.dark .home-banner__refresh {
  color: #c1d5f6;
  background: #284a74;
  border-color: transparent;
}

@media (max-width: 1440px) {
  .home-banner__artwork {
    right: 340px;
    opacity: 0.55;
  }
}

@media (max-width: 1200px) {
  .home-banner__artwork {
    display: none;
  }
}

@media (max-width: 768px) {
  .home-banner {
    flex-direction: column;
    align-items: flex-start;
    padding: 20px;
  }

  .home-banner__aside {
    align-self: stretch;
    justify-content: space-between;
  }

  .home-banner__stat {
    min-width: 0;
    padding: 6px 8px;
  }
}
</style>
