<script lang="ts" setup>
import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

import * as monaco from 'monaco-editor';

import { LOG_LANGUAGE_ID } from './javalog';
import { setupMonaco } from './setup';

const props = withDefaults(
  defineProps<{
    autoScroll?: boolean;
    content?: string;
    height?: string;
  }>(),
  {
    autoScroll: true,
    content: '',
    height: '100%',
  },
);

const containerRef = ref<HTMLElement>();
let editor: monaco.editor.IStandaloneCodeEditor | null = null;

onMounted(() => {
  setupMonaco();
  editor = monaco.editor.create(containerRef.value!, {
    value: props.content,
    language: LOG_LANGUAGE_ID,
    theme: 'javalog-light',
    readOnly: true,
    automaticLayout: true,
    minimap: { enabled: false },
    lineNumbers: 'off',
    folding: false,
    fontSize: 12,
    wordWrap: 'on',
    scrollBeyondLastLine: false,
    renderLineHighlight: 'none',
    occurrencesHighlight: 'off',
    selectionHighlight: false,
    contextmenu: false,
  });
  scrollToBottom();
});

onBeforeUnmount(() => {
  editor?.dispose();
  editor = null;
});

watch(
  () => props.content,
  (content) => {
    if (!editor) {
      return;
    }
    editor.setValue(content ?? '');
    if (props.autoScroll) {
      scrollToBottom();
    }
  },
);

function scrollToBottom() {
  const model = editor?.getModel();
  if (editor && model) {
    editor.revealLine(model.getLineCount());
  }
}
</script>

<template>
  <div ref="containerRef" :style="{ height: props.height }" class="w-full"></div>
</template>
