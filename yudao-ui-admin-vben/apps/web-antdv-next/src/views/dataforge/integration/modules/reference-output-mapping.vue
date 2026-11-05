<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 映射只编辑父设计器隔离草稿，确认后由父级保存。 */
import type {
  FieldMapping,
  MappingColumns,
  MappingField,
  MappingSide,
} from './field-mapping';

import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, ref, watch } from 'vue';

import { Button, Checkbox } from 'antdv-next';

import {
  createMappingColumns,
  moveMappingColumn,
  refreshMappingColumns,
  serializeMappingColumns,
  serializeMappingLayout,
  validateFieldMappings,
} from './field-mapping';
import ReferenceIcon from './reference-icon.vue';

const props = defineProps<{
  config: Record<string, any>;
  connectorMode?: boolean;
  disabled?: boolean;
  inputFields?: DataforgeEtlApi.FieldMeta[];
  targetFields?: {
    dataType?: null | string;
    name: string;
    type?: null | string;
  }[];
}>();
const sides: MappingSide[] = ['source', 'target'];
const columns = ref<MappingColumns>({ source: [], target: [] });
const issues = ref<string[]>([]);
const sourceFields = computed<MappingField[]>(() =>
  (props.inputFields ?? []).flatMap((field) =>
    field.name ? [{ name: field.name, dataType: field.dataType }] : [],
  ),
);
const targetFields = computed<MappingField[]>(() => {
  if (props.targetFields !== undefined)
    return props.targetFields.map((field) => ({
      name: field.name,
      dataType: field.dataType ?? field.type,
    }));
  return (
    props.connectorMode && Array.isArray(props.config.fieldMappingColumns) ? props.config.fieldMappingColumns : Array.isArray(props.config.columns) ? props.config.columns : []
  ).flatMap((field: unknown) => {
    if (typeof field === 'string') return field ? [{ name: field }] : [];
    if (!field || typeof field !== 'object') return [];
    const value = field as Record<string, unknown>;
    return typeof value.name === 'string' && value.name
      ? [
          {
            name: value.name,
            dataType: typeof value.type === 'string' ? value.type : undefined,
          },
        ]
      : [];
  });
});
/** 已映射的字段在表中不存在时才提供“同名自动映射”，正常状态与参考界面一致。 */
const needsRepair = computed(() =>
  [...columns.value.source, ...columns.value.target].some(
    (field) => !!field.name && !field.exists,
  ),
);
const rowCount = computed(() =>
  Math.max(columns.value.source.length, columns.value.target.length),
);
const drag = ref<{
  config: Record<string, any>;
  key: string;
  side: MappingSide;
}>();
let currentConfig: Record<string, any> | undefined;
let committed = '';
const signature = () =>
  JSON.stringify([props.config.fieldMappings, props.config.fieldMappingLayout, props.connectorMode ? props.config.fieldMappingDraft : undefined]);
