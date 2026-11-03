import type { DataforgeDevApi } from '#/api/dataforge/dev';

const MAX_LOG_CHARACTERS = 128 * 1024;

export interface TaskLogState {
  content: string;
  cursor: number;
  mayHaveMore: boolean;
  truncated: boolean;
  statusMessage: string;
}

export function createTaskLogState(): TaskLogState {
  return {
    content: '',
    cursor: 0,
    mayHaveMore: true,
    truncated: false,
    statusMessage: '',
  };
}

/** 使用服务端游标；脱敏后的正文长度和行数不能推算下一页。 */
export function appendTaskLogPage(
  state: TaskLogState,
  page: DataforgeDevApi.TaskLogPage,
) {
  state.statusMessage = page.statusMessage ?? '';
  if (!page.available) return;
  const content = [state.content, page.message].filter(Boolean).join('\n');
  state.truncated =
    state.truncated || page.truncated || content.length > MAX_LOG_CHARACTERS;
  state.content = content.slice(-MAX_LOG_CHARACTERS);
  state.cursor = page.nextSkipLineNum;
  state.mayHaveMore = page.mayHaveMore;
}
