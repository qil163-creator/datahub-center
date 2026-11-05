import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { FillCollabClient } from './collab-client';

class FakeSocket {
  static instances: FakeSocket[] = [];
  static OPEN = 1;
  static CLOSED = 3;
  readyState = 0;
  sent: string[] = [];
  private listeners: Record<string, ((event: any) => void)[]> = {};
  constructor(public url: string) {
    FakeSocket.instances.push(this);
  }
  addEventListener(type: string, listener: (event: any) => void) {
    (this.listeners[type] ??= []).push(listener);
  }
  send(data: string) {
    this.sent.push(data);
  }
  close() {
    this.readyState = FakeSocket.CLOSED;
    this.emit('close', {});
  }
  open() {
    this.readyState = FakeSocket.OPEN;
    this.emit('open', {});
  }
  message(data: string) {
    this.emit('message', { data });
  }
  emit(type: string, event: any) {
    (this.listeners[type] ?? []).forEach((listener) => listener(event));
  }
}

function frame(type: string, content: object) {
  return JSON.stringify({ content: JSON.stringify(content), type });
}

function create(callbacks = {}) {
  const client = new FillCollabClient({
    callbacks,
    refreshToken: 'token',
    workItemId: '7',
  });
  client.connect();
  return client;
}

beforeEach(() => {
  FakeSocket.instances = [];
  vi.useFakeTimers();
  vi.spyOn(Math, 'random').mockReturnValue(0);
  vi.stubGlobal('WebSocket', FakeSocket);
});
afterEach(() => {
  vi.useRealTimers();
  vi.restoreAllMocks();
  vi.unstubAllGlobals();
});

describe('填报协同客户端', () => {
  it('入房、心跳收到 pong 后不重连；未收到 pong 则主动关闭并重连', () => {
    const status = vi.fn();
    create({ onStatusChange: status });
    const first = FakeSocket.instances[0]!;
    first.open();
    expect(first.sent[0]).toContain('fill-work-item-join');
    vi.advanceTimersByTime(25_000);
    expect(first.sent).toContain('ping');
    first.message('pong');
    vi.advanceTimersByTime(10_000);
    expect(first.readyState).toBe(FakeSocket.OPEN);

    vi.advanceTimersByTime(15_000); // 下一次心跳
    vi.advanceTimersByTime(10_000); // pong 超时
    expect(first.readyState).toBe(FakeSocket.CLOSED);
    expect(status).toHaveBeenLastCalledWith(false);
    vi.advanceTimersByTime(1000);
    expect(FakeSocket.instances).toHaveLength(2);
  });

  it('连接成功过之后首次中断只提示一次，重连失败不再重复提示', () => {
    const onError = vi.fn();
    create({ onError });
    const first = FakeSocket.instances[0]!;
    first.open();
    first.emit('error', {});
    first.close();
    expect(onError).toHaveBeenCalledTimes(1);
    vi.advanceTimersByTime(1000);
    const second = FakeSocket.instances[1]!;
    second.emit('error', {});
    second.close();
    vi.advanceTimersByTime(2000);
    const third = FakeSocket.instances[2]!;
    third.emit('error', {});
    third.close();
    expect(onError).toHaveBeenCalledTimes(1);
  });

  it('退避时间翻倍并设置上限；主动关闭后不再重连，也不会因遗留定时器多开连接', () => {
    const client = create();
    FakeSocket.instances[0]!.close();
    vi.advanceTimersByTime(999);
    expect(FakeSocket.instances).toHaveLength(1);
    vi.advanceTimersByTime(1);
    expect(FakeSocket.instances).toHaveLength(2);
    FakeSocket.instances[1]!.close();
    vi.advanceTimersByTime(1999);
    expect(FakeSocket.instances).toHaveLength(2);
    vi.advanceTimersByTime(1);
    expect(FakeSocket.instances).toHaveLength(3);

    FakeSocket.instances[2]!.close();
    client.close();
    vi.advanceTimersByTime(60_000);
    expect(FakeSocket.instances).toHaveLength(3);
    client.connect();
    expect(FakeSocket.instances).toHaveLength(4);
    vi.advanceTimersByTime(60_000);
    expect(FakeSocket.instances).toHaveLength(4);
  });

  it('锁申请按单元格等待结果；超时、未连接或关闭时视为未授予', async () => {
    const client = create();
    const socket = FakeSocket.instances[0]!;
    expect(await client.acquireLockAndWait('r1', 'f1')).toEqual({ granted: false, holderUserId: undefined });
    socket.open();
    const granted = client.acquireLockAndWait('r1', 'f1');
    socket.message(frame('fill-lock-result', { fieldCode: 'f1', granted: true, rowId: 'r1' }));
    expect(await granted).toMatchObject({ granted: true });

    const denied = client.acquireLockAndWait('r2', 'f1');
    socket.message(frame('fill-lock-result', { fieldCode: 'f1', granted: false, holderUserId: 9, rowId: 'r2' }));
    expect(await denied).toMatchObject({ granted: false, holderUserId: 9 });

    const timeout = client.acquireLockAndWait('r3', 'f1', 500);
    vi.advanceTimersByTime(500);
    expect(await timeout).toEqual({ granted: false });

    const pending = client.acquireLockAndWait('r4', 'f1');
    client.close();
    expect(await pending).toEqual({ granted: false });
  });

  it('只把属于当前填报实例的操作广播交给回调，非协同帧被忽略', () => {
    const onOperationApplied = vi.fn();
    create({ onOperationApplied });
    const socket = FakeSocket.instances[0]!;
    socket.open();
    socket.message(frame('fill-operation-applied', { operatorUserId: 1, serverVersion: 3, workItemId: 8 }));
    socket.message(frame('fill-operation-applied', { operatorUserId: 1, serverVersion: 4, workItemId: 7 }));
    socket.message('not json');
    socket.message(frame('notice-push', { text: 'x' }));
    expect(onOperationApplied).toHaveBeenCalledTimes(1);
    expect(onOperationApplied).toHaveBeenCalledWith(expect.objectContaining({ serverVersion: 4 }));
  });
});
