<script setup lang="ts">
import { computed, ref, watch } from 'vue';

import { Button, Modal } from 'antdv-next';

import './node-fields-modal.css';

export interface NodeFieldSource {
  name: string;
  dataType?: null | string;
  length?: null | number | string;
  precision?: null | number | string;
  sourceName?: null | string;
}

const props = withDefaults(
  defineProps<{
    direction: 'input' | 'output';
    error?: string;
    fields: readonly NodeFieldSource[];
    loadFailed?: boolean;
    loading?: boolean;
    open: boolean;
    partial?: boolean;
    stepName: string;
  }>(),
  { error: '', loadFailed: false, loading: false, partial: false },
);
const emit = defineEmits<{ 'update:open': [value: boolean] }>();
const sectionName = computed(() =>
  props.direction === 'input' ? '输入字段' : '输出字段',
);
const sortKey = ref<'length' | 'precision'>();
const sortDirection = ref<'ascending' | 'descending'>('ascending');
watch(
  () => [props.open, props.direction, props.stepName],
  () => {
    sortKey.value = undefined;
  },
);
function integer(value: NodeFieldSource['length']): bigint | undefined {
  if (typeof value === 'number')
    return Number.isSafeInteger(value) ? BigInt(value) : undefined;
  if (typeof value === 'string' && /^[+-]?\d+$/u.test(value.trim()))
    return BigInt(value.trim());
  return undefined;
}
const sortedFields = computed(() => {
  const key = sortKey.value;
  const rows = props.fields.map((field, index) => ({ field, index }));
  if (!key) return rows;
  return rows.toSorted((left, right) => {
    const a = integer(left.field[key]);
    const b = integer(right.field[key]);
    if (a === undefined) return b === undefined ? left.index - right.index : 1;
    if (b === undefined) return -1;
    let order = 0;
    if (a < b) order = -1;
    else if (a > b) order = 1;
    return (
      (sortDirection.value === 'ascending' ? order : -order) ||
      left.index - right.index
    );
  });
});
function toggleSort(key: 'length' | 'precision') {
  if (sortKey.value !== key) {
    sortKey.value = key;
    sortDirection.value = 'ascending';
  } else if (sortDirection.value === 'ascending') {
    sortDirection.value = 'descending';
  } else sortKey.value = undefined;
}
const emptyMessage = computed(() =>
  props.partial ? '暂时无法确定此步骤的字段' : `此步骤暂无${sectionName.value}`,
);
function display(value: NodeFieldSource[keyof NodeFieldSource]) {
  return value === undefined || value === null || value === '' ? '-' : value;
}
function close() {
  emit('update:open', false);
}
</script>

<template>
  <Modal
    :open="open"
    title="步骤里的字段和其来源"
    width="800px"
    wrap-class-name="qi-node-fields-modal"
    destroy-on-hidden
    @cancel="close"
  >
    <p class="qi-node-fields-step">
      <span>步骤名称：</span><strong>{{ display(stepName) }}</strong>
    </p>
    <h3 class="qi-node-fields-section">{{ sectionName }}</h3>
    <p v-if="error" class="qi-node-fields-error" role="alert">{{ error }}</p>
    <p
      v-else-if="partial && !loading"
      class="qi-node-fields-notice"
      role="status"
    >
      字段信息尚不完整，仅展示当前可确定的字段。
    </p>
    <div v-if="!loadFailed" class="qi-node-fields-scroll" :aria-busy="loading">
      <table class="qi-node-fields-table" :aria-label="sectionName">
        <colgroup>
          <col class="qi-node-fields-number" />
          <col class="qi-node-fields-name" />
          <col class="qi-node-fields-type" />
          <col class="qi-node-fields-length" />
          <col class="qi-node-fields-precision" />
          <col class="qi-node-fields-source" />
        </colgroup>
        <thead>
          <tr>
            <th scope="col">序号</th>
            <th scope="col">字段名称</th>
            <th scope="col">字段类型</th>
            <th
              scope="col"
              :aria-sort="sortKey === 'length' ? sortDirection : 'none'"
            >
              <button
                class="qi-node-fields-sort"
                type="button"
                @click="toggleSort('length')"
              >
                字段长度
              </button>
            </th>
            <th
              scope="col"
              :aria-sort="sortKey === 'precision' ? sortDirection : 'none'"
            >
              <button
                class="qi-node-fields-sort"
                type="button"
                @click="toggleSort('precision')"
              >
                字段精度
              </button>
            </th>
            <th scope="col">步骤来源</th>
          </tr>
        </thead>
        <tbody>
          <tr v-if="loading">
            <td colspan="6" class="qi-node-fields-state" role="status">
              正在加载字段信息…
            </td>
          </tr>
          <template v-else-if="error"></template>
          <template v-else>
            <tr
              v-for="({ field, index }, rowIndex) in sortedFields"
              :key="`${index}:${field.name}`"
            >
              <td>{{ rowIndex + 1 }}</td>
              <td :title="field.name">{{ display(field.name) }}</td>
              <td :title="field.dataType ?? undefined">
                {{ display(field.dataType) }}
              </td>
              <td>{{ display(field.length) }}</td>
              <td>{{ display(field.precision) }}</td>
              <td :title="field.sourceName ?? undefined">
                {{ display(field.sourceName) }}
              </td>
            </tr>
            <tr v-if="!fields.length">
              <td colspan="6" class="qi-node-fields-state" role="status">
                {{ emptyMessage }}
              </td>
            </tr>
          </template>
        </tbody>
      </table>
    </div>
    <template #footer>
      <Button @click="close">关闭</Button>
    </template>
  </Modal>
</template>
