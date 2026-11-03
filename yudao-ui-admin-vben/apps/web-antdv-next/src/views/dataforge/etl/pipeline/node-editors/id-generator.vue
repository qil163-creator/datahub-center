<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import { onMounted } from 'vue';

import { Input, InputNumber, RadioGroup } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeIdGenerator' });

const props = defineProps<{
  config: Record<string, any>;
}>();

const MODE_OPTIONS = [
  { label: '增加序列', value: 'SEQUENCE' },
  { label: '增加UUID', value: 'UUID' },
];

onMounted(() => {
  const defaults: Record<string, any> = {
    mode: 'SEQUENCE',
    start: 1,
    step: 1,
    max: 0,
  };
  Object.entries(defaults).forEach(([key, value]) => {
    if (props.config[key] == null) {
      props.config[key] = value;
    }
  });
});
</script>

<template>
  <div class="etl-editor">
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          序列字段名称
        </label>
        <Input
          v-model:value="config.targetField"
          placeholder="自动生成的值输出到这一列"
        />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          ID 生成方式
        </label>
        <RadioGroup
          v-model:value="config.mode"
          :options="MODE_OPTIONS"
          option-type="button"
        />
      </div>
    </div>
    <div v-if="config.mode === 'SEQUENCE'" class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">起始值</label>
        <InputNumber v-model:value="config.start" class="w-full" />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          递增幅度
        </label>
        <InputNumber v-model:value="config.step" :min="1" class="w-full" />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label">最大值（0 不限）</label>
        <InputNumber v-model:value="config.max" :min="0" class="w-full" />
      </div>
    </div>
  </div>
</template>
