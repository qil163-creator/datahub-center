<script lang="ts" setup>
import type { IndicatorModelApi } from '#/api/modeling/indicator-model';

import { computed, onMounted, ref, watch } from 'vue';

import {
  Input,
  InputNumber,
  Modal,
  DateRangePicker as RangePicker,
  Select,
} from 'antdv-next';

import {
  getActiveIndicatorOptions,
  getIndicatorQueryMeta,
} from '#/api/modeling/indicator-model';
import { createLatestWins } from '#/utils/resource-id';

import { CHART_TYPE_OPTIONS, widgetError } from './board';

/** 看板组件编辑：标题、展示方式与指标查询（指标、维度、粒度、时间范围、行数）。 */
defineOptions({ name: 'IndicatorBoardWidgetEditor' });

const props = defineProps<{ widget?: IndicatorModelApi.BoardWidget }>();
const emit = defineEmits<{ save: [widget: IndicatorModelApi.BoardWidget] }>();
const open = defineModel<boolean>('open', { default: false });
const draft = ref<IndicatorModelApi.BoardWidget>(blank());
const range = ref<[string, string]>();
const error = ref<string>();
const options = ref<IndicatorModelApi.Option[]>([]);
const dimensions = ref<IndicatorModelApi.SourceField[]>([]);
const timeSupported = ref(false);
const metaGuard = createLatestWins();

const GRAINS = [
  { label: '日', value: 'DAY' },
  { label: '周', value: 'WEEK' },
  { label: '月', value: 'MONTH' },
  { label: '季', value: 'QUARTER' },
  { label: '年', value: 'YEAR' },
];
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

function blank(): IndicatorModelApi.BoardWidget {
  return {
    key: '',
    title: '',
    chartType: 'TABLE',
    query: { indicatorIds: [], dimensions: [], limit: 200 },
  };
}

async function loadMeta() {
  const ticket = metaGuard.next();
  const ids = draft.value.query.indicatorIds;
  if (ids.length === 0) {
    dimensions.value = [];
    timeSupported.value = false;
    return;
  }
  try {
    const meta = await getIndicatorQueryMeta(ids);
    if (!ticket.isCurrent()) return;
    dimensions.value = meta?.dimensions ?? [];
    timeSupported.value = Boolean(meta?.timeSupported);
  } catch {
    if (ticket.isCurrent()) dimensions.value = [];
  }
}

function save() {
  const widget: IndicatorModelApi.BoardWidget = {
    ...draft.value,
    title: draft.value.title.trim(),
    query: {
      ...draft.value.query,
      timeRange: range.value
        ? { start: range.value[0], end: range.value[1] }
        : undefined,
    },
  };
  error.value = widgetError(widget);
  if (error.value) return;
  emit('save', widget);
  open.value = false;
}

watch(open, (visible) => {
  if (!visible) return;
  draft.value = props.widget ? structuredClone(props.widget) : blank();
  draft.value.query.dimensions ??= [];
  const timeRange = draft.value.query.timeRange;
  range.value = timeRange ? [timeRange.start, timeRange.end] : undefined;
  error.value = undefined;
  void loadMeta();
});
watch(() => [...draft.value.query.indicatorIds], loadMeta);
onMounted(async () => {
  try {
    options.value = (await getActiveIndicatorOptions()) ?? [];
  } catch {
    options.value = [];
  }
});
</script>

<template>
  <Modal
    v-model:open="open"
    :title="widget ? '编辑组件' : '添加组件'"
    width="640px"
    @ok="save"
  >
    <div class="flex flex-col gap-3">
      <Input
        v-model:value="draft.title"
        :maxlength="60"
        placeholder="组件标题"
      />
      <Select
        v-model:value="draft.chartType"
        :options="[...CHART_TYPE_OPTIONS]"
      />
      <Select
        v-model:value="draft.query.indicatorIds"
        :options="indicatorOptions"
        mode="multiple"
        option-filter-prop="label"
        placeholder="选择指标（1-10 个）"
      />
      <Select
        v-model:value="draft.query.dimensions"
        :options="dimensionOptions"
        mode="multiple"
        placeholder="维度（可选，最多 5 个）"
      />
      <div class="flex flex-wrap items-center gap-2">
        <Select
          v-model:value="draft.query.timeGrain"
          :disabled="!timeSupported"
          :options="GRAINS"
          allow-clear
          class="w-28"
          placeholder="时间粒度"
        />
        <RangePicker
          v-model:value="range"
          :disabled="!timeSupported"
          value-format="YYYY-MM-DD"
        />
        <InputNumber
          v-model:value="draft.query.limit"
          :max="1000"
          :min="1"
          :precision="0"
          placeholder="行数"
        />
      </div>
      <div v-if="error" class="text-destructive text-xs">{{ error }}</div>
    </div>
  </Modal>
</template>