function savedMappings(): FieldMapping[] | undefined {
  if (props.connectorMode && Array.isArray(props.config.fieldMappingDraft)) {
    const draft = props.config.fieldMappingDraft as FieldMapping[];
    const enabled = draft.filter((mapping) => mapping.enabled);
    const actual = Array.isArray(props.config.fieldMappings) ? props.config.fieldMappings : [];
    if (JSON.stringify(enabled.map(({source,target}) => [source,target])) === JSON.stringify(actual.map((mapping: FieldMapping) => [mapping.source,mapping.target]))) return draft;
  }
  return Array.isArray(props.config.fieldMappings)
    ? props.config.fieldMappings.map((mapping: FieldMapping) => ({
        ...mapping,
        enabled: mapping.enabled !== false,
      }))
    : undefined;
}
watch(
  () =>
    [
      props.config,
      signature(),
      sourceFields.value,
      targetFields.value,
    ] as const,
  () => {
    const next = signature();
    if (
      currentConfig !== props.config ||
      committed !== next ||
      props.config.fieldMappings === undefined
    ) {
      columns.value = createMappingColumns(
        sourceFields.value,
        targetFields.value,
        savedMappings(),
        props.config.fieldMappingLayout,
      );
      drag.value = undefined;
      issues.value = [];
    } else
      columns.value = refreshMappingColumns(
        columns.value,
        sourceFields.value,
        targetFields.value,
      );
    currentConfig = props.config;
    committed = next;
  },
  { immediate: true },
);
watch(
  () => props.disabled,
  () => {
    drag.value = undefined;
  },
);
function commit() {
  const previous = Array.isArray(props.config.fieldMappings)
    ? props.config.fieldMappings
    : [];
  const draft = serializeMappingColumns(columns.value).map(
    (mapping) => ({
      ...(previous.find(
        (item: FieldMapping) =>
          item.source === mapping.source && item.target === mapping.target,
      ) ??
        previous.find((item: FieldMapping) => item.source === mapping.source)),
      ...mapping,
    }),
  );
  if (props.connectorMode) {
    const catalog: Record<string, any>[] = Array.isArray(props.config.fieldMappingColumns) ? props.config.fieldMappingColumns.map((field: Record<string, any>) => ({ ...field })) : (Array.isArray(props.config.columns) ? props.config.columns : []).map((field: Record<string, any>) => ({ ...field }));
    for (const field of props.targetFields ?? []) if (!catalog.some((old) => old.name === field.name)) catalog.push({ ...field, name: field.name, type: field.dataType ?? field.type ?? '' });
    props.config.fieldMappingColumns = catalog;
    props.config.fieldMappingDraft = draft;
    const enabled = draft.filter((mapping) => mapping.enabled);
    props.config.columns = enabled.map((mapping) => ({ ...catalog.find((field) => field.name === mapping.target), name: mapping.target, key: mapping.source }));
    props.config.fieldMappings = enabled;
  } else props.config.fieldMappings = draft;
  props.config.fieldMappingLayout = serializeMappingLayout(columns.value);
  committed = signature();
  issues.value = [];
}
function selection(side: MappingSide) {
  const available = columns.value[side].filter(
    (field) => field.exists && field.name,
  );
  const count = available.filter((field) => field.checked).length;
  return {
    all: available.length > 0 && count === available.length,
    partial: count > 0 && count < available.length,
  };
}
function select(side: MappingSide, index: number, checked: boolean) {
  if (props.disabled) return;
  const field = columns.value[side][index];
  if (!field?.exists || !field.name) return;
  field.checked = checked;
  commit();
}
function selectAll(side: MappingSide, checked: boolean) {
  if (props.disabled) return;
  for (const field of columns.value[side])
    if (field.exists && field.name) field.checked = checked;
  commit();
}
function move(side: MappingSide, from: number, to: number) {
  if (props.disabled) return;
  const next = moveMappingColumn(columns.value, side, from, to);
  if (next === columns.value) return;
  columns.value = next;
  commit();
}
function startDrag(event: DragEvent, side: MappingSide, index: number) {
  if (props.disabled) {
    event.preventDefault();
    return;
  }
  drag.value = {
    side,
    key: columns.value[side][index]!.key,
    config: props.config,
  };
  if (event.dataTransfer) {
    event.dataTransfer.effectAllowed = 'move';
    event.dataTransfer.setData('text/plain', drag.value.key);
  }
}
function drop(side: MappingSide, index: number) {
  const previous = drag.value;
  drag.value = undefined;
  if (!previous || previous.side !== side || previous.config !== props.config)
    return;
  move(
    side,
    columns.value[side].findIndex((field) => field.key === previous.key),
    index,
  );
}
function keydown(event: KeyboardEvent, side: MappingSide, index: number) {
  if (!event.altKey || !['ArrowDown', 'ArrowUp'].includes(event.key)) return;
  event.preventDefault();
  move(side, index, index + (event.key === 'ArrowUp' ? -1 : 1));
}
function active(index: number) {
  const source = columns.value.source[index];
  const target = columns.value.target[index];
  return !!(
    source?.checked &&
    source.exists &&
    source.name &&
    target?.checked &&
    target.exists &&
    target.name
  );
}
function autoMap() {
  if (
    props.disabled ||
    !sourceFields.value.length ||
    !targetFields.value.length
  )
    return;
  columns.value = createMappingColumns(sourceFields.value, targetFields.value);
  commit();
}
function validate() {
  issues.value = validateFieldMappings(
    serializeMappingColumns(columns.value),
    sourceFields.value,
    targetFields.value,
  );
  return issues.value.length === 0;
}
defineExpose({ autoMap, validate });
</script>

<template>
  <section class="qi-output-mapping" aria-label="字段映射">
    <div class="qi-output-mapping-toolbar">
      <h3>字段映射</h3>
      <Button
        v-if="needsRepair"
        class="qi-reference-button"
        :disabled="disabled || !sourceFields.length || !targetFields.length"
        @click="autoMap"
      >
        <ReferenceIcon name="refresh" />同名自动映射
      </Button>
    </div>
    <p v-if="issues.length" class="qi-output-mapping-error" role="alert">
      {{ issues.join('；') }}
    </p>
    <div class="qi-output-mapping-scroll">
      <div class="qi-output-mapping-columns">
        <template v-for="side in sides" :key="side">
          <div
            v-if="side === 'target'"
            class="qi-output-mapping-arrows"
            aria-hidden="true"
          >
            <div
              v-for="index in rowCount"
              :key="index"
              class="qi-output-mapping-arrow-row"
            >
              <template
                v-if="columns.source[index - 1] && columns.target[index - 1]"
              >
                <i class="qi-output-mapping-dot"></i>
                <span
                  class="qi-output-mapping-arrow"
                  :class="{ 'is-active': active(index - 1) }"
                ></span>
                <i class="qi-output-mapping-dot"></i>
              </template>
            </div>
          </div>
          <div class="qi-output-mapping-side" :data-mapping-side="side">
            <div class="qi-output-mapping-heading">
              <p>{{ side === 'source' ? '来源表字段：' : '目标字段：' }}</p>
              <Checkbox
                v-if="columns[side].length"
                :checked="selection(side).all"
                :indeterminate="selection(side).partial"
                :disabled="
                  disabled ||
                  !columns[side].some((field) => field.exists && field.name)
                "
                :aria-label="
                  side === 'source' ? '全选来源字段' : '全选目标字段'
                "
                @update:checked="(checked) => selectAll(side, checked)"
                >
