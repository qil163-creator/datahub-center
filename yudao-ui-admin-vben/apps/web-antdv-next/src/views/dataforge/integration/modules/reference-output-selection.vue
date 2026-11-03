<script setup lang="ts">
/* eslint-disable vue/no-mutating-props -- 只修改父设计器提供的隔离草稿，保留已有字段及来源映射。 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed } from 'vue';

import { Checkbox } from 'antdv-next';

const props = defineProps<{
  config: Record<string, any>;
  disabled?: boolean;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();
const selected = computed<Record<string, any>[]>(() =>
  Array.isArray(props.config.columns) ? props.config.columns : [],
);
const rows = computed(() => {
  const upstream = (props.inputFields ?? [])
    .filter((field) => field.name)
    .map((field) => ({
      name: field.name!,
      type: field.dataType ?? '',
      missing: false,
    }));
  const names = new Set(upstream.map((field) => field.name));
  for (const field of selected.value) {
    const source = field.key || field.name;
    if (source && !names.has(source)) {
      upstream.push({
        name: source,
        type: field.type ?? '',
        missing: !!props.inputFields?.length,
      });
      names.add(source);
    }
  }
  return upstream;
});
const checked = (name: string) =>
  selected.value.some((field) => (field.key || field.name) === name);
const count = computed(
  () => rows.value.filter((field) => checked(field.name)).length,
);
function update(names: string[]) {
  if (props.disabled) return;
  const current = new Map(
    selected.value.map((field) => [field.key || field.name, field]),
  );
  const upstream = new Map(
    (props.inputFields ?? []).map((field) => [field.name, field]),
  );
  const columns = names.map((name, index) => {
    const existing = current.get(name);
    const fact = upstream.get(name);
    return existing
      ? { ...existing }
      : {
          name,
          key: name,
          type: fact?.dataType || '',
          index,
          ...(fact?.length == null ? {} : { length: fact.length }),
          ...(fact?.scale == null ? {} : { precision: fact.scale }),
        };
  });
  props.config.columns = columns;
  const mappings = Array.isArray(props.config.fieldMappings)
    ? props.config.fieldMappings
    : [];
  props.config.fieldMappings = columns.map((field) => ({
    ...mappings.find(
      (mapping: Record<string, unknown>) =>
        mapping.source === (field.key || field.name) &&
        mapping.target === field.name,
    ),
    source: field.key || field.name,
    target: field.name,
    enabled: true,
  }));
}
function toggle(name: string, value: boolean) {
  const names = selected.value.map((field) => field.key || field.name);
  update(
    value
      ? [...names.filter((item) => item !== name), name]
      : names.filter((item) => item !== name),
  );
}
function toggleAll(value: boolean) {
  update(value ? rows.value.map((field) => field.name) : []);
}
</script>

<template>
  <div class="qi-reference-output-selection">
    <table aria-label="输出属性字段">
      <colgroup>
        <col style="width: 55px" />
        <col style="width: 80px" />
        <col />
        <col />
      </colgroup>
      <thead>
        <tr>
          <th>
            <Checkbox
              :checked="rows.length > 0 && count === rows.length"
              :indeterminate="count > 0 && count < rows.length"
              :disabled="disabled || !rows.length"
              aria-label="全选属性字段"
              @change="(event) => toggleAll(event.target.checked)"
            />
          </th>
          <th>序号</th>
          <th>字段名称</th>
          <th>字段类型</th>
        </tr>
      </thead>
      <tbody>
        <tr
          v-for="(field, index) in rows"
          :key="field.name"
          :class="{ 'is-missing': field.missing }"
        >
          <td>
            <Checkbox
              :checked="checked(field.name)"
              :disabled="disabled"
              :aria-label="`选择属性字段${field.name}`"
              @change="(event) => toggle(field.name, event.target.checked)"
            />
          </td>
          <td>{{ index + 1 }}</td>
          <td>
            {{ field.name }}<small v-if="field.missing">（上游已移除）</small>
          </td>
          <td>{{ field.type || '-' }}</td>
        </tr>
      </tbody>
    </table>
    <div v-if="!rows.length" class="qi-reference-output-empty">暂无记录</div>
  </div>
</template>

<style scoped>
.qi-reference-output-selection {
  max-height: 360px;
  overflow: auto;
}

table {
  width: 100%;
  font-size: 14px;
  table-layout: fixed;
  border-collapse: collapse;
}

th {
  height: 40px;
  padding: 8px 10px;
  font-weight: 400;
  color: #606266;
  text-align: left;
  background: #f1f1f5;
}

td {
  height: 42px;
  padding: 8px 10px;
  color: #606266;
  border-bottom: 1px solid #ebeef5;
}

.is-missing td,
small {
  color: #f56c6c;
}
</style>
