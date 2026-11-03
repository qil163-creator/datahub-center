import type { App } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { previewDevCron } from '#/api/dataforge/dev';

import CronPreview from './cron-preview.vue';

vi.mock('#/api/dataforge/dev', () => ({ previewDevCron: vi.fn() }));
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: number) => `运行时间 ${value}`,
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => accessStore }));

let app: App;
let host: HTMLDivElement;
let accessStore: { accessToken: number; refreshToken: null | number };
let props: { active: boolean; compact: boolean; expression: string };

function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((done) => {
    resolve = done;
  });
  return { promise, resolve };
}

async function flush() {
  await Promise.resolve();
  await Promise.resolve();
  await nextTick();
}

async function runDebounce() {
  await vi.advanceTimersByTimeAsync(350);
  await flush();
}

beforeEach(() => {
  vi.useFakeTimers();
  vi.clearAllMocks();
  vi.mocked(previewDevCron).mockResolvedValue([1000, 2000, 3000, 4000, 5000]);
  accessStore = reactive({ accessToken: 1, refreshToken: null });
  props = reactive({
    active: true,
    compact: false,
    expression: '0 0 9 * * ?',
  });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({ render: () => h(CronPreview, props) });
  app.mount(host);
});

afterEach(() => {
  app.unmount();
  host.remove();
  vi.useRealTimers();
});

describe('调度表达式预览', () => {
  it('在防抖等待期间显示计算状态，随后展示后端运行时间', async () => {
    expect(host.textContent).toContain('正在计算运行时间');
    expect(host.textContent).not.toContain('没有后续运行时间');
    await runDebounce();
    expect(previewDevCron).toHaveBeenCalledWith('0 0 9 * * ?');
    expect(host.querySelectorAll('time')).toHaveLength(5);
    expect(host.textContent).toContain('下次运行');
    expect(host.textContent).not.toContain('正在计算运行时间');
    expect(
      [...host.querySelectorAll('.cron-expression-grid code')].map(
        (node) => node.textContent,
      ),
    ).toEqual(['0', '0', '9', '*', '*', '?', '—', '0 0 9 * * ?']);
  });

  it('快速改写表达式时只预览最新值', async () => {
    props.expression = '0 0 12 * * ?';
    await flush();
    props.expression = '0 0 15 * * ?';
    await flush();
    await runDebounce();
    expect(previewDevCron).toHaveBeenCalledTimes(1);
    expect(previewDevCron).toHaveBeenCalledWith('0 0 15 * * ?');
  });

  it('迟到响应不覆盖新表达式，旧请求不会提前结束加载', async () => {
    const first = deferred<number[]>();
    const second = deferred<number[]>();
    vi.mocked(previewDevCron)
      .mockReturnValueOnce(first.promise)
      .mockReturnValueOnce(second.promise);
    await runDebounce();
    props.expression = '0 0 12 * * ?';
    await flush();
    await runDebounce();
    first.resolve([111]);
    await flush();
    expect(host.textContent).toContain('正在计算运行时间');
    expect(host.textContent).not.toContain('运行时间 111');
    second.resolve([222]);
    await flush();
    expect(host.textContent).toContain('运行时间 222');
    expect(host.textContent).not.toContain('正在计算运行时间');
  });

  it('请求失败保留重试入口，重新计算后清除错误', async () => {
    vi.mocked(previewDevCron).mockRejectedValueOnce(new Error('failed'));
    await runDebounce();
    expect(host.textContent).toContain('运行时间计算失败');
    host.querySelector<HTMLButtonElement>('.cron-retry')!.click();
    await runDebounce();
    expect(previewDevCron).toHaveBeenCalledTimes(2);
    expect(host.textContent).not.toContain('运行时间计算失败');
    expect(host.querySelectorAll('time')).toHaveLength(5);
  });

  it('空白表达式不发请求；无后续时间时提示检查日期范围', async () => {
    props.expression = '   ';
    await flush();
    await runDebounce();
    expect(previewDevCron).not.toHaveBeenCalled();
    expect(host.textContent).toContain('配置调度周期后');
    vi.mocked(previewDevCron).mockResolvedValueOnce([]);
    props.expression = '0 0 0 1 1 ? 1970';
    await flush();
    await runDebounce();
    expect(host.textContent).toContain('没有后续运行时间');
  });

  it('关闭预览时失效正在运行的请求，重新开启后重新计算', async () => {
    const first = deferred<number[]>();
    vi.mocked(previewDevCron).mockReturnValueOnce(first.promise);
    await runDebounce();
    props.active = false;
    await flush();
    first.resolve([111]);
    await flush();
    expect(host.textContent).not.toContain('运行时间 111');
    expect(host.textContent).not.toContain('正在计算运行时间');
    props.active = true;
    await flush();
    await runDebounce();
    expect(previewDevCron).toHaveBeenCalledTimes(2);
    expect(host.querySelectorAll('time')).toHaveLength(5);
  });

  it('切换登录会话后不接受旧会话时间，按新会话重载', async () => {
    const first = deferred<number[]>();
    vi.mocked(previewDevCron).mockReturnValueOnce(first.promise);
    await runDebounce();
    accessStore.refreshToken = 2;
    await flush();
    first.resolve([111]);
    await flush();
    expect(host.textContent).not.toContain('运行时间 111');
    await runDebounce();
    expect(previewDevCron).toHaveBeenCalledTimes(2);
    expect(host.querySelectorAll('time')).toHaveLength(5);
  });

  it('卸载时取消尚未发送的防抖预览', async () => {
    app.unmount();
    await runDebounce();
    expect(previewDevCron).not.toHaveBeenCalled();
  });

  it('生成器紧凑预览只展示运行时间，不重复表达式区', async () => {
    props.compact = true;
    await flush();
    await runDebounce();
    expect(host.querySelector('.cron-expression-section')).toBeNull();
    expect(host.querySelectorAll('time')).toHaveLength(5);
  });
});
