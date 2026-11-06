import type { Pinia } from 'pinia';

import type { App } from 'vue';

import { createApp, h, nextTick } from 'vue';

import { useUserStore } from '@vben/stores';

import { createPinia } from 'pinia';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import PageGuide from './index.vue';

const accountId = '9223372036854775807';
const apps = new Map<App, HTMLElement>();
let pinia: Pinia;

function signIn(userId: string) {
  useUserStore(pinia).setUserInfo({
    avatar: '',
    nickname: '测试用户',
    userId,
    username: 'tester',
  });
}

function mountGuide(guideId = 'asset-guide') {
  const host = document.createElement('div');
  const app = createApp({
    render: () =>
      h(
        PageGuide,
        { class: 'usage-guide', guideId, type: 'warning' },
        {
          description: () => '详细步骤',
          message: () => '操作说明',
        },
      ),
  });
  app.use(pinia).mount(host);
  apps.set(app, host);
  return host;
}

async function clickButton(host: HTMLElement, label: string) {
  const button = [...host.querySelectorAll('button')].find(
    (item) =>
      item.getAttribute('aria-label') === label ||
      item.textContent?.trim() === label,
  );
  expect(button).toBeDefined();
  button?.click();
  await nextTick();
}

beforeEach(() => {
  pinia = createPinia();
  localStorage.clear();
  signIn(accountId);
});

afterEach(() => {
  for (const app of apps.keys()) app.unmount();
  apps.clear();
  vi.restoreAllMocks();
});

describe('页面使用说明', () => {
  it('保留提示内容及样式，关闭后重新挂载会再次显示', async () => {
    const guide = mountGuide();
    expect(guide.querySelector('.usage-guide.ant-alert-warning')).toBeTruthy();
    expect(guide.textContent).toContain('操作说明');
    expect(guide.textContent).toContain('详细步骤');
    await clickButton(guide, '关闭提示');
    expect(guide.textContent).not.toContain('操作说明');
    expect(localStorage.length).toBe(0);
    expect(mountGuide().textContent).toContain('操作说明');
  });

  it('不再提示仅影响当前账号的指定提示', async () => {
    const guide = mountGuide();
    await clickButton(guide, '不再提示');
    expect(guide.textContent).not.toContain('操作说明');
    expect(mountGuide().textContent).not.toContain('操作说明');
    expect(mountGuide('other-guide').textContent).toContain('操作说明');

    signIn('9223372036854775806');
    await nextTick();
    expect(guide.textContent).toContain('操作说明');

    signIn(accountId);
    await nextTick();
    expect(guide.textContent).not.toContain('操作说明');
  });

  it('浏览器禁用存储时仍可关闭当前提示', async () => {
    vi.spyOn(window, 'localStorage', 'get').mockImplementation(() => {
      throw new DOMException('存储不可访问', 'SecurityError');
    });
    const guide = mountGuide();
    await clickButton(guide, '不再提示');
    expect(guide.textContent).not.toContain('操作说明');
    expect(mountGuide().textContent).toContain('操作说明');
  });
});
