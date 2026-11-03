/**
 * 在线填报协同 WebSocket 客户端
 *
 * 连接约定与 infra 示例一致：VITE_BASE_URL 换 ws 协议 + refreshToken 查询参数；
 * 帧格式 {type, content}（content 为二次 JSON 字符串）；心跳 ping/pong。
 * 一致性铁律：所有广播只是提示，数据一致性靠 REST 的 afterVersion 增量拉取。
 */

export interface FillCollabCallbacks {
  /** 收到"有新操作"提示（含本人在其他端的操作） */
  onOperationApplied?: (payload: {
    operatorUserId: number;
    serverVersion: number;
    workItemId: number;
  }) => void;
  /** 入房回执：在线成员 + 当前 serverVersion */
  onPresenceSnapshot?: (payload: {
    memberUserIds: number[];
    serverVersion: number;
    workItemId: number;
  }) => void;
  onPresenceChanged?: (payload: { joined: boolean; userId: number }) => void;
  onLockResult?: (payload: {
    fieldCode?: string;
    granted: boolean;
    holderUserId?: number;
    rowId?: string;
  }) => void;
  onLockChanged?: (payload: {
    fieldCode?: string;
    holderUserId?: null | number;
    rowId?: string;
  }) => void;
  onError?: (message: string) => void;
  onStatusChange?: (open: boolean) => void;
}

const HEARTBEAT_INTERVAL_MS = 25_000;
const PRESENCE_PING_INTERVAL_MS = 30_000;
const RECONNECT_BASE_MS = 1000;
const RECONNECT_MAX_MS = 30_000;

export class FillCollabClient {
  private readonly callbacks: FillCollabCallbacks;
  private closedByUser = false;
  private heartbeatTimer: null | ReturnType<typeof setInterval> = null;

  private readonly lockWaiters = new Map<
    string,
    Array<(result: { granted: boolean; holderUserId?: number }) => void>
  >();
  private presenceTimer: null | ReturnType<typeof setInterval> = null;
  private reconnectAttempts = 0;
  private readonly url: string;
  private readonly workItemId: number | string;
  private ws: null | WebSocket = null;

  constructor(options: {
    callbacks: FillCollabCallbacks;
    refreshToken: string;
    workItemId: number | string;
  }) {
    this.workItemId = options.workItemId;
    this.callbacks = options.callbacks;
    this.url = `${`${import.meta.env.VITE_BASE_URL}/dataforge/ws`.replace('http', 'ws')}?token=${options.refreshToken}`;
  }

  acquireLock(rowId: string, fieldCode?: string) {
    this.send('fill-lock-acquire', {
      fieldCode,
      rowId,
      workItemId: this.workItemId,
    });
  }

  /** 申请单元格软锁并等待 fill-lock-result；超时或未连接视为未授予 */
  acquireLockAndWait(rowId: string, fieldCode?: string, timeoutMs = 2000) {
    if (this.ws?.readyState !== WebSocket.OPEN) {
      return Promise.resolve({
        granted: false as const,
        holderUserId: undefined,
      });
    }
    const waiter = this.waitLockResult(rowId, fieldCode, timeoutMs);
    this.acquireLock(rowId, fieldCode);
    return waiter;
  }

  close() {
    this.closedByUser = true;
    this.stopTimers();
    this.lockWaiters.forEach((waiters) => {
      waiters.forEach((waiter) => waiter({ granted: false }));
    });
    this.lockWaiters.clear();
    if (this.ws && this.ws.readyState === WebSocket.OPEN) {
      this.send('fill-work-item-leave', { workItemId: this.workItemId });
    }
    this.ws?.close();
    this.ws = null;
  }

  connect() {
    this.closedByUser = false;
    this.openSocket();
  }

  releaseLock(rowId: string, fieldCode?: string) {
    this.send('fill-lock-release', {
      fieldCode,
      rowId,
      workItemId: this.workItemId,
    });
    this.flushLockWaiters(rowId, fieldCode, { granted: false });
  }

  renewLock(rowId: string, fieldCode?: string) {
    this.send('fill-lock-renew', {
      fieldCode,
      rowId,
      workItemId: this.workItemId,
    });
  }

  // ---------------------------------------------------------------- 内部实现

  private cellLockKey(rowId?: string, fieldCode?: string) {
    return `${rowId ?? ''}::${fieldCode ?? '-'}`;
  }

