<script setup lang="ts">
import type { Component } from 'vue';

import {
  markRaw,
  onBeforeUnmount,
  onErrorCaptured,
  ref,
  shallowRef,
} from 'vue';

import { Button, TextArea } from 'antdv-next';

const props = withDefaults(
  defineProps<{
    ariaLabel?: string;
    height?: string;
    language?: string;
    modelValue?: string;
    readonly?: boolean;
    renderValidationDecorations?: 'off' | 'on';
  }>(),
  { ariaLabel: 'SQL文本编辑器', height: '190px', language: 'mysql', modelValue: '', readonly: false, renderValidationDecorations: undefined },
);
const emit = defineEmits<{ 'update:modelValue': [value: string] }>();
const editor = shallowRef<Component>();
const loading = ref(false);
const failed = ref(false);
let generation = 0;
function update(value: number | string | undefined) {
  if (!props.readonly) emit('update:modelValue', String(value ?? ''));
}
async function load() {
  const current = ++generation;
  editor.value = undefined;
  failed.value = false;
  loading.value = true;
  try {
    const module = await import('#/components/monaco/sql-editor.vue');
    if (current === generation) editor.value = markRaw(module.default);
  } catch {
    if (current === generation) failed.value = true;
  } finally {
    if (current === generation) loading.value = false;
  }
}
onErrorCaptured(() => {
  generation++;
  editor.value = undefined;
  loading.value = false;
  failed.value = true;
  return false;
});
onBeforeUnmount(() => {
  generation++;
});
void load();
</script>

<template>
  <div class="qi-sql-editor" :style="{ '--qi-sql-editor-height': height }">
    <div class="qi-sql-editor-surface">
      <component
        v-if="editor"
        :is="editor"
        :model-value="modelValue"
        :height="height"
        :language="language"
        :readonly="readonly"
        :render-validation-decorations="renderValidationDecorations"
        :aria-label="ariaLabel"
        :minimap="false"
        :line-numbers-min-chars="3"
        @update:model-value="update"
      />
      <TextArea
        v-else
        :value="modelValue"
        :readonly="readonly"
        class="qi-sql-editor-fallback"
        :aria-label="ariaLabel"
        placeholder="请输入SQL"
        :spellcheck="false"
        @update:value="update"
      />
    </div>
    <div v-if="loading || failed" class="qi-sql-editor-status" role="status">
      <span>{{
        loading
          ? '代码编辑器加载中，可继续输入。'
          : '代码编辑器加载失败，可继续输入。'
      }}</span>
      <Button v-if="failed" type="link" size="small" @click="load">
重试编辑器
</Button>
    </div>
  </div>
</template>

<style scoped>
.qi-sql-editor {
  width: 100%;
  min-width: 0;
}

.qi-sql-editor-surface {
  height: var(--qi-sql-editor-height);
  min-height: var(--qi-sql-editor-height);
  overflow: hidden;
  border: 1px solid #dcdfe6;
}

.qi-sql-editor-fallback {
  width: 100%;
  height: 100%;
  min-height: 100%;
  padding: 8px 12px;
  font-family: monospace;
  resize: none;
  border: 0;
  border-radius: 0;
}

.qi-sql-editor-status {
  display: flex;
  flex-wrap: wrap;
  gap: 8px;
  align-items: center;
  margin-top: 4px;
  font-size: 12px;
  color: #909399;
}
</style>
