<script setup lang="ts">
import type { SupplyApiLogApi } from '#/api/supply/apilog';

import { computed, onBeforeUnmount, ref, watch } from 'vue';
import { useRoute, useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useTabs } from '@vben/hooks';
import { useAccessStore, useUserStore } from '@vben/stores';

import { Alert, Button, Descriptions, DescriptionsItem, Tag } from 'antdv-next';

import { getApiLog } from '#/api/supply/apilog';
import DetailBackButton from '#/components/detail-back-button/index.vue';
import { createLatestWins, parseResourceId } from '#/utils/resource-id';
import { formatJsonText } from '#/views/governance/common/detail-helpers';

import { formatSupplyDateTime } from '../utils/format';
import { LOG_STATUS_OPTIONS, REJECT_STAGE_OPTIONS } from './data';

defineOptions({ name: 'SupplyApiLogDetailPage' });

const route = useRoute();
const router = useRouter();
const { closeCurrentTab } = useTabs();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const id = computed(() => parseResourceId(route.params.id));
const canQuery = computed(() => hasAccessByCodes(['supply:api-log:query']));
const detail = ref<SupplyApiLogApi.ApiLog>();
const loading = ref(false);
const error = ref('');
const guard = createLatestWins();
let active = true;

const status = computed(() =>
  LOG_STATUS_OPTIONS.find((item) => item.value === detail.value?.status),
);
const rejectStage = computed(
  () =>
    REJECT_STAGE_OPTIONS.find(
      (item) => item.value === detail.value?.rejectStage,
    )?.label ||
    detail.value?.rejectStage ||
    '-',
);
const duration = computed(() => {
  const value = detail.value?.durationMs;
  return value === undefined || value === null ? '-' : String(value / 1000);
});

function resetDetail() {
  guard.invalidate();
  detail.value = undefined;
  loading.value = false;
  error.value = '';
}

async function loadDetail() {
  resetDetail();
  if (!canQuery.value) {
    error.value = '无调用记录查询权限';
    return;
  }
  const logId = id.value;
  if (!logId) {
    error.value = '无效的调用记录编号';
    return;
  }
  const ticket = guard.next();
  const isCurrent = () =>
    active && ticket.isCurrent() && canQuery.value && id.value === logId;
  loading.value = true;
  try {
    const row = await getApiLog(logId);
    if (!isCurrent()) return;
    if (parseResourceId(row?.id) !== logId) {
      error.value = '调用记录身份不匹配，请返回列表后重新打开';
      return;
    }
    detail.value = row;
  } catch {
    if (isCurrent()) error.value = '调用记录详情加载失败，请重试';
  } finally {
    if (isCurrent()) loading.value = false;
  }
}

async function handleBack() {
  const current = router.currentRoute.value;
  await router.push('/supply/api-log');
  await closeCurrentTab(current);
}

// 路由、登录身份或权限变化时立即清空详情并使旧请求失效。
watch(
  () => [
    id.value,
    access.accessToken,
    access.refreshToken,
    user.userInfo?.userId,
    access.accessCodes.join('\0'),
    canQuery.value,
  ],
  () => void loadDetail(),
  { immediate: true, flush: 'sync' },
);
onBeforeUnmount(() => {
  active = false;
  resetDetail();
});
</script>