  private dispatchMessage(type: string, content: any) {
    switch (type) {
      case 'fill-error': {
        this.callbacks.onError?.(content?.message ?? '协同消息处理失败');
        break;
      }
      case 'fill-lock-changed': {
        this.callbacks.onLockChanged?.(content);
        break;
      }
      case 'fill-lock-result': {
        this.flushLockWaiters(content?.rowId, content?.fieldCode, {
          granted: !!content?.granted,
          holderUserId: content?.holderUserId,
        });
        this.callbacks.onLockResult?.(content);
        break;
      }
      case 'fill-operation-applied': {
        if (String(content?.workItemId) === String(this.workItemId)) {
          this.callbacks.onOperationApplied?.(content);
        }
        break;
      }
      case 'fill-presence-joined': {
        this.callbacks.onPresenceChanged?.({
          joined: true,
          userId: content?.userId,
        });
        break;
      }
      case 'fill-presence-left': {
        this.callbacks.onPresenceChanged?.({
          joined: false,
          userId: content?.userId,
        });
        break;
      }
      case 'fill-presence-snapshot': {
        this.callbacks.onPresenceSnapshot?.(content);
        break;
      }
      default: {
        // 其他业务消息（站内信推送等）不属于协同协议，忽略
      }
    }
  }

  private flushLockWaiters(
    rowId?: string,
    fieldCode?: string,
    result: { granted: boolean; holderUserId?: number } = { granted: false },
  ) {
    const key = this.cellLockKey(rowId, fieldCode);
    const list = this.lockWaiters.get(key);
    this.lockWaiters.delete(key);
    list?.forEach((waiter) => waiter(result));
  }

  private handleMessage(raw: string) {
    if (raw === 'pong') {
      return;
    }
    try {
      const frame = JSON.parse(raw);
      const type = frame.type;
      const content = frame.content ? JSON.parse(frame.content) : null;
      this.dispatchMessage(type, content);
    } catch {
      // 非协同 JSON 帧忽略
    }
  }

  private openSocket() {
    const ws = new WebSocket(this.url);
    this.ws = ws;
    ws.addEventListener('open', () => {
      this.reconnectAttempts = 0;
      this.callbacks.onStatusChange?.(true);
      // 入房（重连后重新入房，服务端回执含最新 serverVersion，客户端据此增量拉取补齐）
      this.send('fill-work-item-join', { workItemId: this.workItemId });
      this.startTimers();
    });
    ws.addEventListener('message', (event) =>
      this.handleMessage(String(event.data)),
    );
    ws.addEventListener('close', () => {
      this.stopTimers();
      this.callbacks.onStatusChange?.(false);
      this.scheduleReconnect();
    });
    ws.addEventListener('error', () => {
      // close 事件负责重连，这里仅提示
      this.callbacks.onError?.('连接异常');
    });
  }

  private removeLockWaiter(
    key: string,
    waiter: (result: { granted: boolean; holderUserId?: number }) => void,
  ) {
    const list = this.lockWaiters.get(key);
    if (!list) {
      return;
    }
    const next = list.filter((item) => item !== waiter);
    if (next.length === 0) {
      this.lockWaiters.delete(key);
    } else {
      this.lockWaiters.set(key, next);
    }
  }

  private scheduleReconnect() {
    if (this.closedByUser) {
      return;
    }
    const delay = Math.min(
      RECONNECT_BASE_MS * 2 ** this.reconnectAttempts,
      RECONNECT_MAX_MS,
    );
    this.reconnectAttempts += 1;
    setTimeout(() => {
      if (!this.closedByUser) {
        this.openSocket();
      }
    }, delay);
  }

  private send(type: string, payload: object) {
    if (this.ws?.readyState !== WebSocket.OPEN) {
      return;
    }
    this.ws.send(JSON.stringify({ content: JSON.stringify(payload), type }));
  }

  private startTimers() {
    this.stopTimers();
    this.heartbeatTimer = setInterval(() => {
      if (this.ws?.readyState === WebSocket.OPEN) {
        this.ws.send('ping');
      }
    }, HEARTBEAT_INTERVAL_MS);
    this.presenceTimer = setInterval(() => {
      this.send('fill-presence-ping', { workItemId: this.workItemId });
    }, PRESENCE_PING_INTERVAL_MS);
  }

  private stopTimers() {
    if (this.heartbeatTimer) {
      clearInterval(this.heartbeatTimer);
      this.heartbeatTimer = null;
    }
    if (this.presenceTimer) {
      clearInterval(this.presenceTimer);
      this.presenceTimer = null;
    }
  }

  private waitLockResult(
    rowId: string,
    fieldCode: string | undefined,
    timeoutMs: number,
  ) {
    return new Promise<{ granted: boolean; holderUserId?: number }>(
      (resolve) => {
        const key = this.cellLockKey(rowId, fieldCode);
        const list = this.lockWaiters.get(key) ?? [];
        const timer = setTimeout(() => {
          this.removeLockWaiter(key, waiter);
          resolve({ granted: false });
        }, timeoutMs);
        const waiter = (result: {
          granted: boolean;
          holderUserId?: number;
        }) => {
          clearTimeout(timer);
          resolve(result);
        };
        list.push(waiter);
        this.lockWaiters.set(key, list);
      },
    );
  }
}
