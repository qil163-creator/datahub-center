<script lang="ts" setup>
import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { DatabaseMonitorApi } from '#/api/dataforge/database/monitor';

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { DICT_TYPE } from '@vben/constants';
import { getDictOptions } from '@vben/hooks';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Descriptions,
  DescriptionsItem,
  Empty,
  Skeleton,
  TabPane,
  Tabs,
  Tag,
} from 'antdv-next';

import { getDatabase } from '#/api/dataforge/database';
import {
  collectDatabaseMonitor,
  getDatabaseMonitorOverview,
  getDatabaseMonitorTrend,
} from '#/api/dataforge/database/monitor';
import { ConnectionIcon } from '#/components/connection-icon';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';

import { hasDatabaseMetadata } from '../connection-capabilities';
import { DATABASE_USAGE_OPTIONS } from '../data';
import { readNativeConnectionFields } from '../native-connection';
import { hasSchemaConcept } from '../schema-support';
import MonitorPanel from './monitor-panel.vue';
import RecordsPanel from './records-panel.vue';
import RulesPanel from './rules-panel.vue';

defineOptions({ name: 'DataforgeDatabaseDetail' });

const route = useRoute();
const router = useRouter();
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const databaseId = computed(() => parseResourceId(route.params.id));
const canQuery = computed(() => hasAccessByCodes(['dataforge:database:query']));
const sessionKey = computed(
  () => `${access.accessToken}/${access.refreshToken}`,
);
const detail = ref<FoundationDatabaseApi.Database>();
const supportsMetadata = computed(
  () => !!detail.value && hasDatabaseMetadata(detail.value),
);
const publicConfig = computed(() =>
  readNativeConnectionFields(detail.value?.extendConfig),
);
const overview = ref<DatabaseMonitorApi.Overview>();
const trend = ref<DatabaseMonitorApi.Trend>();
const activeTab = ref('monitor');
const days = ref(7);
const loading = ref(false);
const monitorLoading = ref(false);
const collecting = ref(false);
const error = ref('');
const monitorError = ref('');
const collectError = ref('');
const detailGuard = createLatestWins();
const monitorGuard = createLatestWins();
const collectGuard = createLatestWins();

const statusLabel = computed(() => {
  const value = detail.value?.status;
  if (value === undefined || value === null) return '—';
  return (
    getDictOptions(DICT_TYPE.COMMON_STATUS, 'number').find(
      (option) => option.value === value,
    )?.label ?? String(value)
  );
});
const usageLabel = computed(() => {
  const value = detail.value?.usageType;
  return (
    DATABASE_USAGE_OPTIONS.find((option) => option.value === value)?.label ??
    (value === undefined || value === null ? '—' : String(value))
  );
});
const columns = { xs: 1, sm: 2, lg: 3 };

function back() {
  void router.push('/develop/database');
}

async function loadMonitor() {
  const id = databaseId.value;
  if (!id || !detail.value || !canQuery.value || !supportsMetadata.value)
    return;
  const request = monitorGuard.next();
  monitorLoading.value = true;
  monitorError.value = '';
  try {
    const [nextOverview, nextTrend] = await Promise.all([
      getDatabaseMonitorOverview(id),
      getDatabaseMonitorTrend(id, days.value),
    ]);
    if (!request.isCurrent()) return;
    overview.value = nextOverview;
    trend.value = nextTrend;
  } catch {
    if (request.isCurrent()) {
      monitorError.value = '监控数据加载失败，请重试。';
    }
  } finally {
    if (request.isCurrent()) monitorLoading.value = false;
  }
}

async function loadDetail() {
  const request = detailGuard.next();
  monitorGuard.invalidate();
  collectGuard.invalidate();
  detail.value = undefined;
  overview.value = undefined;
  trend.value = undefined;
  error.value = '';
  monitorError.value = '';
  collectError.value = '';
  monitorLoading.value = false;
  collecting.value = false;
  const id = databaseId.value;
  loading.value = Boolean(id && canQuery.value);
  if (!id || !canQuery.value) return;
  try {
    const data = await getDatabase(id);
    if (!request.isCurrent()) return;
    detail.value = data;
    if (!supportsMetadata.value) activeTab.value = 'basic';
    if (data) {
      // 先展示已有监控数据，同时自动采集；完成后再刷新为最新结果。
      void loadMonitor();
      void collect();
    }
  } catch {
    if (request.isCurrent()) error.value = '连接详情加载失败，请重试。';
  } finally {
    if (request.isCurrent()) loading.value = false;
  }
}

