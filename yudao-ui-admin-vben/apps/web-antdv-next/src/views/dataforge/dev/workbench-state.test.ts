import type {
  EditorContent,
  EditorSnapshot,
  TaskRunState,
} from './workbench-state';

import { describe, expect, it, vi } from 'vitest';

import { createCommandTracker } from '#/api/dataforge/execution/command';
import { parseResourceId } from '#/utils/resource-id';

import {
  hasUnsavedContent,
  mergeEditorSnapshot,
  normalizeEditorDatabaseId,
  saveEditorSnapshot,
  submitTaskRun,
} from './workbench-state';

describe('研发编辑会话', () => {
  it('原生资源配置保存使用完整快照，慢响应不能确认其后修改', async () => {
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const editor: EditorContent = {
      content: '',
      savedContent: '',
      programConfig: {
        runtimeProfileCode: 'flink-managed',
        artifactFileId: '9007199254740993',
        dependencyFileIds: ['9007199254740995'],
        taskManagerSlots: 2,
        taskManagerCount: 3,
      },
      savedConfig: {},
    };
    const persist = vi.fn((_snapshot: EditorSnapshot) => pending);
    const saving = saveEditorSnapshot(editor, persist);
    editor.programConfig!.taskManagerSlots = 4;
    editor.programConfig!.dependencyFileIds!.push('9007199254740997');
    finish();
    await saving;
    expect(persist.mock.calls[0]?.[0]?.programConfig).toEqual({
      runtimeProfileCode: 'flink-managed',
      artifactFileId: '9007199254740993',
      dependencyFileIds: ['9007199254740995'],
      taskManagerSlots: 2,
      taskManagerCount: 3,
    });
    expect(hasUnsavedContent([editor])).toBe(true);
    expect(editor.savedConfig?.programConfig?.taskManagerSlots).toBe(2);
  });

  it('原生配置外部刷新保留本地编辑，未编辑配置则跟随最新保存配置', () => {
    const editor: EditorContent = {
      content: '',
      savedContent: '',
      programConfig: { parallelism: 4 },
      savedConfig: { programConfig: { parallelism: 2 } },
    };
    const merged = mergeEditorSnapshot(editor, {
      content: '',
      programConfig: { parallelism: 8 },
    });
    expect(merged.programConfig?.parallelism).toBe(4);
    expect(merged.savedConfig?.programConfig?.parallelism).toBe(8);
    expect(hasUnsavedContent([merged])).toBe(true);
    const clean = mergeEditorSnapshot(
      {
        content: '',
        savedContent: '',
        programConfig: null,
        savedConfig: { programConfig: null },
      },
      { content: '', programConfig: { parallelism: 8 } },
    );
    expect(clean.programConfig?.parallelism).toBe(8);
    expect(hasUnsavedContent([clean])).toBe(false);
  });

  it('参数配置也参与脏状态，慢保存不能确认请求后的参数值', async () => {
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const editor: EditorContent = {
      content: 'select :amount',
      savedContent: 'select :amount',
      executionConfig: {
        localParams: [
          { prop: 'amount', type: 'LONG', value: '9007199254740993' },
        ],
      },
      savedConfig: {},
    };
    const persist = vi.fn((_snapshot: EditorSnapshot) => pending);
    const saving = saveEditorSnapshot(editor, persist);
    editor.executionConfig!.localParams![0]!.value = '9007199254740995';
    finish();
    await saving;
    expect(
      persist.mock.calls[0]?.[0]?.executionConfig?.localParams?.[0]?.value,
    ).toBe('9007199254740993');
    expect(hasUnsavedContent([editor])).toBe(true);
    expect(editor.savedConfig?.executionConfig?.localParams?.[0]?.value).toBe(
      '9007199254740993',
    );
  });
  it('慢速保存期间继续输入，只确认已发送的文本', async () => {
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const editor = { content: 'select 1', savedContent: '' };
    const persist = vi.fn(() => pending);
    const saving = saveEditorSnapshot(editor, persist);
    editor.content = 'select 2';
    finish();
    await saving;
    expect(persist).toHaveBeenCalledWith({
      content: 'select 1',
      databaseId: undefined,
      timeoutSeconds: undefined,
      maxRows: undefined,
    });
    expect(editor.savedContent).toBe('select 1');
    expect(hasUnsavedContent([editor])).toBe(true);
  });

  it('保存失败保留脏状态，不能假装已经保存', async () => {
    const editor = { content: 'select 2', savedContent: 'select 1' };
    await expect(
      saveEditorSnapshot(editor, () =>
        Promise.reject(new Error('revision conflict')),
      ),
    ).rejects.toThrow('revision conflict');
    expect(editor.savedContent).toBe('select 1');
    expect(hasUnsavedContent([editor])).toBe(true);
  });

  it('临时查询没有数据库任务ID也必须提示未保存', () => {
    expect(hasUnsavedContent([{ content: 'select 1', savedContent: '' }])).toBe(
      true,
    );
    expect(hasUnsavedContent([{ content: '', savedContent: '' }])).toBe(false);
  });

  it('完整保存后消除脏状态', async () => {
    const editor = { content: 'select 1', savedContent: '' };
    await saveEditorSnapshot(editor, () => Promise.resolve());
    expect(hasUnsavedContent([editor])).toBe(false);
  });

  it.each([
    { databaseId: '9007199254740995' },
    { timeoutSeconds: 600 },
    { maxRows: 2000 },
  ])('配置变化也必须标记未保存 %s', (change) => {
    const editor = {
      content: 'select 1',
      savedContent: 'select 1',
      databaseId: '9007199254740993',
      timeoutSeconds: 300,
      maxRows: 1000,
      savedConfig: {
        databaseId: '9007199254740993',
        timeoutSeconds: 300,
        maxRows: 1000,
      },
      ...change,
    };
    expect(hasUnsavedContent([editor])).toBe(true);
  });

  it('慢速保存捕获全部配置，不确认请求期间的新配置', async () => {
    let finish!: () => void;
    const pending = new Promise<void>((resolve) => {
      finish = resolve;
    });
    const editor: EditorContent = {
      content: 'select 1',
      savedContent: '',
      databaseId: '9007199254740993',
      timeoutSeconds: 300,
      maxRows: 1000,
      savedConfig: {},
    };
    const persist = vi.fn(() => pending);
    const saving = saveEditorSnapshot(editor, persist);
    editor.content = 'select 2';
    editor.databaseId = '9007199254740995';
    editor.timeoutSeconds = 600;
    editor.maxRows = 2000;
    finish();
    await saving;
    expect(persist).toHaveBeenCalledWith({
      content: 'select 1',
      databaseId: '9007199254740993',
      timeoutSeconds: 300,
      maxRows: 1000,
    });
    expect(editor.savedConfig).toEqual({
      databaseId: '9007199254740993',
      timeoutSeconds: 300,
      maxRows: 1000,
    });
    expect(editor.databaseId).toBe('9007199254740995');
    expect(editor.timeoutSeconds).toBe(600);
    expect(editor.maxRows).toBe(2000);
    expect(hasUnsavedContent([editor])).toBe(true);
  });

  it('回滚或外部刷新同步未编辑字段，保留未保存脚本与配置', () => {
    const editor: EditorContent = {
      content: 'local edit',
      savedContent: 'old script',
      databaseId: '9007199254740995',
      timeoutSeconds: 300,
      maxRows: 2000,
      savedConfig: {
        databaseId: '9007199254740993',
        timeoutSeconds: 300,
        maxRows: 1000,
      },
    };
    const merged = mergeEditorSnapshot(editor, {
      content: 'rolled back script',
      databaseId: '9007199254740997',
      timeoutSeconds: 600,
      maxRows: 1500,
    });
    expect(merged).toEqual({
      content: 'local edit',
      savedContent: 'rolled back script',
      databaseId: '9007199254740995',
      timeoutSeconds: 600,
      maxRows: 2000,
      savedConfig: {
        databaseId: '9007199254740997',
        timeoutSeconds: 600,
        maxRows: 1500,
      },
    });
    expect(hasUnsavedContent([merged])).toBe(true);
  });

  it('无损ID只改变JSON表示时保持干净，外部更新可清理所有基线', () => {
    const editor: EditorContent = {
      content: 'select 1',
      savedContent: 'select 1',
      databaseId: '123',
      timeoutSeconds: 300,
      maxRows: 1000,
      savedConfig: { databaseId: 123, timeoutSeconds: 300, maxRows: 1000 },
    };
    expect(hasUnsavedContent([editor])).toBe(false);
    const merged = mergeEditorSnapshot(editor, {
      content: 'select 2',
      databaseId: '9007199254740993',
      timeoutSeconds: 600,
      maxRows: 2000,
    });
    expect(merged.content).toBe('select 2');
    expect(merged.databaseId).toBe('9007199254740993');
    expect(hasUnsavedContent([merged])).toBe(false);
  });

  it('清空数据库后接收null响应，重载与再次保存不产生字符串null', async () => {
    const editor: EditorContent = {
      content: 'select 1',
      savedContent: 'select 1',
      databaseId: undefined,
      savedConfig: { databaseId: '9007199254740993' },
    };
    expect(hasUnsavedContent([editor])).toBe(true);
    await saveEditorSnapshot(editor, () => Promise.resolve());
    const merged = mergeEditorSnapshot(editor, {
      content: 'select 1',
      databaseId: normalizeEditorDatabaseId(null),
    });
    const persist = vi.fn(() => Promise.resolve());
    await saveEditorSnapshot(merged, persist);
    expect(merged.databaseId).toBeUndefined();
    expect(persist).toHaveBeenCalledWith(
      expect.objectContaining({ databaseId: undefined }),
    );
    expect(hasUnsavedContent([merged])).toBe(false);
  });
});

