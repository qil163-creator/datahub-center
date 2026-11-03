import type { App } from 'vue';

import type { ActionItem } from './typing';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import TableAction from './table-action.vue';

vi.mock('@vben/access', () => ({
  useAccess: () => ({ hasAccessByCodes: () => true }),
}));
vi.mock('@vben/icons', () => ({
  IconifyIcon: () => h('span', { class: 'iconify' }),
}));
vi.mock('@vben/locales', () => ({
  $t: () => '更多',
}));
vi.mock('@vben/utils', () => ({
  isBoolean: (value: unknown) => typeof value === 'boolean',
  isFunction: (value: unknown) => typeof value === 'function',
}));

let app: App | undefined;
let host: HTMLDivElement;

async function flush(milliseconds = 0) {
  await nextTick();
  await vi.advanceTimersByTimeAsync(milliseconds);
  await nextTick();
}

function visiblePopup(selector: string) {
  return [...document.querySelectorAll<HTMLElement>(selector)].find(
    (element) =>
      element.style.display !== 'none' &&
      element.style.pointerEvents !== 'none',
  );
}

function findButton(label: string, root: ParentNode = document) {
  const button = [...root.querySelectorAll<HTMLButtonElement>('button')].find(
    (element) => element.textContent?.trim() === label,
  );
  expect(button, `button ${label}`).toBeDefined();
  return button!;
}

async function mount(
  dropDownActions: ActionItem[],
  actions: ActionItem[] = [],
) {
  const props = reactive({ actions, dropDownActions });
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({ render: () => h(TableAction, props) });
  app.mount(host);
  await flush();
  return props;
}

async function openMenu() {
  await nextTick();
  findButton('更多', host).dispatchEvent(new MouseEvent('mouseenter'));
  await flush(250);
  const menu = visiblePopup('.ant-dropdown');
  expect(menu).toBeDefined();
  return menu!;
}

async function openConfirmation() {
  const menu = await openMenu();
  const trigger = [
    ...menu.querySelectorAll<HTMLElement>('.table-action-more-item'),
  ].find((item) => item.textContent?.trim() === '删除');
  expect(trigger).toBeDefined();
  trigger!.click();
  await flush(400);
  expect(visiblePopup('.ant-dropdown')).toBeDefined();
  expect(visiblePopup('.ant-popconfirm')).toBeDefined();
  return menu;
}

beforeEach(() => {
  vi.useFakeTimers();
});

afterEach(async () => {
  app?.unmount();
  app = undefined;
  await nextTick();
  document.body.innerHTML = '';
  vi.clearAllTimers();
  vi.useRealTimers();
});

