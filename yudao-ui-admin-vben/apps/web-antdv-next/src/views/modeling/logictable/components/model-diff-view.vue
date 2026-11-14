<script setup lang="ts">
import type { TableModelApi } from '#/api/modeling/tablemodel';

import { computed, ref } from 'vue';

import { Checkbox } from 'antdv-next';

import emptyRecords from '../assets/empty-records.png';

import '../modal.css';
import '../version.css';

const props = defineProps<{ diff: TableModelApi.Diff }>();

const onlyChanged = ref(true);

const TYPE_LABELS: Record<TableModelApi.FieldChangeType, string> = {
  ADDED: '新增',
  REMOVED: '删除',
  MODIFIED: '修改',
  UNCHANGED: '未变化',
};

const rows = computed(() =>
  props.diff.fields.filter(
    (field) => !onlyChanged.value || field.changeType !== 'UNCHANGED',
  ),
);

function tagClass(type: TableModelApi.FieldChangeType) {
  return `qd-diff-tag qd-diff-tag--${type.toLowerCase()}`;
}

function describe(field?: TableModelApi.FieldChange['after']) {
  if (!field) return '';
  if (field.dataPrecision !== undefined && field.dataPrecision !== null)
    return `${field.dataType}(${field.dataPrecision},${field.dataScale ?? 0})`;
  if (field.dataLength !== undefined && field.dataLength !== null)
    return `${field.dataType}(${field.dataLength})`;
  return field.dataType || '';
}
</script>

<template>
  <div class="qd-model-diff">
    <div class="qd-diff-summary">
      <span>{{ diff.fromLabel }} → {{ diff.toLabel }}</span>
      <span class="qd-diff-tag qd-diff-tag--added">新增 {{ diff.summary.added }}</span>
      <span class="qd-diff-tag qd-diff-tag--removed">删除 {{ diff.summary.removed }}</span>
      <span class="qd-diff-tag qd-diff-tag--modified">修改 {{ diff.summary.modified }}</span>
      <span class="qd-diff-tag qd-diff-tag--unchanged">未变化 {{ diff.summary.unchanged }}</span>
      <span v-if="diff.headerChanges.length > 0" class="qd-diff-tag qd-diff-tag--modified">
        表信息变化 {{ diff.headerChanges.length }}
      </span>
      <Checkbox
        :checked="onlyChanged"
        style="margin-left: auto"
        @change="onlyChanged = $event.target.checked"
      >
        只看变化
      </Checkbox>
    </div>
    <template v-if="diff.headerChanges.length > 0">
      <h3 class="qd-modal-section">表信息变化</h3>
      <div class="qd-summary-table-wrap">
        <table class="qd-summary-table">
          <thead>
            <tr><th>属性</th><th>变化前</th><th>变化后</th></tr>
          </thead>
          <tbody>
            <tr v-for="change in diff.headerChanges" :key="change.key">
              <td>{{ change.label }}</td>
              <td>{{ change.before || '-' }}</td>
              <td>{{ change.after || '-' }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </template>
    <h3 class="qd-modal-section">字段变化</h3>
    <div class="qd-summary-table-wrap">
      <table class="qd-summary-table">
        <thead>
          <tr>
            <th style="width: 90px">变化</th>
            <th>字段英文名称</th>
            <th>字段中文名称</th>
            <th>数据类型</th>
            <th>变化内容</th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="(field, index) in rows" :key="`${field.fieldUid || field.fieldName}-${index}`">
            <td><span :class="tagClass(field.changeType)">{{ TYPE_LABELS[field.changeType] }}</span></td>
            <td>{{ field.fieldName }}</td>
            <td>{{ field.cnName }}</td>
            <td>{{ describe(field.after || field.before) }}</td>
            <td>
              <template v-if="field.changeType === 'MODIFIED'">
                <span v-for="change in field.changes" :key="change.key" class="qd-diff-change">
                  {{ change.label }}：<del>{{ change.before || '空' }}</del> →
                  <ins>{{ change.after || '空' }}</ins>
                </span>
              </template>
              <template v-else-if="field.changeType === 'ADDED'">新增字段</template>
              <template v-else-if="field.changeType === 'REMOVED'">字段已删除</template>
              <template v-else>-</template>
            </td>
          </tr>
        </tbody>
      </table>
      <div v-if="rows.length === 0" class="qd-summary-empty">
        <img :src="emptyRecords" alt="" />
        <p>{{ onlyChanged ? '字段没有变化' : '暂无字段' }}</p>
      </div>
    </div>
  </div>
</template>
