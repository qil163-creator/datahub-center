<script lang="ts" setup>
/* eslint-disable vue/no-mutating-props -- 编辑器与设计器共享同一 reactive 配置对象，提交时由设计器深拷贝落盘 */
import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { computed, onMounted } from 'vue';

import { AutoComplete, Checkbox, Input } from 'antdv-next';

import './editor-style.css';

defineOptions({ name: 'EtlNodeSplitToRows' });

const props = defineProps<{
  config: Record<string, any>;
  inputFields?: DataforgeEtlApi.FieldMeta[];
}>();

const fieldOptions = computed(() =>
  (props.inputFields ?? []).map((field) => ({
    value: field.name!,
    label: `${field.name}（${field.dataType ?? '-'}）`,
  })),
);

onMounted(() => {
  if (props.config.skipEmpty == null) {
    props.config.skipEmpty = true;
  }
});
</script>

<template>
  <div class="etl-editor">
    <div class="flex gap-4">
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          需拆分的字段
        </label>
        <AutoComplete
          v-model:value="config.sourceField"
          :options="fieldOptions"
          placeholder="选择或输入字段名"
        />
      </div>
      <div class="form-item flex-1">
        <label class="form-item__label form-item__label--required">
          分隔符
        </label>
        <Input v-model:value="config.delimiter" placeholder="如 ," />
      </div>
    </div>
    <div class="form-item">
      <label class="form-item__label">目标字段（空则覆盖来源字段）</label>
      <Input v-model:value="config.targetField" placeholder="可为空" />
    </div>
    <div class="form-item">
      <Checkbox
        :checked="config.skipEmpty !== false"
        @update:checked="(v: boolean) => (config.skipEmpty = v)"
      >
        跳过空值（来源为空或拆出空段时不生成行）
      </Checkbox>
      <div class="form-item__tip">
        示例：[id=1, tags=a,b,c] 按逗号拆分后输出 3 行，其余字段每行复制
      </div>
    </div>
  </div>
</template>