全选
</Checkbox>
            </div>
            <div
              class="qi-output-mapping-list"
              :aria-label="side === 'source' ? '来源字段排序' : '目标字段排序'"
            >
              <div
                v-for="(field, index) in columns[side]"
                :key="field.key"
                class="qi-output-mapping-row"
                :class="{
                  'is-missing': !field.exists,
                  'is-dragging': drag?.side === side && drag.key === field.key,
                }"
                :data-field-name="field.name"
                :draggable="!disabled"
                :tabindex="disabled ? -1 : 0"
                :title="`${field.name || '未选择字段'}${field.dataType ? `（${field.dataType}）` : ''}${field.exists ? '' : '：字段不存在'}；Alt + 方向键调整顺序`"
                @dragstart="(event) => startDrag(event, side, index)"
                @dragend="drag = undefined"
                @dragover.prevent
                @drop.prevent="drop(side, index)"
                @keydown="(event) => keydown(event, side, index)"
              >
                <Checkbox
                  :checked="field.checked"
                  :disabled="disabled || !field.exists || !field.name"
                  :aria-label="`${side === 'source' ? '来源' : '目标'}字段${field.name || '未选择'}`"
                  @update:checked="(checked) => select(side, index, checked)"
                  >
<span>{{ field.name || '未选择字段' }}</span>
</Checkbox>
                <ReferenceIcon
                  name="field-drag-ref"
                  class="qi-output-mapping-drag"
                />
              </div>
            </div>
          </div>
        </template>
      </div>
    </div>
  </section>
</template>

<style scoped>
.qi-output-mapping {
  font-size: 14px;
  color: #333;
}

.qi-output-mapping-toolbar {
  display: flex;
  gap: 16px;
  align-items: center;
  justify-content: space-between;
  margin-bottom: 14px;
}

.qi-output-mapping-toolbar h3 {
  padding-left: 10px;
  margin: 0;
  font-size: 14px;
  font-weight: 400;
  border-left: 4px solid #2f62ff;
}

.qi-output-mapping-scroll {
  max-height: 340px;
  padding: 0 24px 12px;
  overflow: auto;
}

.qi-output-mapping-columns {
  display: grid;
  grid-template-columns: minmax(140px, 1fr) 100px minmax(140px, 1fr);
  align-items: start;
}

.qi-output-mapping-heading {
  height: 76px;
  padding: 8px 12px;
}

.qi-output-mapping-heading p {
  margin: 0 0 10px;
}

.qi-output-mapping-row,
.qi-output-mapping-arrow-row {
  display: flex;
  align-items: center;
  height: 40px;
}

.qi-output-mapping-row {
  gap: 12px;
  justify-content: space-between;
  padding: 0 12px;
  border: 1px solid transparent;
}

.qi-output-mapping-row[draggable='true'] {
  cursor: move;
}

.qi-output-mapping-row:focus-visible {
  outline: 2px solid #2f62ff;
  outline-offset: -2px;
}

.qi-output-mapping-row:hover,
.qi-output-mapping-row.is-dragging {
  background: #f5f7fa;
}

.qi-output-mapping-row.is-missing,
.qi-output-mapping-error {
  color: #ff6262;
}

.qi-output-mapping-row :deep(.ant-checkbox-wrapper) {
  min-width: 0;
  overflow: hidden;
  white-space: nowrap;
}

.qi-output-mapping-row :deep(.ant-checkbox-wrapper > span:last-child) {
  overflow: hidden;
  text-overflow: ellipsis;
}

.qi-output-mapping-drag {
  flex-shrink: 0;
  width: 16px;
  height: 16px;
}

.qi-output-mapping-arrows {
  padding-top: 76px;
}

.qi-output-mapping-arrow-row {
  justify-content: center;
  padding: 0 6px;
}

.qi-output-mapping-dot {
  flex-shrink: 0;
  width: 6px;
  height: 6px;
  background: #2f62ff;
  border-radius: 50%;
}

.qi-output-mapping-arrow {
  position: relative;
  flex: 1;
  height: 1px;
  margin: 0 3px;
}

.qi-output-mapping-arrow.is-active {
  background: #2f62ff;
}

.qi-output-mapping-arrow.is-active::after {
  position: absolute;
  top: -3px;
  right: 0;
  width: 7px;
  height: 7px;
  content: '';
  border-top: 1px solid #2f62ff;
  border-right: 1px solid #2f62ff;
  transform: rotate(45deg);
}

</style>
