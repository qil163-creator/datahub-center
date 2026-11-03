<script setup lang="ts">
import { computed, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { CodeEditor } from '@vben/plugins/code-editor';

import { Button, Checkbox, message } from 'antdv-next';

import { formatJson } from '../request';

const props = defineProps<{ language: 'json' | 'text' | 'xml' }>();
const value = defineModel<string>('value', { required: true });
const wrap = ref(true);
const editorModes = {
  json: 'application/json',
  xml: 'xml',
  text: 'text/plain',
};
const mode = computed(() => editorModes[props.language]);
function format() {
  try {
    if (props.language === 'json') value.value = formatJson(value.value);
    else if (props.language === 'xml') {
      const document = new DOMParser().parseFromString(
        value.value,
        'application/xml',
      );
      if (document.querySelector('parsererror'))
        throw new Error('请求体不是有效的 XML');
      value.value = new XMLSerializer().serializeToString(document);
    }
  } catch (error) {
    message.warning((error as Error).message);
  }
}
</script>

<template>
  <div class="body-editor">
    <div class="body-editor__tools">
      <Button
        v-if="language !== 'text'"
        size="small"
        type="text"
        @click="format"
      >
        <template #icon><IconifyIcon icon="lucide:braces" /></template>
        格式化
</Button><Checkbox v-model:checked="wrap">自动换行</Checkbox>
    </div>
    <div class="body-editor__code">
      <CodeEditor
        v-model:value="value"
        :mode="mode"
        :auto-format="false"
        :word-wrap="wrap"
        aria-label="请求体"
        class="body-editor__input"
      />
    </div>
  </div>
</template>

<style scoped>
.body-editor {
  display: flex;
  flex: 1;
  flex-direction: column;
  min-height: 220px;
  overflow: hidden;
  border: 1px solid hsl(var(--border));
  border-radius: calc(var(--radius) - 2px);
}

.body-editor__tools {
  display: flex;
  gap: 12px;
  align-items: center;
  justify-content: flex-end;
  min-height: 34px;
  padding: 2px 12px;
  border-bottom: 1px solid hsl(var(--border));
}

.body-editor__code {
  display: flex;
  flex: 1;
  min-height: 0;
  font-family: ui-monospace, SFMono-Regular, Menlo, monospace;
  font-size: 13px;
  line-height: 22px;
}

.body-editor__input {
  flex: 1;
  min-width: 0;
  height: 100%;
  overflow: hidden;
}
</style>
