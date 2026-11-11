<script lang="ts" setup>
import type { WorkspaceData } from '../shared/data';
import type { RouteTarget } from '../shared/route-resolver';

import { computed, onMounted, ref } from 'vue';

import { preferences } from '@vben/preferences';
import { useUserStore } from '@vben/stores';
import { formatAxis, formatDate } from '@vben/utils';

import { loadWorkspaceData } from '../shared/data';
import { DASHBOARD_TARGETS, useDashboardNav } from '../shared/route-resolver';
import { todayText } from '../shared/utils';
import DataChain from './modules/data-chain.vue';
import HealthMetrics from './modules/health-metrics.vue';
import HomeBanner from './modules/home-banner.vue';
import NoticePanel from './modules/notice-panel.vue';
import QuickNav from './modules/quick-nav.vue';
import RecentRuns from './modules/recent-runs.vue';
import TodoPanel from './modules/todo-panel.vue';
import TrendPanel from './modules/trend-panel.vue';

/**
 * 首页：面向当前用户的“今天要做什么”。
 * 横幅（问候 + 个人待办数）→ 左：数据全链路总览 / 近 7 天趋势 / 今日运行概况；
 * 右：快捷入口 / 待办事项 / 最近运行动态 / 系统公告。
 * 所有数据来自各业务域已有接口，无权限或服务未启动时对应卡片降级为 “--” / 空态。
 */
defineOptions({ name: 'DashboardWorkspace' });

const userStore = useUserStore();
const { navTo, resolve } = useDashboardNav();

const loading = ref(true);
const updatedAt = ref('');
const data = ref<WorkspaceData>({
  stages: [],
  health: [],
  todo: { items: [] },
  runs: { available: true, items: [] },
});

/** 快捷入口：只保留当前用户菜单里真实存在的页面 */
const QUICK_NAV_CANDIDATES: {
  color: string;
  icon: string;
  target: RouteTarget;
  title: string;
}[] = [
  {
    color: '#2f7df6',
    icon: 'carbon:ibm-cloud-databases',
    target: DASHBOARD_TARGETS.database,
    title: '数据源管理',
  },
  {
    color: '#0ea5e9',
    icon: 'carbon:data-share',
    target: DASHBOARD_TARGETS.access,
    title: '数据同步',
  },
  {
    color: '#7c3aed',
    icon: 'carbon:code',
    target: DASHBOARD_TARGETS.devWorkbench,
    title: '开发工作台',
  },
  {
    color: '#14b8a6',
    icon: 'carbon:catalog',
    target: DASHBOARD_TARGETS.metaObject,
    title: '元数据管理',
  },
  {
    color: '#f59e0b',
    icon: 'carbon:white-paper',
    target: DASHBOARD_TARGETS.standardElement,
    title: '标准数据元',
  },
  {
    color: '#d03050',
    icon: 'carbon:top-data-sets',
    target: DASHBOARD_TARGETS.qualityRule,
    title: '质量规则',
  },
  {
    color: '#f97316',
    icon: 'carbon:carbon-for-ibm-product',
    target: DASHBOARD_TARGETS.dataAsset,
    title: '数据资产',
  },
  {
    color: '#18a058',
    icon: 'carbon:api-1',
    target: DASHBOARD_TARGETS.dataApi,
    title: 'API 管理',
  },
  {
    color: '#64748b',
    icon: 'carbon:search-locate',
    target: DASHBOARD_TARGETS.dataQuery,
    title: '数据查询',
  },
  {
    color: '#ec4899',
    icon: 'carbon:dashboard',
    target: DASHBOARD_TARGETS.goView,
    title: '大屏设计器',
  },
  {
    color: '#8b5cf6',
    icon: 'carbon:flow',
    target: DASHBOARD_TARGETS.etlPipeline,
    title: '可视化 ETL',
  },
];

const quickNavItems = computed(() =>
  QUICK_NAV_CANDIDATES.flatMap((item) => {
    const url = resolve(item.target);
    return url
      ? [{ color: item.color, icon: item.icon, title: item.title, url }]
      : [];
  }).slice(0, 12),
);

const bannerTitle = computed(() => {
  const nickname =
    userStore.userInfo?.nickname || userStore.userInfo?.username || '';
  return `${formatAxis(new Date())}，${nickname}，欢迎使用数据中台`;
});

/** 头部描述：日期 · 部门 · 角色 */
const description = computed(() => {
  const parts = [`今天是 ${todayText()}`];
  const profile = data.value.profile;
  if (profile?.dept?.name) {
    parts.push(profile.dept.name);
  }
  const roleNames = (profile?.roles ?? [])
    .map((role: { name?: string }) => role.name)
    .filter(Boolean);
  if (roleNames.length > 0) {
    parts.push(roleNames.slice(0, 3).join(' / '));
  }
  return parts.join(' · ');
});

/** 头部右侧统计：待办审批 / 待处理工单 / 未读消息 */
const headerStats = computed(() => [
  {
    key: 'approval',
    label: '待办审批',
    value: data.value.todo.approvalCount,
    target: DASHBOARD_TARGETS.bpmTodo,
  },
  {
    key: 'workOrder',
    label: '待处理工单',
    value: data.value.todo.workOrderCount,
    target: DASHBOARD_TARGETS.qualityWorkOrder,
  },
  {
    key: 'unread',
    label: '未读消息',
    value: data.value.todo.unreadCount,
    target: DASHBOARD_TARGETS.myNotify,
  },
]);

async function loadData() {
  loading.value = true;
  try {
    data.value = await loadWorkspaceData();
    updatedAt.value = formatDate(new Date(), 'HH:mm');
  } finally {
    loading.value = false;
  }
}

onMounted(loadData);
</script>

<template>
  <div class="flex flex-col gap-5 p-5">
    <HomeBanner
      :avatar="userStore.userInfo?.avatar || preferences.app.defaultAvatar"
      :description="description"
      :loading="loading"
      :stats="headerStats"
      :title="bannerTitle"
      @navigate="navTo"
      @refresh="loadData"
    />

    <div class="flex flex-col gap-5 lg:flex-row">
      <div class="flex w-full min-w-0 flex-col gap-5 lg:w-3/5">
        <DataChain
          :loading="loading"
          :stages="data.stages"
          @navigate="navTo"
        />
        <TrendPanel
          :api-stats="data.apiStats"
          :loading="loading"
          :work-order-stats="data.workOrderStats"
          @navigate="navTo"
        />
        <HealthMetrics
          :loading="loading"
          :metrics="data.health"
          :updated-at="updatedAt"
          @navigate="navTo"
        />
      </div>
      <div class="flex w-full min-w-0 flex-col gap-5 lg:w-2/5">
        <QuickNav
          v-if="quickNavItems.length > 0"
          :items="quickNavItems"
          @navigate="navTo"
        />
        <TodoPanel :loading="loading" :todo="data.todo" @navigate="navTo" />
        <RecentRuns
          :available="data.runs.available"
          :items="data.runs.items"
          :loading="loading"
          @navigate="navTo"
        />
        <NoticePanel
          v-if="data.notices !== undefined || loading"
          :loading="loading"
          :notices="data.notices ?? []"
        />
      </div>
    </div>
  </div>
</template>
