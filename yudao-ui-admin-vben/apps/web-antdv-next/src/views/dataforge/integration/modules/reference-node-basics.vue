<script setup lang="ts">
import { Input, TextArea } from 'antdv-next';

const props = defineProps<{
  description: string;
  descriptionOnly?: boolean;
  disabled?: boolean;
  hideType?: boolean;
  name: string;
  showDescription?: boolean;
  stacked?: boolean;
  typeName?: string;
}>();
const emit = defineEmits<{
  'update:description': [value: string];
  'update:name': [value: string];
}>();
function updateName(value: number | string | undefined) {
  if (!props.disabled) emit('update:name', String(value ?? ''));
}
function updateDescription(value: number | string | undefined) {
  if (!props.disabled) emit('update:description', String(value ?? ''));
}
const count = ({ count, maxLength }: { count: number; maxLength?: number }) =>
  `${count} / ${maxLength ?? 256}个字符`;
</script>

<template>
  <div class="qi-node-basics" :class="{ 'qi-node-basics-stacked': stacked }">
    <template v-if="!descriptionOnly">
      <label class="qi-node-name"><span><i aria-hidden="true">*</i>节点名称</span><Input
          :value="name"
          :disabled="disabled"
          placeholder="请输入节点名称"
          aria-label="节点名称"
          aria-required="true"
          @update:value="updateName"
      /></label>
      <label v-if="!hideType"><span>类型</span><Input :value="typeName" disabled placeholder="请输入类型" /></label>
    </template>
    <label v-if="showDescription || descriptionOnly" class="qi-node-description"><span>描述</span><TextArea
        :value="description"
        :disabled="disabled"
        :maxlength="256"
        :rows="4"
        placeholder="请输入描述"
        :show-count="{ formatter: count }"
        @update:value="updateDescription"
    /></label>
  </div>
</template>
