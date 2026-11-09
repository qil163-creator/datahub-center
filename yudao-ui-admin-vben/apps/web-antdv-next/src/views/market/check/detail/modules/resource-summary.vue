<script lang="ts" setup>
import type { MarketCheckApi } from '#/api/asset/market/check';

import { computed } from 'vue';

import { Tag } from 'antdv-next';

import DetailBackButton from '#/components/detail-back-button/index.vue';
import ownershipIcon from '#/views/market/check/reference-assets/ownership.png';

import { marketCheckTime } from '../../data';

const props = defineProps<{
  resource?: MarketCheckApi.Resource;
  statusLabel?: string;
  statusTone?: string;
}>();
const emit = defineEmits<{ back: [] }>();
const typeNames: Record<number, string> = {
  1: '数据库表',
  2: 'API接口',
  3: '文件',
};
const assetType = computed(
  () => typeNames[props.resource?.assetType ?? 0] || '-',
);
const deliveryTypes = computed(() =>
  (props.resource?.deliveryTypes ?? []).map((value) => ({
    value,
    label: typeNames[value] || String(value),
  })),
);
const ownership = computed(
  () =>
    `${props.resource?.sourceDeptName || '-'} / ${props.resource?.ownerDeptName || '-'}`,
);
const shortTime = computed(() => marketCheckTime(props.resource?.createTime));
const rowCount = computed(() =>
  props.resource?.rowCount === '0' ? '-' : props.resource?.rowCount || '-',
);
</script>

<template>
  <section class="check-summary">
    <header class="check-summary__heading">
      <h1>
        <span v-if="resource?.sourceRecordId" class="check-summary__badge">{{
          resource.sourceRecordId
        }}</span>{{ resource?.marketName || resource?.assetName || '-'
        }}<Tag
          v-if="statusLabel"
          class="check-summary__status"
          :color="statusTone || 'default'"
          :bordered="false"
        >
          {{ statusLabel }}
        </Tag>
      </h1>
      <DetailBackButton @click="emit('back')" />
    </header>
    <div class="check-summary__grid">
      <div class="check-summary__field">
        <label>资产名称</label><span>{{ resource?.assetName || '-' }}</span>
      </div>
      <div class="check-summary__field">
        <label>所属集市</label><span>{{ resource?.marketCategoryName || '-' }}</span>
      </div>
      <div class="check-summary__field">
        <label>归属信息</label><span class="check-summary__ownership"><img :src="ownershipIcon" alt="" />{{ ownership }}</span>
      </div>
      <div class="check-summary__field">
        <label>资产类型</label><span><Tag class="check-summary__tag" :bordered="false">{{
            assetType
          }}</Tag></span>
      </div>
      <div class="check-summary__field">
        <label>更新频率</label><span><Tag class="check-summary__tag" :bordered="false">{{
            resource?.frequency || '-'
          }}</Tag></span>
      </div>
      <div class="check-summary__field">
        <label>交付形态</label><span><Tag
            v-for="delivery in deliveryTypes"
            :key="delivery.value"
            :bordered="false"
            class="check-summary__tag"
            :class="[`check-summary__tag--${delivery.value}`]"
            >{{ delivery.label }}</Tag><template v-if="!deliveryTypes.length">-</template></span>
      </div>
      <div class="check-summary__field">
        <label>数据量</label><span>{{ rowCount }}</span>
      </div>
      <div class="check-summary__field">
        <label>创建时间</label><span>{{ shortTime }}</span>
      </div>
    </div>
  </section>
</template>

<style scoped>
.check-summary {
  padding: 14px;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.check-summary__heading {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  min-height: 32px;
  margin-bottom: 14px;
}

.check-summary__heading h1 {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin: 0;
  font-size: 16px;
  font-weight: 400;
  color: #303743;
}

.check-summary__badge {
  display: inline-flex;
  align-items: center;
  justify-content: center;
  min-width: 19px;
  height: 19px;
  padding: 0 2px;
  font-size: 14px;
  font-weight: 600;
  color: hsl(var(--primary-foreground));
  background: #4967ff;
}

.check-summary__status {
  padding: 0 8px;
  margin: 0;
  line-height: 24px;
  border-radius: calc(var(--radius) - 2px);
}

.check-summary__grid {
  display: grid;
  grid-template-columns: repeat(3, minmax(0, 1fr));
  border-top: 1px solid #f7f8fa;
  border-left: 1px solid #f7f8fa;
}

.check-summary__field {
  display: flex;
  min-height: 42px;
  font-size: 14px;
  border-right: 1px solid #f7f8fa;
  border-bottom: 1px solid #f7f8fa;
}

.check-summary__field > label {
  display: flex;
  flex: 0 0 140px;
  align-items: center;
  padding: 8px 10px;
  color: #a3a8af;
  background: #fafafa;
}

.check-summary__field > span {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  min-width: 0;
  padding: 8px 10px;
  overflow-wrap: anywhere;
}

.check-summary__tag {
  padding: 0 8px;
  margin: 0;
  line-height: 23px;
  color: #4967ff;
  background: #f0f6fd;
  border-radius: calc(var(--radius) - 2px);
}

.check-summary__tag--2 {
  color: #9452b9;
  background: #f1edf7;
}

.check-summary__tag--3 {
  color: #b98429;
  background: #fdf8ec;
}

.check-summary__ownership img {
  width: 18px;
  height: 18px;
  object-fit: contain;
}

@media (width <= 1200px) {
  .check-summary__field > label {
    flex-basis: 105px;
  }
}

@media (width <= 900px) {
  .check-summary__grid {
    grid-template-columns: repeat(2, minmax(0, 1fr));
  }
}

@media (width <= 640px) {
  .check-summary__grid {
    grid-template-columns: minmax(0, 1fr);
  }

}
</style>
