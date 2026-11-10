import type { App } from 'vue';

import { createApp, h, nextTick } from 'vue';

import { afterEach, expect, it, vi } from 'vitest';

import JobActionDropdown from './job-action-dropdown.vue';

let app: App | undefined;
let host: HTMLDivElement;

afterEach(() => {
  app?.unmount();
  host.remove();
});

async function flush() {
  for (let i = 0; i < 12; i++) await Promise.resolve();
  await nextTick();
}

it('同一行的两个表格副本各自控制菜单，点击菜单项只触发一次', async () => {
  const selected = vi.fn();
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    setup: () => () => h('div', [0, 1].map((index) => h(JobActionDropdown, null, {
      default: () => h('button', { 'data-trigger': index }, '更多'),
      actions: () => h('button', { 'data-action': index, onClick: selected }, '调度周期'),
    }))),
  });
  app.mount(host);
  await flush();
  host.querySelector<HTMLButtonElement>('[data-trigger="1"]')!.click();
  await flush();
  expect(document.querySelector('[data-action="1"]')).not.toBeNull();
  expect(document.querySelector('[data-action="0"]')).toBeNull();
  document.querySelector<HTMLButtonElement>('[data-action="1"]')!.click();
  await flush();
  expect(selected).toHaveBeenCalledTimes(1);
});
