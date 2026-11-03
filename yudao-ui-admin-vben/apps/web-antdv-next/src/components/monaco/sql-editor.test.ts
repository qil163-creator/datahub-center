/* eslint-disable vue/one-component-per-file -- 使用包装组件验证动态编辑器属性。 */
import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import SqlEditor from './sql-editor.vue';

const state = vi.hoisted(() => ({
  create: vi.fn(),
  executeEdits: vi.fn(),
  focus: vi.fn(),
  dispose: vi.fn(),
  updateOptions: vi.fn(),
  modelOptions: vi.fn(),
  markers: vi.fn(),
  cursorChanged: undefined as (() => void) | undefined,
  markersChanged: undefined as
    | ((resources: { toString: () => string }[]) => void)
    | undefined,
}));
vi.mock('./setup', () => ({ setupMonaco: vi.fn() }));
vi.mock('sql-formatter', () => ({ format: (value: string) => value }));
vi.mock('monaco-editor/esm/vs/editor/editor.api.js', () => ({
  editor: {
    create: state.create,
    getModelMarkers: state.markers,
    onDidChangeMarkers: (listener: typeof state.markersChanged) => {
      state.markersChanged = listener;
      return { dispose: vi.fn() };
    },
  },
  MarkerSeverity: { Error: 8 },
  KeyMod: { CtrlCmd: 2048 },
  KeyCode: { Enter: 13, KeyS: 83 },
}));
let app: App | undefined;
let host: HTMLDivElement;
const selection = {
  startLineNumber: 1,
  startColumn: 2,
  endLineNumber: 1,
  endColumn: 2,
};
beforeEach(() => {
  vi.clearAllMocks();
  state.create.mockReturnValue({
    getSelection: () => selection,
    executeEdits: state.executeEdits,
    focus: state.focus,
    dispose: state.dispose,
    getValue: () => 'value',
    onDidChangeModelContent: () => ({ dispose: vi.fn() }),
    onDidChangeCursorPosition: (listener: () => void) => {
      state.cursorChanged = listener;
      return { dispose: vi.fn() };
    },
    onDidChangeModelOptions: () => ({ dispose: vi.fn() }),
    updateOptions: state.updateOptions,
    getPosition: () => ({ lineNumber: 3, column: 9 }),
    getModel: () => ({
      uri: { toString: () => 'inmemory:current' },
      getOptions: () => ({ tabSize: 4, insertSpaces: true }),
      getLanguageId: () => 'mysql',
      getEOL: () => '\r\n',
      updateOptions: state.modelOptions,
    }),
    addCommand: vi.fn(),
  });
  state.markers.mockReturnValue([]);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('sql编辑器公开插入能力', () => {
  it('响应缩略图切换并发送真实光标与诊断状态，不将未知诊断标作通过', async () => {
    const settings = reactive({ minimap: false });
    const onStatusChange = vi.fn();
    app = createApp(
      defineComponent(
        () => () => h(SqlEditor, { ...settings, tabSize: 4, onStatusChange }),
      ),
    );
    app.mount(host);
    expect(onStatusChange).toHaveBeenLastCalledWith(
      expect.objectContaining({
        lineNumber: 3,
        column: 9,
        tabSize: 4,
        eol: 'CRLF',
        syntaxErrors: undefined,
      }),
    );
    settings.minimap = true;
    await nextTick();
    expect(state.updateOptions).toHaveBeenCalledWith({
      minimap: { enabled: true },
    });
    state.markersChanged?.([{ toString: () => 'inmemory:other' }]);
    expect(onStatusChange).toHaveBeenCalledTimes(1);
    state.markers.mockReturnValue([{ severity: 8 }]);
    state.markersChanged?.([{ toString: () => 'inmemory:current' }]);
    expect(onStatusChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ syntaxErrors: 1 }),
    );
    state.cursorChanged?.();
    expect(onStatusChange).toHaveBeenLastCalledWith(
      expect.objectContaining({ lineNumber: 3, column: 9 }),
    );
  });
  it('向当前选区插入原始文本且允许消费者关闭minimap', () => {
    app = createApp(SqlEditor, {
      minimap: false,
      folding: false,
      fontSize: 12,
      lineNumbersMinChars: 2,
      lineDecorationsWidth: 14,
      renderValidationDecorations: 'off',
    });
    const editor = app.mount(host) as unknown as {
      insertText: (text: string) => void;
    };
    editor.insertText('rain_station_id');
    expect(state.create).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        minimap: expect.objectContaining({ enabled: false }),
        folding: false,
        fontSize: 12,
        lineNumbersMinChars: 2,
        lineDecorationsWidth: 14,
        renderValidationDecorations: 'off',
      }),
    );
    expect(state.executeEdits).toHaveBeenCalledWith('insert-text', [
      { range: selection, text: 'rain_station_id', forceMoveMarkers: true },
    ]);
    expect(state.focus).toHaveBeenCalledOnce();
  });
  it('只读实例不插入，旧消费者默认保留minimap', () => {
    app = createApp(SqlEditor, { readonly: true });
    const editor = app.mount(host) as unknown as {
      insertText: (text: string) => void;
    };
    editor.insertText('blocked');
    expect(state.executeEdits).not.toHaveBeenCalled();
    expect(state.create).toHaveBeenCalledWith(
      expect.anything(),
      expect.objectContaining({
        minimap: expect.objectContaining({ enabled: true }),
        folding: true,
        fontSize: 13,
        lineNumbersMinChars: 5,
        lineDecorationsWidth: 10,
      }),
    );
  });
});
