<script lang="ts" setup>
import type { DataforgeAccessApi } from '#/api/dataforge/access';

import { computed, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Alert, Button, Input, InputNumber, Modal, Select } from 'antdv-next';

import {
  copyDorisOptions,
  defaultDorisOptions,
  validateDorisOptions,
  validateDorisPartition,
} from './table-mapping';

const props = withDefaults(
  defineProps<{
    open: boolean;
    options?: DataforgeAccessApi.DorisOptions;
    section?: 'all' | 'distribution' | 'partition';
    title?: string;
  }>(),
  { options: undefined, section: 'all', title: 'Doris 建表配置' },
);
const emit = defineEmits<{
  save: [value: DataforgeAccessApi.DorisOptions];
  'update:open': [value: boolean];
}>();
const draft = ref(defaultDorisOptions());
const error = ref('');
const partitionColumnsInput = ref('');
const distributionColumnsInput = ref('');
const partitionKind = computed(() => draft.value.partition?.kind ?? 'NONE');
const showPartition = computed(() => props.section !== 'distribution');
const showDistribution = computed(
  () => props.section !== 'partition' || !props.options,
);

watch(
  () => props.open,
  (open) => {
    if (open) {
      draft.value = copyDorisOptions(props.options);
      partitionColumnsInput.value =
        draft.value.partition?.columns.join(', ') ?? '';
      distributionColumnsInput.value =
        draft.value.distributionColumns.join(', ');
      error.value = '';
    }
  },
  { immediate: true },
);

function changePartition(kind: unknown) {
  if (kind !== 'LIST' && kind !== 'NONE' && kind !== 'RANGE') return;
  if (kind === 'NONE') {
    draft.value.partition = undefined;
  } else {
    const previous = draft.value.partition;
    draft.value.partition = {
      kind,
      columns: previous?.columns ?? [],
      partitions:
        previous?.partitions.map((item) => ({
          ...item,
          values:
            kind === 'RANGE'
              ? [
                  item.values[0] ?? previous.columns.map(() => ''),
                  item.values[1] ?? previous.columns.map(() => ''),
                ]
              : item.values,
        })) ?? [],
    };
  }
  partitionColumnsInput.value = draft.value.partition?.columns.join(', ') ?? '';
}

function setPartitionColumns(text: string) {
  const partition = draft.value.partition;
  if (!partition) return;
  partition.columns = text
    .split(',')
    .map((column) => column.trim())
    .filter(Boolean);
  for (const item of partition.partitions) {
    item.values = item.values.map((tuple) =>
      partition.columns.map((_, index) => tuple[index] ?? ''),
    );
  }
}

function addPartition() {
  const partition = draft.value.partition;
  if (!partition) return;
  partition.partitions.push({
    name: '',
    values: Array.from({ length: partition.kind === 'RANGE' ? 2 : 1 }, () =>
      partition.columns.map(() => ''),
    ),
  });
}

function changeDistribution(value: unknown) {
  if (value !== 'HASH' && value !== 'RANDOM') return;
  draft.value.distributionType = value;
  if (value === 'RANDOM') {
    draft.value.distributionColumns = [];
    distributionColumnsInput.value = '';
  }
}

function setDistributionColumns(text: string) {
  draft.value.distributionColumns = text
    .split(',')
    .map((value) => value.trim())
    .filter(Boolean);
}

function save() {
  const options = copyDorisOptions(draft.value);
  if (options.bucketMode === 'AUTO') delete options.bucketCount;
  if (options.distributionType === 'RANDOM') options.distributionColumns = [];
  error.value =
    props.section === 'partition' && !showDistribution.value
      ? validateDorisPartition(options.partition)
      : validateDorisOptions(
          props.section === 'distribution'
            ? { ...options, partition: undefined }
            : options,
        );
  if (error.value) return;
  emit('save', options);
  emit('update:open', false);
}
</script>