async function collect() {
  const id = databaseId.value;
  if (
    !id ||
    collecting.value ||
    !canQuery.value ||
    !supportsMetadata.value ||
    detail.value?.status !== 0
  )
    return;
  const request = collectGuard.next();
  collecting.value = true;
  collectError.value = '';
  try {
    await collectDatabaseMonitor(id);
    if (request.isCurrent()) await loadMonitor();
  } catch {
    if (request.isCurrent()) {
      collectError.value = '监控数据采集失败，请点击“立即采集”重试。';
    }
  } finally {
    if (request.isCurrent()) collecting.value = false;
  }
}

function changeRange(value: number) {
  days.value = value;
  void loadMonitor();
}

watch(
  () => [databaseId.value, sessionKey.value, canQuery.value],
  () => {
    activeTab.value = 'monitor';
    days.value = 7;
    void loadDetail();
  },
  { immediate: true, flush: 'sync' },
);
onBeforeUnmount(() => {
  detailGuard.invalidate();
  monitorGuard.invalidate();
  collectGuard.invalidate();
});
</script>

<template>
  <Page content-class="database-detail-content">
    <div class="database-detail space-y-4">
      <div v-if="!databaseId || !canQuery" class="bg-card rounded-lg p-5">
        <Alert
          :message="!databaseId ? '无效的连接编号' : '暂无连接查询权限'"
          show-icon
          type="warning"
        />
        <DetailBackButton class="mt-4" @click="back" />
      </div>
      <template v-else>
        <section class="bg-card rounded-lg p-5" aria-label="连接信息">
          <div class="mb-5 flex flex-wrap items-center justify-between gap-3">
            <div class="flex min-w-0 items-center gap-3">
              <ConnectionIcon
                v-if="detail"
                :icon-path="detail.iconPath"
                :type-code="detail.typeCode || detail.productId"
                :alt="detail.databaseTypeName || '连接图标'"
                class="size-9 shrink-0"
              />
              <span
                class="bg-primary/10 text-primary rounded px-2 py-1 text-xs font-medium tabular-nums"
              >
                {{ databaseId }}
              </span>
              <h1 class="m-0 break-all text-lg font-semibold">
                {{ detail?.name || '连接详情' }}
              </h1>
              <Tag
                v-if="detail"
                :color="detail.status === 0 ? 'blue' : 'default'"
              >
                {{ statusLabel }}
              </Tag>
            </div>
            <DetailBackButton @click="back" />
          </div>
          <Skeleton v-if="loading" active :paragraph="{ rows: 4 }" />
          <Alert v-else-if="error" :message="error" show-icon type="error">
            <template #action>
              <Button size="small" @click="loadDetail">
                <template #icon>
                  <IconifyIcon icon="lucide:refresh-cw" />