describe('无损资源路由', () => {
  it('大整数ID保持原始十进制字符串', () => {
    expect(parseResourceId('9007199254740993')).toBe('9007199254740993');
    expect(parseResourceId(123)).toBe('123');
  });
  it.each([undefined, null, ['12'], '1e3', '-1', '0', 9_007_199_254_740_992])(
    '拒绝不合法或已经丢失精度的ID %s',
    (id) => {
      expect(parseResourceId(id)).toBeUndefined();
    },
  );
});

describe('任务运行回执', () => {
  it('回执丢失重试保留原请求和原业务日期，成功确认后才允许新运行', async () => {
    const tracker = createCommandTracker(() => 'tenant-user');
    const state: TaskRunState = { taskId: '9007199254740993' };
    const send = vi
      .fn<
        (payload: {
          businessDate: string;
          id: number | string;
          requestId: string;
        }) => Promise<string>
      >()
      .mockRejectedValueOnce(new Error('response lost'))
      .mockResolvedValue('record-1');
    const execute = (payload: { businessDate: string; id: number | string }) =>
      tracker.execute('dev:run:1', payload, send);
    await expect(submitTaskRun(state, '2026-10-01', execute)).rejects.toThrow(
      'response lost',
    );
    expect(state.instanceUnconfirmed).toBe(true);
    await submitTaskRun(state, '2026-10-02', execute);
    expect(send.mock.calls[1]?.[0]).toEqual(send.mock.calls[0]?.[0]);
    expect(send.mock.calls[1]?.[0].businessDate).toBe('2026-10-01');
    expect(state.instanceUnconfirmed).toBe(false);
    await submitTaskRun(state, '2026-10-02', execute);
    expect(send.mock.calls[2]?.[0].requestId).not.toBe(
      send.mock.calls[0]?.[0].requestId,
    );
    expect(send.mock.calls[2]?.[0].businessDate).toBe('2026-10-02');
  });
});
