<script setup lang="ts">
import type { CleanMappingItem } from '../../../../cleanrule/modules/clean-rule-parameters';

import { computed, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Button, Input, RadioGroup, Select } from 'antdv-next';

import {
  cleanParameterValue,
  replaceCleanParameter,
} from '../../../../cleanrule/modules/clean-rule-parameters';
import ParameterEditor from '../../../../cleanrule/modules/parameter-editor.vue';
import { emptyImage } from '../../../../quality/task/data';
import CodeImport from './code-import.vue';
const props = defineProps<{ readonly?: boolean; strategyKey: string }>();
const config = defineModel<Record<string, unknown>>('config', {
  required: true,
});
const importer = ref<InstanceType<typeof CodeImport>>();
const mappings = computed(() =>
  Array.isArray(config.value.stringValue)
    ? (config.value.stringValue as CleanMappingItem[])
    : [],
);
const get = (key: string) =>
  cleanParameterValue(config.value, key, props.strategyKey);
const text = (key: string) =>
  get(key) === undefined ? undefined : String(get(key));
function update(key: string, value: unknown) {
  if (!props.readonly)
    config.value = replaceCleanParameter(config.value, key, value);
}
function edit(index: number, key: 'name' | 'value', value: string) {
  update(
    'stringValue',
    mappings.value.map((row, i) =>
      i === index ? { ...row, [key]: value } : row,
    ),
  );
}
const formats = [
  'yyyy',
  'yyyy-MM',
  'yyyy-MM-dd',
  'yy-MM-dd',
  'yyyy-MM-dd HH:mm:ss',
].map((value) => ({ value, label: value }));
</script>
<template>
  <div class="q-form-grid standard-clean-config">
    <template v-if="strategyKey === 'DATE_FILL_NULL'">
      <div class="q-field q-span">
        <span class="required">默认值</span><RadioGroup
          :value="config.fillType"
          :disabled="readonly"
          :options="[
            { label: '昨天', value: 'YESTERDAY' },
            { label: '当前日期', value: 'CURRENT' },
            { label: '固定值', value: 'FIXED' },
          ]"
          @update:value="update('fillType', $event)"
        />
      </div>
      <div v-if="config.fillType === 'FIXED'" class="q-field">
        <span class="required">固定值</span><Input
          :value="text('value')"
          :disabled="readonly"
          placeholder="请输入固定值"
          @update:value="update('value', $event)"
        />
      </div>
      <div v-else class="q-field">
        <span class="required">日期格式</span><Select
          :value="text('format')"
          :disabled="readonly"
          :options="formats"
          placeholder="请选择日期格式"
          @update:value="update('format', $event)"
        />
      </div>
    </template>
    <div v-else-if="strategyKey === 'MENU_CUSTOM'" class="q-span">
      <div v-if="!readonly" class="standard-mapping-buttons">
        <Button
          type="primary"
          @click="update('stringValue', [...mappings, { value: '', name: '' }])"
        >
          ＋ 新增规则
</Button><Button type="primary" @click="importer?.open()">＋ 导入规则</Button>
      </div>
      <div class="standard-mapping-scroll">
        <table class="q-table">
          <thead>
            <tr>
              <th>原值</th>
              <th>标准值</th>
              <th v-if="!readonly" class="standard-mapping-actions">操作</th>
            </tr>
          </thead>
          <tbody>
            <tr v-for="(row, index) in mappings" :key="index">
              <td>
                <Input
                  :value="row.value"
                  :disabled="readonly"
                  placeholder="请输入原值"
                  :maxlength="128"
                  @update:value="edit(index, 'value', $event)"
                />
              </td>
              <td>
                <Input
                  :value="row.name"
                  :disabled="readonly"
                  placeholder="请输入标准值"
                  :maxlength="256"
                  @update:value="edit(index, 'name', $event)"
                />
              </td>
              <td v-if="!readonly" class="standard-mapping-actions">
                <Button
                  type="link"
                  danger
                  @click="
                    update(
                      'stringValue',
                      mappings.filter((_row, i) => i !== index),
                    )
                  "
                >
                  <template #icon>
                    <IconifyIcon icon="lucide:trash-2" />
                  </template>
                  删除
                </Button>
              </td>
            </tr>
          </tbody>
        </table>
      </div>
      <div v-if="!mappings.length" class="standard-empty">
        <img :src="emptyImage" alt="" /><span>暂无记录</span>
      </div>
    </div>
    <ParameterEditor
      v-else
      class="q-span"
      :model-value="config"
      :strategy-key="strategyKey"
      :disabled="readonly"
      supported
      @update:model-value="config = $event"
    />
    <CodeImport ref="importer" @select="update('stringValue', $event)" />
  </div>
</template>

<style scoped>
.standard-mapping-scroll {
  overflow-x: auto;
}

.standard-mapping-scroll .q-table {
  min-width: 560px;
}

.standard-mapping-actions {
  position: sticky;
  right: 0;
  z-index: 1;
  width: 120px;
  min-width: 120px;
  white-space: nowrap;
  background: #fff;
}

th.standard-mapping-actions {
  background: #f1f1f5;
}
</style>
