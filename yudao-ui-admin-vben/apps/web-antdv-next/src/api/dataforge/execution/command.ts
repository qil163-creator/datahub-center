export type RunId = number | string;
export interface RunReceipt {
  recordId: RunId;
  replayed: boolean;
  runStatus: number;
  stopRequested: boolean;
}
export interface RunCommand {
  requestId: string;
  taskId: RunId;
}
export interface StopCommand {
  recordId: RunId;
  requestId: string;
}
export interface ResolveCommand extends StopCommand {
  confirmedStopped: true;
  evidenceRef: string;
  expectedVersion: RunId;
  outcome: 'FAILED' | 'STOPPED' | 'SUCCESS';
  outputReviewed: true;
  reason: string;
}

export const createRunCommand = (taskId: RunId): RunCommand => ({
  requestId: crypto.randomUUID(),
  taskId,
});

export class CommandPayloadChangedError extends Error {
  constructor() {
    super('上次请求结果尚未确认，请先核对执行记录，再放弃旧命令并提交新结论');
  }
}

interface PendingCommand {
  command: object & { requestId: string };
  inFlight: boolean;
  payload: string;
  scope: string;
}

/** 页面持有未确认命令；响应丢失后复用身份，不把重试变成下一次运行。 */
export function createCommandTracker(getScope: () => string) {
  let generation = 0;
  const pending = new Map<string, PendingCommand>();
  return {
    async execute<P extends object, R>(
      key: string,
      payload: P,
      send: (command: P & { requestId: string }) => Promise<R>,
    ): Promise<R | undefined> {
      const scope = getScope();
      const started = generation;
      const serialized = JSON.stringify(payload);
      let entry = pending.get(key);
      if (entry?.scope !== scope) entry = undefined;
      if (entry && entry.payload !== serialized) {
        throw new CommandPayloadChangedError();
      }
      if (entry?.inFlight) return undefined;
      if (!entry) {
        entry = {
          command: Object.freeze({
            ...structuredClone(payload),
            requestId: crypto.randomUUID(),
          }),
          inFlight: false,
          payload: serialized,
          scope,
        };
        pending.set(key, entry);
      }
      entry.inFlight = true;
      try {
        const result = await send(entry.command as P & { requestId: string });
        if (started !== generation || scope !== getScope()) return undefined;
        if (pending.get(key) === entry) pending.delete(key);
        return result;
      } finally {
        entry.inFlight = false;
      }
    },
    invalidate() {
      ++generation;
      pending.clear();
    },
  };
}