describe('tableAction 下拉确认的原生浮层交互', () => {
  it('菜单点击和鼠标离开时保留确认锚点，取消后释放菜单', async () => {
    const confirm = vi.fn();
    const cancel = vi.fn();
    await mount([
      { label: '删除', popConfirm: { title: '确认删除？', confirm, cancel } },
    ]);
    const menu = await openConfirmation();

    menu.dispatchEvent(
      new MouseEvent('mouseleave', { relatedTarget: document.body }),
    );
    await flush(500);
    expect(visiblePopup('.ant-dropdown')).toBeDefined();
    expect(visiblePopup('.ant-popconfirm')).toBeDefined();

    findButton('Cancel', visiblePopup('.ant-popconfirm')).click();
    await flush(400);
    expect(cancel).toHaveBeenCalledTimes(1);
    expect(confirm).not.toHaveBeenCalled();
    expect(visiblePopup('.ant-popconfirm')).toBeUndefined();
    expect(visiblePopup('.ant-dropdown')).toBeUndefined();

    await openMenu();
    findButton('更多', host).dispatchEvent(new MouseEvent('mouseleave'));
    menu.dispatchEvent(
      new MouseEvent('mouseleave', { relatedTarget: document.body }),
    );
    await flush(400);
    expect(visiblePopup('.ant-dropdown')).toBeUndefined();
  });

  it('点击删除菜单项的空白区域也打开确认框', async () => {
    const confirm = vi.fn();
    await mount([
      { label: '删除', popConfirm: { title: '确认删除？', confirm } },
    ]);
    const menu = await openMenu();
    menu.querySelector<HTMLElement>('.ant-dropdown-menu-item')!.click();
    await flush(400);

    expect(visiblePopup('.ant-popconfirm')).toBeDefined();
    expect(confirm).not.toHaveBeenCalled();
    findButton('OK', visiblePopup('.ant-popconfirm')).click();
    await flush(400);
    expect(confirm).toHaveBeenCalledTimes(1);
  });

  it('普通菜单项仍执行一次并自动关闭', async () => {
    const onClick = vi.fn();
    await mount([{ label: '编辑', onClick }]);
    const menu = await openMenu();
    menu.querySelector<HTMLElement>('.table-action-more-item')!.click();
    await flush(400);

    expect(onClick).toHaveBeenCalledTimes(1);
    expect(visiblePopup('.ant-dropdown')).toBeUndefined();
    expect(visiblePopup('.ant-popconfirm')).toBeUndefined();
  });

  it('确认异步完成前保留菜单，重复点击不重复执行业务回调', async () => {
    let complete: (() => void) | undefined;
    const confirm = vi.fn(
      () =>
        new Promise<void>((resolve) => {
          complete = resolve;
        }),
    );
    await mount([
      { label: '删除', popConfirm: { title: '确认删除？', confirm } },
    ]);
    await openConfirmation();

    const ok = findButton('OK', visiblePopup('.ant-popconfirm'));
    ok.click();
    ok.click();
    await flush(400);
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(visiblePopup('.ant-dropdown')).toBeDefined();
    expect(visiblePopup('.ant-popconfirm')).toBeDefined();

    complete!();
    await flush(400);
    expect(visiblePopup('.ant-popconfirm')).toBeUndefined();
    expect(visiblePopup('.ant-dropdown')).toBeUndefined();
  });

  it('点击外部关闭确认并释放菜单锁，重开后正常响应鼠标移出', async () => {
    const confirm = vi.fn();
    const cancel = vi.fn();
    await mount([
      { label: '删除', popConfirm: { title: '确认删除？', confirm, cancel } },
    ]);
    await openConfirmation();

    document.body.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    await flush(400);
    expect(confirm).not.toHaveBeenCalled();
    expect(cancel).not.toHaveBeenCalled();
    expect(visiblePopup('.ant-popconfirm')).toBeUndefined();
    expect(visiblePopup('.ant-dropdown')).toBeUndefined();

    const menu = await openMenu();
    menu.dispatchEvent(
      new MouseEvent('mouseleave', { relatedTarget: document.body }),
    );
    await flush(400);
    expect(visiblePopup('.ant-dropdown')).toBeUndefined();
  });

  it.each(['移除', '禁用动作', '禁用确认', '隐藏'] as const)(
    '%s当前确认项后清理浮层与锁',
    async (change) => {
      const confirm = vi.fn();
      const props = await mount([
        { label: '删除', popConfirm: { title: '确认删除？', confirm } },
      ]);
      await openConfirmation();

      if (change === '移除') props.dropDownActions = [];
      else if (change === '禁用动作') props.dropDownActions[0]!.disabled = true;
      else if (change === '禁用确认')
        props.dropDownActions[0]!.popConfirm!.disabled = true;
      else props.dropDownActions[0]!.ifShow = false;
      await flush(400);
      expect(visiblePopup('.ant-popconfirm')).toBeUndefined();
      expect(visiblePopup('.ant-dropdown')).toBeUndefined();
      expect(confirm).not.toHaveBeenCalled();

      props.dropDownActions = [{ label: '编辑', onClick: vi.fn() }];
      const menu = await openMenu();
      menu.querySelector<HTMLElement>('.table-action-more-item')!.click();
      await flush(400);
      expect(visiblePopup('.ant-dropdown')).toBeUndefined();
    },
  );

  it('直接展示按钮的确认与取消行为保持独立', async () => {
    const confirm = vi.fn();
    await mount(
      [],
      [{ label: '删除', popConfirm: { title: '确认删除？', confirm } }],
    );
    findButton('删除', host).click();
    await flush(400);
    expect(visiblePopup('.ant-dropdown')).toBeUndefined();
    expect(visiblePopup('.ant-popconfirm')).toBeDefined();

    findButton('OK', visiblePopup('.ant-popconfirm')).click();
    await flush(400);
    expect(confirm).toHaveBeenCalledTimes(1);
    expect(visiblePopup('.ant-popconfirm')).toBeUndefined();
  });
});
