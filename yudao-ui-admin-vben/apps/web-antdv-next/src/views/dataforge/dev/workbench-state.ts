import type { DataforgeDevApi } from '#/api/dataforge/dev';
import type { RunId } from '#/api/dataforge/execution/command';

export interface EditorConfig {
  databaseId?: RunId;
  timeoutSeconds?: number;
  maxRows?: number;
  executionConfig?: DataforgeDevApi.ExecutionConfig;
  programConfig?: DataforgeDevApi.ProgramConfig | null;
}

export interface EditorSnapshot extends EditorConfig {
  content: string;
}

/** 研发编辑器内存状态；不将脚本或配置写入浏览器持久存储。 */
export interface EditorContent extends EditorSnapshot {
  savedContent: string;
  savedConfig?: EditorConfig;
}

export function normalizeEditorDatabaseId(value?: null | RunId) {
  return value === null || value === undefined ? undefined : String(value);
}

function sameDatabaseId(left?: null | RunId, right?: null | RunId) {
  return normalizeEditorDatabaseId(left) === normalizeEditorDatabaseId(right);
}

export function captureEditorSnapshot(editor: EditorSnapshot): EditorSnapshot {
  return {
    content: editor.content,
    databaseId: normalizeEditorDatabaseId(editor.databaseId),
    timeoutSeconds: editor.timeoutSeconds,
    maxRows: editor.maxRows,
    ...captureExecutionConfig(editor),
    ...captureProgramConfig(editor),
  };
}

function captureExecutionConfig(editor: EditorConfig) {
  return editor.executionConfig === undefined
    ? {}
    : {
        executionConfig: JSON.parse(
          JSON.stringify(editor.executionConfig),
        ) as DataforgeDevApi.ExecutionConfig,
      };
}

function captureProgramConfig(editor: EditorConfig) {
  return editor.programConfig === undefined
    ? {}
    : {
        programConfig:
          editor.programConfig === null
            ? null
            : (JSON.parse(
                JSON.stringify(editor.programConfig),
              ) as DataforgeDevApi.ProgramConfig),
      };
}

function captureEditorConfig(editor: EditorConfig): EditorConfig {
  return {
    databaseId: normalizeEditorDatabaseId(editor.databaseId),
    timeoutSeconds: editor.timeoutSeconds,
    maxRows: editor.maxRows,
    ...captureExecutionConfig(editor),
    ...captureProgramConfig(editor),
  };
}

export function hasUnsavedContent(editors: EditorContent[]) {
  return editors.some(
    (editor) =>
      editor.content !== editor.savedContent ||
      !sameDatabaseId(editor.databaseId, editor.savedConfig?.databaseId) ||
      editor.timeoutSeconds !== editor.savedConfig?.timeoutSeconds ||
      editor.maxRows !== editor.savedConfig?.maxRows ||
      JSON.stringify(editor.executionConfig) !==
        JSON.stringify(editor.savedConfig?.executionConfig) ||
      JSON.stringify(editor.programConfig) !==
        JSON.stringify(editor.savedConfig?.programConfig),
  );
}

/** 外部修改/回滚更新基线；只替换本地未编辑的字段。 */
export function mergeEditorSnapshot(
  editor: EditorContent,
  latest: EditorSnapshot,
): EditorContent {
  return {
    content:
      editor.content === editor.savedContent ? latest.content : editor.content,
    databaseId: sameDatabaseId(
      editor.databaseId,
      editor.savedConfig?.databaseId,
    )
      ? normalizeEditorDatabaseId(latest.databaseId)
      : editor.databaseId,
    timeoutSeconds:
      editor.timeoutSeconds === editor.savedConfig?.timeoutSeconds
        ? latest.timeoutSeconds
        : editor.timeoutSeconds,
    maxRows:
      editor.maxRows === editor.savedConfig?.maxRows
        ? latest.maxRows
        : editor.maxRows,
    savedContent: latest.content,
    savedConfig: captureEditorConfig(latest),
    ...(editor.executionConfig !== undefined ||
    latest.executionConfig !== undefined
      ? {
          executionConfig:
            JSON.stringify(editor.executionConfig) ===
            JSON.stringify(editor.savedConfig?.executionConfig)
              ? captureExecutionConfig(latest).executionConfig
              : editor.executionConfig,
        }
      : {}),
    ...(editor.programConfig !== undefined || latest.programConfig !== undefined
      ? {
          programConfig:
            JSON.stringify(editor.programConfig) ===
            JSON.stringify(editor.savedConfig?.programConfig)
              ? captureProgramConfig(latest).programConfig
              : editor.programConfig,
        }
      : {}),
  };
}

/** 成功响应只确认实际发送的完整快照，保留请求期间继续编辑的脏状态。 */
export async function saveEditorSnapshot(
  editor: EditorContent,
  persist: (snapshot: EditorSnapshot) => Promise<unknown>,
) {
  const snapshot = captureEditorSnapshot(editor);
  await persist(snapshot);
  editor.savedContent = snapshot.content;
  editor.savedConfig = captureEditorConfig(snapshot);
}

export interface TaskRunState {
  taskId?: RunId;
  instanceUnconfirmed?: boolean;
  instanceBusinessDate?: string;
}

/** 未确认回执绑定原业务日期，切换其他标签或修改全局日期都不能把重试变成新运行。 */
export async function submitTaskRun(
  state: TaskRunState,
  businessDate: string,
  execute: (payload: {
    businessDate: string;
    id: RunId;
  }) => Promise<RunId | undefined>,
) {
  if (state.taskId === undefined) return undefined;
  const date = state.instanceBusinessDate ?? businessDate;
  state.instanceBusinessDate = date;
  try {
    const result = await execute({ id: state.taskId, businessDate: date });
    if (result !== undefined) {
      state.instanceUnconfirmed = false;
      state.instanceBusinessDate = undefined;
    }
    return result;
  } catch (error) {
    state.instanceUnconfirmed = true;
    throw error;
  }
}
