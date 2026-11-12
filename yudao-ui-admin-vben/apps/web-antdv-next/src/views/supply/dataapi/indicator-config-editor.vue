<script lang="ts" setup>
import type { IndicatorApiConfig } from './indicator-config';

import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { computed, onMounted, ref, watch } from 'vue';

import { Button, Input, InputNumber, Select, Switch } from 'antdv-next';

import {
  getActiveIndicatorOptions,
  getIndicatorQueryMeta,
} from '#/api/modeling/indicator-model';
import { createLatestWins } from '#/utils/resource-id';

import { referencedParams } from './indicator-config';

/** 指标接口配置编辑：指标与维度在发布时固定，调用方只能传声明的时间范围与维度过滤参数。 */
defineOptions({ name: 'IndicatorConfigEditor' });

const props = defineProps<{ declaredParams: string[] }>();
const emit = defineEmits<{ declare: [names: string[]] }>();
const config = defineModel<IndicatorApiConfig>({ required: true });

const options = ref<IndicatorModelApi.Option[]>([]);
const dimensions = ref<IndicatorModelApi.SourceField[]>([]);
const timeSupported = ref(false);
const metaGuard = createLatestWins();

const indicatorOptions = computed(() =>
  options.value.map((item) => ({
    label: `${item.name}（${item.code}）`,
    value: String(item.id),
  })),
);
const dimensionOptions = computed(() =>
  dimensions.value.map((item) => ({
    label: item.cnName ? `${item.cnName}（${item.fieldName}）` : item.fieldName,
    value: item.fieldUid,
  })),
);
const GRAINS = [
  { label: '日', value: 'DAY' },
  { label: '周', value: 'WEEK' },
  { label: '月', value: 'MONTH' },
  { label: '季', value: 'QUARTER' },
  { label: '年', value: 'YEAR' },
];
const timeParams = computed({
  get: () => Boolean(config.value.startParam && config.value.endParam),
  set: (enabled: boolean) => {
    config.value.startParam = enabled ? 'startDate' : undefined;
    config.value.endParam = enabled ? 'endDate' : undefined;
  },
});
const missingParams = computed(() =>
  referencedParams(config.value).filter(
    (name) => !props.declaredParams.includes(name),
  ),
);

async function loadMeta() {
  const ticket = metaGuard.next();
  if (config.value.indicatorIds.length === 0) {
    dimensions.value = [];
    timeSupported.value = false;
    return;
  }
  try {
    const meta = await getIndicatorQueryMeta(config.value.indicatorIds);
    if (!ticket.isCurrent()) return;
    dimensions.value = meta?.dimensions ?? [];
    timeSupported.value = Boolean(meta?.timeSupported);
  } catch {
    if (ticket.isCurrent()) dimensions.value = [];
  }
}

function addFilter() {
  config.value.filters.push({ param: '', fieldUid: '' });
}

function removeFilter(index: number) {
  config.value.filters.splice(index, 1);
}

watch(() => [...config.value.indicatorIds], loadMeta);
onMounted(async () => {
  try {
    options.value = (await getActiveIndicatorOptions()) ?? [];
  } catch {
    options.value = [];
  }
  await loadMeta();
});
</script>

<template>
  <div class="flex flex-col gap-3">
    <div class="form-item form-item--full">
      <label class="form-item__label form-item__label--required">发布的指标</label>
      <Select
        v-model:value="config.indicatorIds"
        :options="indicatorOptions"
        mode="multiple"
        option-filter-prop="label"
        placeholder="选择 1-10 个已启用指标"
      />
      <div class="form-item__tip">
        结果按调用应用的权限主体动态脱敏；指标接口不能使用 NONE 认证。
      </div>
    </div>
    <div class="form-item form-item--full">
      <label class="form-item__label">维度</label>
      <Select
        v-model:value="config.dimensions"
        :options="dimensionOptions"
        mode="multiple"
        placeholder="所选指标共同可用的维度（最多 5 个）"
      />
    </div>
    <div class="flex flex-wrap items-center gap-3">
      <span class="text-sm">时间粒度</span>
      <Select
        v-model:value="config.timeGrain"
        :disabled="!timeSupported"
        :options="GRAINS"
        allow-clear
        class="w-28"
      />
      <span class="text-sm">行数上限</span>
      <InputNumber
        v-model:value="config.limit"
        :max="1000"
        :min="1"
        :precision="0"
      />
      <span class="text-sm">按日期范围查询</span>
      <Switch v-model:checked="timeParams" :disabled="!timeSupported" />
      <span v-if="timeParams" class="text-muted-foreground text-xs">
        参数 {{ config.startParam }} / {{ config.endParam }}（yyyy-MM-dd）
      </span>
    </div>
    <div class="form-item form-item--full">
      <label class="form-item__label">维度过滤参数</label>
      <div
        v-for="(filter, index) in config.filters"
        :key="index"
        class="mb-2 flex items-center gap-2"
      >
        <Input
          v-model:value="filter.param"
          class="w-48"
          placeholder="请求参数名，如 region"
        />
        <Select
          v-model:value="filter.fieldUid"
          :options="dimensionOptions"
          class="w-64"
          placeholder="对应维度"
        />
        <Button danger size="small" type="link" @click="removeFilter(index)">
删除
</Button>
      </div>
      <Button size="small" @click="addFilter">添加过滤参数</Button>
    </div>
    <div v-if="missingParams.length" class="flex items-center gap-2 text-xs">
      <span class="text-warning">以下参数尚未在“请求参数”中声明：{{ missingParams.join('、') }}</span>
      <Button size="small" type="link" @click="emit('declare', missingParams)">
一键声明
</Button>
    </div>
  </div>
</template>