</template>重试
              </Button>
            </template>
          </Alert>
          <Descriptions
            v-else-if="detail"
            bordered
            size="small"
            :column="columns"
          >
            <DescriptionsItem label="连接类型">
              {{
                detail.databaseTypeName ||
                detail.typeCode ||
                detail.databaseType ||
                '—'
              }}
            </DescriptionsItem>
            <DescriptionsItem label="IP地址">
              {{ detail.databaseIp || '—' }}
            </DescriptionsItem>
            <DescriptionsItem label="端口号">
              {{ detail.databasePort || '—' }}
            </DescriptionsItem>
            <DescriptionsItem label="所属单位" :span="3">
              {{ detail.orgName || detail.orgId || '—' }}
            </DescriptionsItem>
            <DescriptionsItem label="创建人">
              {{ detail.creatorName || '—' }}
            </DescriptionsItem>
            <DescriptionsItem label="创建时间">
              {{ detail.createTime ? formatDateTime(detail.createTime) : '—' }}
            </DescriptionsItem>
            <DescriptionsItem label="更新时间">
              {{ detail.updateTime ? formatDateTime(detail.updateTime) : '—' }}
            </DescriptionsItem>
            <DescriptionsItem label="备注" :span="3">
              {{ detail.remark || '—' }}
            </DescriptionsItem>
          </Descriptions>
          <Empty v-else description="连接不存在或已删除" />
        </section>

        <section
          v-if="detail"
          :key="`${sessionKey}/${detail.id}`"
          class="bg-card detail-tabs rounded-lg px-5 pb-5 pt-2"
          aria-label="连接详情"
        >
          <Tabs v-model:active-key="activeTab" :animated="false">
            <TabPane v-if="supportsMetadata" key="monitor" tab="运行监控">
              <Alert
                v-if="monitorError"
                class="mb-4"
                :message="monitorError"
                show-icon
                type="error"
              >
                <template #action>
                  <Button size="small" @click="loadMonitor">
                    <template #icon>
                      <IconifyIcon icon="lucide:refresh-cw" />
                    </template>
                    重试
                  </Button>
                </template>
              </Alert>
              <Alert
                v-if="collectError"
                class="mb-4"
                :message="collectError"
                show-icon
                type="warning"
              />
              <MonitorPanel
                :database="detail"
                :overview="overview"
                :trend="trend"
                :loading="monitorLoading"
                :collecting="collecting"
                @range-change="changeRange"
                @collect="collect"
              />
            </TabPane>
            <TabPane v-if="supportsMetadata" key="logs" tab="读写日志">
              <RecordsPanel :database-id="detail.id" mode="log" />
            </TabPane>
            <TabPane v-if="supportsMetadata" key="alarms" tab="告警记录">
              <RecordsPanel
                :database-id="detail.id"
                mode="alarm"
                @change="loadMonitor"
              />
            </TabPane>
            <TabPane v-if="supportsMetadata" key="rules" tab="告警规则">
              <RulesPanel :database-id="detail.id" @change="loadMonitor" />
            </TabPane>
            <TabPane key="basic" tab="基本信息">
              <Descriptions
                class="pt-1"
                :column="{ xs: 1, sm: 2 }"
                bordered
                size="small"
              >
                <DescriptionsItem label="账号">
                  {{ detail.userName || '—' }}
                </DescriptionsItem>
                <DescriptionsItem
                  v-if="
                    publicConfig.connectionEndpoint ||
                    publicConfig.connectionServers
                  "
                  label="服务地址"
                >
                  {{
                    publicConfig.connectionEndpoint ||
                    publicConfig.connectionServers
                  }}
                </DescriptionsItem>
                <DescriptionsItem label="数据库名称">
                  {{ detail.databaseName || '—' }}
                </DescriptionsItem>
                <DescriptionsItem
                  v-if="hasSchemaConcept(detail.typeCode)"
                  label="Schema"
                >
                  {{ detail.databaseSchema || '—' }}
                </DescriptionsItem>
                <DescriptionsItem label="用途分类">
                  {{ usageLabel }}
                </DescriptionsItem>
                <DescriptionsItem label="更新人">
                  {{ detail.updaterName || '—' }}
                </DescriptionsItem>
              </Descriptions>
            </TabPane>
          </Tabs>
        </section>
      </template>
    </div>
  </Page>
</template>

<style scoped>
.database-detail {
  min-width: 0;
}

.detail-tabs {
  min-height: 480px;
}

.database-detail :deep(.ant-descriptions-item-label) {
  width: 140px;
  color: hsl(var(--muted-foreground));
}

.database-detail :deep(.ant-descriptions-item-content) {
  overflow-wrap: anywhere;
}

.database-detail :deep(.ant-tabs-nav) {
  margin-bottom: 20px;
}

@media (width <= 640px) {
  .database-detail :deep(.ant-descriptions-item-label) {
    width: 100px;
  }
}
</style>
