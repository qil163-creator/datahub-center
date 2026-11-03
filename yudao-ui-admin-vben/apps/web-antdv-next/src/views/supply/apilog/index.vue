<script lang="ts" setup>
import { computed, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { Page } from '@vben/common-ui';
import { useAccessStore } from '@vben/stores';

import { Empty, Tabs } from 'antdv-next';

import CallLog from './modules/call-log.vue';
import WriteOperation from './modules/write-operation.vue';

defineOptions({ name: 'SupplyApiLog' });

const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const sessionKey = computed(
  () => `${access.accessToken}/${access.refreshToken}`,
);
const canQueryLogs = computed(() => hasAccessByCodes(['supply:api-log:query']));
const canQueryOperations = computed(() =>
  hasAccessByCodes(['supply:data-api:operation-query']),
);
const tabItems = computed(() => [
  ...(canQueryLogs.value ? [{ key: 'logs', label: '调用日志' }] : []),
  ...(canQueryOperations.value
    ? [{ key: 'operations', label: '写操作台账' }]
    : []),
]);
const activeTab = ref('');

watch(
  tabItems,
  (items) => {
    if (!items.some((item) => item.key === activeTab.value)) {
      activeTab.value = items[0]?.key ?? '';
    }
  },
  { immediate: true },
);
</script>

<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col overflow-hidden"
  >
    <div
      v-if="tabItems.length > 0"
      class="flex min-h-0 flex-1 flex-col overflow-hidden"
    >
      <Tabs
        v-model:active-key="activeTab"
        :items="tabItems"
        :destroy-on-hidden="true"
        class="record-tabs"
      >
        <template #contentRender="{ item }">
          <CallLog
            v-if="canQueryLogs && item.key === 'logs' && activeTab === 'logs'"
            :key="sessionKey"
          />
          <WriteOperation
            v-else-if="
              canQueryOperations &&
              item.key === 'operations' &&
              activeTab === 'operations'
            "
          />
        </template>
      </Tabs>
    </div>
    <Empty v-else description="暂无调用记录查询权限" />
  </Page>
</template>

<style scoped>
.record-tabs {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}

.record-tabs :deep(.ant-tabs-nav) {
  flex: none;
  padding: 0 16px;
  margin-bottom: 16px;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.record-tabs :deep(.ant-tabs-body-holder),
.record-tabs :deep(.ant-tabs-body),
.record-tabs :deep(.ant-tabs-content-active) {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 0;
  overflow: hidden;
}
</style>
