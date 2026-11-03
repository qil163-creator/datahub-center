/* eslint-disable vue/one-component-per-file -- 保留真实 Antdv 控件，仅隔离外层布局和权限来源。 */
import type { App } from 'vue';

import { readFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { ConfigProvider, Radio } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CategoryPage from './page.vue';

const categoryStyles = readFileSync(
  join(dirname(fileURLToPath(import.meta.url)), 'style.css'),
  'utf8',
);

const harness = vi.hoisted(() => ({
  list: vi.fn(),
}));

vi.mock('@vben/stores', () => ({
  useAccessStore: () => ({
    accessCodes: [],
    accessToken: 'test',
    refreshToken: '',
  }),
  useUserStore: () => ({ userInfo: { userId: '7' }, userRoles: ['editor'] }),
}));
vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/utils', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@vben/utils')>()),
  formatDate: String,
}));
vi.mock('@vben/common-ui', async (importOriginal) => ({
  ...(await importOriginal<typeof import('@vben/common-ui')>()),
  Page: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', slots.default?.()),
  }),
}));
vi.mock('#/api/dataforge/projectcategory', () => ({
  getProjectCategoryList: harness.list,
  getProjectCategory: vi.fn(),
  createProjectCategory: vi.fn(),
  updateProjectCategory: vi.fn(),
  deleteProjectCategory: vi.fn(),
  deleteProjectCategoryList: vi.fn(),
  updateProjectCategoryStatus: vi.fn(),
}));

let app: App;
let host: HTMLDivElement;
let styles: HTMLStyleElement;

beforeEach(async () => {
  vi.clearAllMocks();
  harness.list.mockResolvedValue([
    {
      id: '9007199254740993',
      parentId: '0',
      name: '父类目',
      status: 1,
      sort: 0,
      description: '',
      taskCount: 0,
      childCount: 1,
    },
    {
      id: '9007199254740995',
      parentId: '9007199254740993',
      name: '子类目',
      status: 1,
      sort: 0,
      description: '',
      taskCount: 0,
      childCount: 0,
    },
  ]);
  styles = document.createElement('style');
  styles.textContent = `:root { --radius: 8px; }\n${categoryStyles}`;
  document.head.append(styles);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(
    defineComponent({
      setup: () => () =>
        h(
          ConfigProvider,
          { theme: { token: { colorPrimary: '#157a6e' } } },
          {
            default: () => [
              h(Radio, { checked: true, 'data-test-theme-probe': true }),
              h(CategoryPage, { kind: 'integration' }),
            ],
          },
        ),
    }),
  );
  app.mount(host);
  await vi.waitUntil(() => host.querySelector('.qpc-tree-closed'));
});

afterEach(async () => {
  app.unmount();
  await nextTick();
  host.remove();
  styles.remove();
});

describe('真实 Antdv Next 控件契约', () => {
  it('父复选联动未展开的子项，主操作与删除保留原生语义', async () => {
    const parentCheckbox = host.querySelector<HTMLInputElement>(
      '.vxe-table--body-wrapper .vxe-cell--checkbox',
    )!;
    parentCheckbox.click();
    await nextTick();
    host.querySelector<HTMLButtonElement>('.qpc-tree-closed')!.click();
    await vi.waitFor(() =>
      expect(
        host.querySelectorAll('.vxe-table--body-wrapper .vxe-cell--checkbox'),
      ).toHaveLength(2),
    );
    const checked = [
      ...host.querySelectorAll<HTMLInputElement>(
        '.vxe-table--body-wrapper .vxe-cell--checkbox',
      ),
    ];
    expect(
      checked.every((input) => input.classList.contains('is--checked')),
    ).toBe(true);
    expect(
      host.querySelector('.qpc-toolbar-actions .ant-btn-primary'),
    ).not.toBeNull();
    expect(
      host.querySelector('.qpc-toolbar-actions .ant-btn-dangerous'),
    ).not.toBeNull();
  });

  it('原生新增弹窗保留字段校验约束、父级选择与继承主题', async () => {
    host
      .querySelector<HTMLButtonElement>('.qpc-toolbar-actions button')!
      .click();
    await vi.waitFor(() =>
      expect(
        document.querySelector('.qpc-modal .ant-modal-container'),
      ).not.toBeNull(),
    );
    const dialog = document.querySelector('.qpc-modal[role="dialog"]')!;
    expect(dialog.textContent).toContain('添加数据集成类目');
    expect(dialog.querySelector('input[maxlength="50"]')).not.toBeNull();
    expect(dialog.querySelector('textarea[maxlength="256"]')).not.toBeNull();
    const inheritedColor = getComputedStyle(
      host.querySelector('[data-test-theme-probe] .ant-radio-checked')!,
    ).backgroundColor;
    expect(inheritedColor).not.toBe('');
    expect(
      getComputedStyle(dialog.querySelector('.ant-radio-checked')!)
        .backgroundColor,
    ).toBe(inheritedColor);
    const parent = dialog.querySelector<HTMLElement>('.qpc-form .ant-select')!;
    parent.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    await vi.waitFor(() =>
      expect(document.querySelector('.qpc-parent-popup')).not.toBeNull(),
    );
    expect(document.querySelector('.qpc-parent-popup')?.textContent).toContain(
      '顶级节点',
    );
  });
});