<template>
  <Page auto-content-height content-class="apilog-detail-content">
    <div class="apilog-profile" :aria-busy="loading">
      <section class="apilog-profile__summary" aria-label="调用记录概要">
        <div class="apilog-profile__head">
          <div class="apilog-profile__identity">
            <span v-if="detail" class="apilog-profile__id">{{
              detail.id
            }}</span>
            <h1 class="apilog-profile__name">
              {{ detail?.apiName || '调用记录详情' }}
            </h1>
            <Tag v-if="detail" :color="status?.color">
              {{ status?.label ?? detail.status ?? '-' }}
            </Tag>
            <Tag v-if="detail?.method" color="blue">{{ detail.method }}</Tag>
          </div>
          <DetailBackButton @click="handleBack" />
        </div>
        <Alert v-if="error" type="error" :message="error" show-icon>
          <template v-if="id && canQuery" #action>
            <Button size="small" @click="loadDetail">重试</Button>
          </template>
        </Alert>
        <p v-if="loading" role="status">正在加载调用记录详情…</p>
        <Descriptions
          v-if="detail"
          class="apilog-profile__info"
          :column="{ xs: 1, sm: 1, md: 1, lg: 2, xl: 3, xxl: 3 }"
          bordered
          size="small"
        >
          <DescriptionsItem label="API服务名称">
            {{ detail.apiName || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="请求方式">
            <Tag v-if="detail.method" color="blue">{{ detail.method }}</Tag>
            <span v-else>-</span>
          </DescriptionsItem>
          <DescriptionsItem label="调用者IP">
            {{ detail.callerIp || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="调用时间">
            {{ formatSupplyDateTime(detail.callTime) || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="调用耗时(秒)">
            {{ duration }}
          </DescriptionsItem>
          <DescriptionsItem label="调用数据量">
            {{ detail.dataCount ?? '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="追踪编号">
            {{ detail.traceId || '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="拒绝阶段">
            {{ rejectStage }}
          </DescriptionsItem>
          <DescriptionsItem label="发布快照">
            {{ detail.releaseId ?? '-' }}
          </DescriptionsItem>
          <DescriptionsItem label="调用接口地址" span="filled">
            <span class="font-mono">{{ detail.callPath || '-' }}</span>
          </DescriptionsItem>
        </Descriptions>
      </section>

      <section
        v-if="detail"
        class="apilog-profile__attributes"
        aria-label="调用记录详细信息"
      >
        <div class="apilog-profile__section-head">
          <h2>详细信息</h2>
        </div>
        <Descriptions
          class="apilog-profile__info apilog-profile__details"
          :column="1"
          bordered
          size="small"
        >
          <DescriptionsItem label="参数名称与配置摘要">
            <pre class="apilog-profile__text font-mono">{{
              formatJsonText(detail.paramsJson) || '-'
            }}</pre>
          </DescriptionsItem>
          <DescriptionsItem label="异常信息记录">
            <pre class="apilog-profile__text">{{
              detail.errorInfo || '-'
            }}</pre>
          </DescriptionsItem>
        </Descriptions>
      </section>
    </div>
  </Page>
</template>

<style scoped>
:deep(.apilog-detail-content) {
  display: flex;
  flex-direction: column;
}

.apilog-profile {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  min-height: 0;
}

.apilog-profile__summary,
.apilog-profile__attributes {
  padding: 20px;
  background: hsl(var(--card));
  border-radius: var(--radius);
}

.apilog-profile__summary {
  flex-shrink: 0;
}

.apilog-profile__attributes {
  flex: 1 0 auto;
  min-width: 0;
  padding-top: 8px;
}

.apilog-profile__head,
.apilog-profile__identity {
  display: flex;
  flex-wrap: wrap;
  gap: 12px;
  align-items: center;
}

.apilog-profile__head {
  justify-content: space-between;
  margin-bottom: 20px;
}

.apilog-profile__identity {
  min-width: 0;
}

.apilog-profile__id {
  padding: 4px 8px;
  font-size: 12px;
  font-weight: 500;
  font-variant-numeric: tabular-nums;
  color: hsl(var(--primary));
  overflow-wrap: anywhere;
  background: hsl(var(--primary) / 10%);
  border-radius: calc(var(--radius) - 4px);
}

.apilog-profile__name {
  margin: 0;
  font-size: 18px;
  font-weight: 600;
  overflow-wrap: anywhere;
}

.apilog-profile__info :deep(.ant-descriptions-item-label) {
  width: 140px;
  color: hsl(var(--muted-foreground));
}

.apilog-profile__info :deep(.ant-descriptions-item-content) {
  min-width: 0;
  overflow-wrap: anywhere;
}

.apilog-profile__section-head {
  margin-bottom: 20px;
  border-bottom: 1px solid hsl(var(--border));
}

.apilog-profile__section-head h2 {
  display: inline-block;
  padding: 12px 0;
  margin: 0 0 -1px;
  font-size: 14px;
  font-weight: 500;
  color: hsl(var(--primary));
  border-bottom: 2px solid hsl(var(--primary));
}

.apilog-profile__text {
  max-width: 100%;
  margin: 0;
  font-family: inherit;
  font-size: inherit;
  overflow-wrap: anywhere;
  white-space: pre-wrap;
}

@media (width <= 640px) {
  .apilog-profile__summary,
  .apilog-profile__attributes {
    padding: 12px;
  }

  .apilog-profile__head {
    align-items: flex-start;
  }

  .apilog-profile__info :deep(.ant-descriptions-item-label) {
    width: 100px;
  }
}
</style>