<template>
  <Modal
    :open="open"
    :title="title"
    :width="900"
    :mask-closable="false"
    destroy-on-hidden
    ok-text="确定"
    cancel-text="取消"
    @cancel="emit('update:open', false)"
    @ok="save"
  >
    <div class="max-h-[65vh] space-y-5 overflow-y-auto py-2">
      <Alert v-if="error" :message="error" type="error" show-icon />
      <section v-if="showPartition" class="space-y-3">
        <h3 class="font-medium">目标表分区</h3>
        <label class="block space-y-1">
          <span>分区方式</span>
          <Select
            :value="partitionKind"
            class="w-full"
            aria-label="分区方式"
            :options="[
              { label: '不分区', value: 'NONE' },
              { label: 'RANGE 范围分区', value: 'RANGE' },
              { label: 'LIST 枚举分区', value: 'LIST' },
            ]"
            @update:value="changePartition"
          />
        </label>
        <template v-if="draft.partition">
          <label class="block space-y-1">
            <span>分区字段</span>
            <Input
              v-model:value="partitionColumnsInput"
              aria-label="分区字段"
              placeholder="目标字段名称，多列用英文逗号分隔"
              @update:value="setPartitionColumns"
            />
          </label>
          <p class="text-muted-foreground text-xs">
            {{
              draft.partition.kind === 'RANGE'
                ? '按字段顺序填写包含下界、不包含上界的范围；下界填写具体值，上界可填写 MAXVALUE。'
                : '按字段顺序填写每组枚举值；同一分区可添加多组值。'
            }}
            值直接填写，无需添加 SQL 引号。
          </p>
          <div
            v-for="(partition, index) in draft.partition.partitions"
            :key="index"
            class="rounded border p-3"
          >
            <div class="mb-3 flex items-center gap-3">
              <Input
                v-model:value="partition.name"
                :aria-label="`分区 ${index + 1} 名称`"
                placeholder="分区名称，如 p202601"
              />
              <Button
                danger
                @click="draft.partition.partitions.splice(index, 1)"
              >
                <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
                删除分区
              </Button>
            </div>
            <div
              v-for="(tuple, tupleIndex) in partition.values"
              :key="tupleIndex"
              class="mb-2 flex items-center gap-2"
            >
              <span
                v-if="draft.partition.kind === 'RANGE'"
                class="text-muted-foreground w-14 shrink-0 text-xs"
                >{{ tupleIndex === 0 ? '包含下界' : '不含上界' }}</span>
              <Input
                v-for="(column, columnIndex) in draft.partition.columns"
                :key="columnIndex"
                v-model:value="tuple[columnIndex]"
                :aria-label="`${partition.name || `分区 ${index + 1}`} ${column} 值 ${tupleIndex + 1}`"
                :placeholder="`${column} ${draft.partition.kind === 'RANGE' ? (tupleIndex === 0 ? '下界值' : '上界值') : '枚举值'}`"
              />
              <Button
                v-if="draft.partition.kind === 'LIST'"
                :disabled="partition.values.length === 1"
                @click="partition.values.splice(tupleIndex, 1)"
              >
                <template #icon><IconifyIcon icon="lucide:minus" /></template>
                移除
              </Button>
            </div>
            <Button
              v-if="draft.partition.kind === 'LIST'"
              size="small"
              @click="
                partition.values.push(draft.partition.columns.map(() => ''))
              "
            >
              <template #icon><IconifyIcon icon="lucide:plus" /></template>
              添加一组值
            </Button>
          </div>
          <Button
            :disabled="!draft.partition.columns.length"
            @click="addPartition"
          >
            <template #icon><IconifyIcon icon="lucide:plus" /></template>
            添加分区
          </Button>
        </template>
      </section>
      <section v-if="showDistribution" class="space-y-3">
        <h3 class="font-medium">目标表分桶与副本</h3>
        <p v-if="section === 'partition'" class="text-muted-foreground text-xs">
          尚未配置布局的表需要同时指定分桶；已有分桶设置的表保留原配置。
        </p>
        <p
          v-if="draft.distributionType === 'RANDOM'"
          class="text-muted-foreground text-xs"
        >
          RANDOM 适用于明细模型；Unique Key 表需使用
          HASH。保存任务时将按实际表结构校验。
        </p>
        <div class="grid grid-cols-2 gap-4">
          <label class="space-y-1">
            <span>分桶方式</span>
            <Select
              :value="draft.distributionType"
              class="w-full"
              aria-label="分桶方式"
              :options="[
                { label: 'HASH 哈希分桶', value: 'HASH' },
                { label: 'RANDOM 随机分桶', value: 'RANDOM' },
              ]"
              @update:value="changeDistribution"
            />
          </label>
          <label class="space-y-1">
            <span>桶数方式</span>
            <Select
              v-model:value="draft.bucketMode"
              class="w-full"
              aria-label="桶数方式"
              :options="[
                { label: '自动分桶', value: 'AUTO' },
                { label: '固定桶数', value: 'FIXED' },
              ]"
            />
          </label>
          <label
            v-if="draft.distributionType === 'HASH'"
            class="col-span-2 space-y-1"
          >
            <span>分桶字段</span>
            <Input
              v-model:value="distributionColumnsInput"
              aria-label="分桶字段"
              placeholder="目标字段名称，多列用英文逗号分隔"
              @update:value="setDistributionColumns"
            />
          </label>
          <label v-if="draft.bucketMode === 'FIXED'" class="space-y-1">
            <span>桶数</span>
            <InputNumber
              v-model:value="draft.bucketCount"
              :min="1"
              :precision="0"
              class="w-full"
              aria-label="桶数"
            />
          </label>
          <label class="space-y-1">
            <span>副本数量</span>
            <InputNumber
              v-model:value="draft.replicationNum"
              :min="1"
              :precision="0"
              class="w-full"
              aria-label="副本数量"
            />
          </label>
        </div>
      </section>
    </div>
  </Modal>
</template>
