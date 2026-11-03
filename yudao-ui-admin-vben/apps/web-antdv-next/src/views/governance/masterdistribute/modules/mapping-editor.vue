<script setup lang="ts">
import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';
import type { GovernanceMasterDistributeApi } from '#/api/governance/masterdistribute';

import { computed } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Checkbox, Input, Select, Table } from 'antdv-next';

import { TRANSFORM_OPTIONS } from '../push-config';

const props = defineProps<{
  columns: GovernanceMasterDataApi.Column[];
  disabled?: boolean;
}>();
const rows = defineModel<GovernanceMasterDistributeApi.FieldMapping[]>({
  default: () => [],
});
const options = computed(() =>
  props.columns
    .filter((row) => row.name)
    .map((row) => ({
      value: row.name!,
      label: `${row.commentStr || row.name} (${row.name})`,
    })),
);
function add() {
  rows.value = [
    ...rows.value,
    { enabled: true, sourceField: '', targetField: '', transform: 'ORIGINAL' },
  ];
}
function selectSource(
  row: GovernanceMasterDistributeApi.FieldMapping,
  value: unknown,
) {
  const previous = row.sourceField;
  row.sourceField = typeof value === 'string' ? value : '';
  if (!row.targetField || row.targetField === previous)
    row.targetField = row.sourceField;
}
</script>

<template>
  <div>
    <Button size="small" type="primary" :disabled="disabled" @click="add">
      ＋ 添加映射
    </Button>
    <div class="qdata-master-mapping">
      <Table
        class="mapping-editor"
        :data-source="rows"
        :pagination="false"
        :scroll="{ x: 900 }"
        :row-key="(_row, index) => index ?? ''"
        :columns="[
          { title: '是否推送', key: 'enabled', width: 95 },
          { title: '模型字段', key: 'sourceField', width: 240 },
          { title: '下游字段', key: 'targetField', width: 180 },
          { title: '转换规则', key: 'transform', width: 170 },
          { title: '默认值', key: 'defaultValue', width: 190 },
          { title: '操作', key: 'actions', width: 90, fixed: 'right' },
        ]"
      >
        <template #bodyCell="{ column, record: row, index }">
          <Checkbox
            v-if="column.key === 'enabled'"
            v-model:checked="row.enabled"
            :disabled="disabled"
            :aria-label="`推送字段 ${index + 1}`"
          />
          <Select
            v-else-if="column.key === 'sourceField'"
            :value="row.sourceField || undefined"
            :options="options"
            :disabled="disabled"
            placeholder="请选择模型字段"
            show-search
            option-filter-prop="label"
            @update:value="(value) => selectSource(row, value)"
          />
          <Input
            v-else-if="column.key === 'targetField'"
            v-model:value="row.targetField"
            :disabled="disabled"
            placeholder="请输入下游字段名"
          />
          <Select
            v-else-if="column.key === 'transform'"
            v-model:value="row.transform"
            :disabled="disabled"
            :options="TRANSFORM_OPTIONS"
          />
          <Input
            v-else-if="column.key === 'defaultValue'"
            v-model:value="row.defaultValue"
            :disabled="disabled"
            placeholder="请输入默认值"
          />
          <Button
            v-else-if="column.key === 'actions'"
            type="link"
            danger
            :disabled="disabled"
            @click="rows.splice(index, 1)"
          >
            <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
            删除
          </Button>
        </template>
        <template #emptyText>
          <div class="qdata-master-empty">
            <img
              src="../../mastercommon/reference-assets/empty-records.png"
              alt=""
              aria-hidden="true"
            />
            <span>暂无记录</span>
          </div>
        </template>
      </Table>
    </div>
  </div>
</template>

<style scoped>
.mapping-editor .ant-select {
  width: 100%;
}
</style>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
