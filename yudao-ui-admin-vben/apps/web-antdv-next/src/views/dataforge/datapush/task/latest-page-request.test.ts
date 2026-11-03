import { describe, expect, it } from 'vitest';

import { awaitLatestPageRequest } from './latest-page-request';

function deferred<T>() {
  let resolve!: (value: T) => void;
  let reject!: (reason: unknown) => void;
  const promise = new Promise<T>((resolvePromise, rejectPromise) => {
    resolve = resolvePromise;
    reject = rejectPromise;
  });
  return { promise, reject, resolve };
}

describe('推送列表并发查询', () => {
  it('旧请求等待期间再次查询时，只显示最新结果', async () => {
    const a = deferred<string>();
    const b = deferred<string>();
    const c = deferred<string>();
    let latest = a.promise;
    const wait = (request: Promise<string>) =>
      awaitLatestPageRequest(request, () => latest, () => true);
    const resultA = wait(a.promise);
    latest = b.promise;
    const resultB = wait(b.promise);
    a.resolve('A');
    await Promise.resolve();
    latest = c.promise;
    const resultC = wait(c.promise);
    c.resolve('C');
    b.resolve('B');
    await expect(Promise.all([resultA, resultB, resultC])).resolves.toEqual([
      { current: true, result: 'C' },
      { current: true, result: 'C' },
      { current: true, result: 'C' },
    ]);
  });

  it('旧请求失败时继续等待新结果', async () => {
    const old = deferred<string>();
    const current = deferred<string>();
    let latest = old.promise;
    const result = awaitLatestPageRequest(old.promise, () => latest, () => true);
    latest = current.promise;
    old.reject(new Error('旧请求失败'));
    current.resolve('最新结果');
    await expect(result).resolves.toEqual({ current: true, result: '最新结果' });
  });

  it('最新请求失败时向调用者抛出错误', async () => {
    const old = deferred<string>();
    const current = deferred<string>();
    let latest = old.promise;
    const result = awaitLatestPageRequest(old.promise, () => latest, () => true);
    latest = current.promise;
    old.resolve('旧结果');
    await Promise.resolve();
    current.reject(new Error('最新请求失败'));
    await expect(result).rejects.toThrow('最新请求失败');
  });

  it('身份改变后丢弃旧请求及其错误', async () => {
    const request = deferred<string>();
    let active = true;
    const result = awaitLatestPageRequest(request.promise, () => request.promise, () => active);
    active = false;
    request.reject(new Error('旧身份请求失败'));
    await expect(result).resolves.toEqual({ current: false });
  });
});
