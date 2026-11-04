import { nextTick } from 'vue';

import { clearAllAlerts, confirm } from '@vben/common-ui';

import { afterEach, expect, it, vi } from 'vitest';

afterEach(() => clearAllAlerts());
it.each([
  ['继续编辑', false],
  ['丢弃修改', true],
] as const)('离开确认真实显示两个按钮，点击%s返回对应选择', async (label, expected) => {
  const result = confirm({
    title: '流程有未保存的修改',
    content: '确认丢弃未保存的节点与配置？',
    confirmText: '丢弃修改',
    cancelText: '继续编辑',
    icon: 'warning',
  }).then(() => true, () => false);
  await nextTick();
  const buttons = () => [...document.querySelectorAll<HTMLButtonElement>('button')];
  await vi.waitUntil(() => buttons().some((button) => button.textContent?.trim() === label));
  expect(buttons().some((button) => button.textContent?.trim() === '继续编辑')).toBe(true);
  expect(buttons().some((button) => button.textContent?.trim() === '丢弃修改')).toBe(true);
  buttons().find((button) => button.textContent?.trim() === label)!.click();
  expect(await result).toBe(expected);
});
