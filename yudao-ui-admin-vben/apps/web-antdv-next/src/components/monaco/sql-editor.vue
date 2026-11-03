<script lang="ts" setup>
import type { SqlEditorStatus } from './sql-editor-status';

import { onBeforeUnmount, onMounted, ref, watch } from 'vue';

import * as monaco from 'monaco-editor/esm/vs/editor/editor.api.js';
import { format as formatSql } from 'sql-formatter';

import { setupMonaco } from './setup';

const props = withDefaults(
  defineProps<{
    folding?: boolean;
    fontSize?: number;
    height?: string;
    language?: string;
    lineDecorationsWidth?: number;
    lineNumbersMinChars?: number;
    minimap?: boolean;
    modelValue?: string;
    readonly?: boolean;
    renderValidationDecorations?: 'off' | 'on';
    tabSize?: number;
  }>(),
  {
    folding: true,
    fontSize: 13,
    height: '100%',
    language: 'mysql',
    lineDecorationsWidth: 10,
    lineNumbersMinChars: 5,
    modelValue: '',
    minimap: true,
    readonly: false,
    tabSize: 2,
    renderValidationDecorations: undefined,
  },
);

const emit = defineEmits<{
  run: [];
  save: [];
  statusChange: [status: SqlEditorStatus];
  'update:modelValue': [value: string];
}>();

const containerRef = ref<HTMLElement>();
let editor: monaco.editor.IStandaloneCodeEditor | null = null;
let suppressChange = false;
let diagnosticsReceived = false;
const subscriptions: monaco.IDisposable[] = [];
function emitStatus() {
  const model = editor?.getModel();
  const position = editor?.getPosition();
  if (!model || !position) return;
  const options = model.getOptions();
  emit('statusChange', {
    column: position.column,
    eol: model.getEOL() === '\r\n' ? 'CRLF' : 'LF',
    insertSpaces: options.insertSpaces,
    language: model.getLanguageId(),
    lineNumber: position.lineNumber,
    syntaxErrors: diagnosticsReceived
      ? monaco.editor
          .getModelMarkers({ resource: model.uri })
          .filter((marker) => marker.severity === monaco.MarkerSeverity.Error)
          .length
      : undefined,
    tabSize: options.tabSize,
  });
}

onMounted(() => {
  setupMonaco();
  editor = monaco.editor.create(containerRef.value!, {
    value: props.modelValue,
    language: props.language,
    theme: 'sql-light',
    readOnly: props.readonly,
    renderValidationDecorations: props.renderValidationDecorations,
    automaticLayout: true,
    // 代码地图：类似 IDEA CodeGlance，整个文件缩放显示，视口滑块常驻，可点击/拖拽跳转
    minimap: {
      enabled: props.minimap,
      autohide: false,
      size: 'fit',
      showSlider: 'always',
      renderCharacters: true,
      maxColumn: 100,
      scale: 1,
    },
    smoothScrolling: true,
    fontSize: props.fontSize,
    folding: props.folding,
    lineDecorationsWidth: props.lineDecorationsWidth,
    lineNumbersMinChars: props.lineNumbersMinChars,
    tabSize: props.tabSize,
    wordWrap: 'on',
    scrollBeyondLastLine: false,
    suggestSelection: 'first',
    fixedOverflowWidgets: true,
  });
  subscriptions.push(
    editor.onDidChangeModelContent(() => {
      diagnosticsReceived = false;
      emitStatus();
      if (suppressChange) {
        return;
      }
      emit('update:modelValue', editor?.getValue() ?? '');
    }),
    editor.onDidChangeCursorPosition(emitStatus),
    editor.onDidChangeModelOptions(emitStatus),
    monaco.editor.onDidChangeMarkers((resources) => {
      const uri = editor?.getModel()?.uri;
      if (
        !uri ||
        !resources.some((resource) => resource.toString() === uri.toString())
      )
        return;
      diagnosticsReceived = true;
      emitStatus();
    }),
  );
  emitStatus();
  // Ctrl+Enter 运行 / Ctrl+S 保存
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.Enter, () =>
    emit('run'),
  );
  editor.addCommand(monaco.KeyMod.CtrlCmd | monaco.KeyCode.KeyS, () =>
    emit('save'),
  );
});

onBeforeUnmount(() => {
  subscriptions.splice(0).forEach((subscription) => subscription.dispose());
  editor?.dispose();
  editor = null;
});

watch(
  () => props.modelValue,
  (value) => {
    if (editor && value !== editor.getValue()) {
      suppressChange = true;
      editor.setValue(value ?? '');
      suppressChange = false;
    }
  },
);

watch(
  () => props.language,
  (language) => {
    const model = editor?.getModel();
    if (model && language) {
      diagnosticsReceived = false;
      monaco.editor.setModelLanguage(model, language);
      emitStatus();
    }
  },
);

watch(
  () => props.readonly,
  (readonly) => editor?.updateOptions({ readOnly: readonly }),
);

watch(
  () => props.minimap,
  (enabled) => editor?.updateOptions({ minimap: { enabled } }),
);
watch(
  () => props.tabSize,
  (tabSize) => {
    editor?.getModel()?.updateOptions({ tabSize });
    emitStatus();
  },
);

/** 格式化整个脚本 */
function format() {
  if (!editor || props.readonly) {
    return;
  }
  const dialect = props.language === 'pgsql' ? 'postgresql' : 'mysql';
  try {
    const formatted = formatSql(editor.getValue(), {
      language: dialect,
      keywordCase: 'upper',
    });
    editor.setValue(formatted);
  } catch {
    // SQL 语法不完整时保持原文
  }
}

/** 获取选中文本，未选中时返回全文（用于"运行选中"） */
function getSelectionOrValue(): string {
  if (!editor) {
    return '';
  }
  const selection = editor.getSelection();
  if (selection && !selection.isEmpty()) {
    return editor.getModel()?.getValueInRange(selection) ?? '';
  }
  return editor.getValue();
}

/** 获取全文（供调用方校验 ref 指向的是否为当前可见编辑器实例） */
function getValue(): string {
  return editor?.getValue() ?? '';
}

function focus() {
  editor?.focus();
}

/** 在当前选区插入字段等文本，不解释或执行输入内容。 */
function insertText(text: string) {
  const selection = editor?.getSelection();
  if (!editor || !selection || props.readonly) return;
  editor.executeEdits('insert-text', [
    { range: selection, text, forceMoveMarkers: true },
  ]);
  editor.focus();
}

defineExpose({ format, getSelectionOrValue, getValue, focus, insertText });
</script>

<template>
  <div
    ref="containerRef"
    :style="{ height: props.height }"
    class="w-full"
  ></div>
</template>
