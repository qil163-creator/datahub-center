<script lang="ts" setup>
import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { ref, watch } from 'vue';

import { Alert, Empty, Modal, Spin, Table, Tag } from 'antdv-next';

import { getIndicatorImpact } from '#/api/modeling/indicator-model';
import { createLatestWins } from '#/utils/resource-id';

/** 指标口径变更影响分析：修改、停用前查看会随之变化的下游。 */
defineOptions({ name: 'IndicatorImpactModal' });

const props = defineProps<{ indicatorId?: IndicatorModelApi.Id }>();
const open = defineModel<boolean>('open', { default: false });

const TYPE: Record<string, string> = {
  ATOMIC: '原子',
  COMPOSITE: '复合',
  DERIVED: '派生',
};
const impact = ref<IndicatorModelApi.Impact>();
const loading = ref(false);
const guard = createLatestWins();

const dependentColumns = [
  { title: '层级', dataIndex: 'depth', key: 'depth', width: 70 },
  { title: '类型', key: 'type', width: 80 },
  { title: '指标', key: 'name' },
  { title: '状态', dataIndex: 'status', key: 'status', width: 90 },
];

watch(
  () => [open.value, props.indicatorId] as const,
  async ([visible, id]) => {
    const ticket = guard.next();
    if (!visible || id === undefined) {
      impact.value = undefined;
      loading.value = false;
      return;
    }
    loading.value = true;
    try {
      const value = await getIndicatorImpact(id);
      if (ticket.isCurrent()) impact.value = value;
    } finally {
      if (ticket.isCurrent()) loading.value = false;
    }
  },
  { immediate: true },
);
</script>

<template>
  <Modal
    v-model:open="open"
    :footer="null"
    title="口径变更影响分析"
    width="760px"
  >
    <Spin :spinning="loading">
      <div v-if="impact" class="flex flex-col gap-3">
        <div>
          修改或停用
          <Tag color="blue">
{{
            TYPE[impact.indicator.indicatorType] ??
            impact.indicator.indicatorType
          }}
</Tag>
          {{ impact.indicator.name }}（{{ impact.indicator.code }}）会影响：
        </div>
        <Alert
          v-if="impact.truncated"
          type="warning"
          show-icon
          message="下游指标过多，只列出前 200 个"
        />
        <div class="font-medium">
          下游指标（{{ impact.dependents.length }}）
        </div>
        <Table
          v-if="impact.dependents.length"
          :columns="dependentColumns"
          :data-source="impact.dependents"
          :pagination="false"
          :row-key="(row: IndicatorModelApi.ImpactItem) => String(row.id)"
          size="small"
        >
          <template #bodyCell="{ column, record }">
            <template v-if="column.key === 'type'">
{{
              TYPE[record.indicatorType] ?? record.indicatorType
            }}
</template>
            <template v-else-if="column.key === 'name'">
{{ record.name }}（{{ record.code }}）
</template>
          </template>
        </Table>
        <Empty v-else description="没有派生或复合指标依赖它" />
        <div class="font-medium">看板（{{ impact.boards.length }}）</div>
        <div v-if="impact.boards.length" class="flex flex-col gap-1 text-sm">
          <div v-for="board in impact.boards" :key="String(board.id)">
            {{ board.name }}：{{ board.widgets.join('、') }}
          </div>
        </div>
        <div v-else class="text-muted-foreground text-sm">没有看板使用</div>
        <div class="font-medium">数据服务接口（{{ impact.apis.length }}）</div>
        <Alert
          v-if="!impact.apisAvailable"
          type="warning"
          show-icon
          message="数据服务暂不可用，接口清单可能不完整"
        />
        <div v-if="impact.apis.length" class="flex flex-col gap-1 text-sm">
          <div v-for="api in impact.apis" :key="String(api.id)">
            {{ api.name }}
            <span class="text-muted-foreground">{{ api.path }}</span>
            <Tag :color="api.status === 1 ? 'green' : 'default'" class="ml-1">
              {{ api.status === 1 ? '上线' : '下线' }}
            </Tag>
          </div>
        </div>
        <div
          v-else-if="impact.apisAvailable"
          class="text-muted-foreground text-sm"
        >
          没有接口发布
        </div>
      </div>
    </Spin>
  </Modal>
</template>
