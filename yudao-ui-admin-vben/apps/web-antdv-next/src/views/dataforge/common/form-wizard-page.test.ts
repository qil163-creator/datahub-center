import type { App } from 'vue';

import { createApp, h, nextTick, reactive } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import DataforgeFormWizardPage from './form-wizard-page.vue';

const steps = [
  {
    title: '配置基础信息',
    description: '填写名称',
    hint: '选择任务类型',
    fields: ['name'],
  },
  {
    title: '连接配置',
    description: '配置连接',
    hint: '填写服务地址',
    fields: ['host'],
  },
  {
    title: '确认提交',
    description: '核对配置',
    hint: '确认后保存',
    fields: [],
  },
];
let app: App;
let host: HTMLDivElement;

async function mountPage(current = 0) {
  const state = reactive({ busy: false, current });
  const change = vi.fn();
  const close = vi.fn();
  const confirm = vi.fn();
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    render: () =>
      h(
        DataforgeFormWizardPage,
        {
          ...state,
          steps,
          onChange: change,
          onClose: close,
          onConfirm: confirm,
        },
        { default: () => h('input', { value: '任务草稿' }) },
      ),
  });
  app.mount(host);
  await nextTick();
  return { state, change, close, confirm };
}

function button(label: string) {
  return [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (control) => control.textContent?.trim() === label,
  )!;
}

afterEach(() => {
  app?.unmount();
  host?.remove();
});

describe('页面式配置向导', () => {
  it('横向步骤与底部按钮将导航交给领域表单校验，返回列表独立触发', async () => {
    const { change, close } = await mountPage();
    expect(host.querySelector('.dataforge-wizard--page')).not.toBeNull();
    expect(
      host.querySelectorAll('.dataforge-wizard__steps button'),
    ).toHaveLength(3);
    expect(host.querySelector('[aria-current="step"]')?.textContent).toContain(
      '配置基础信息',
    );
    expect(
      host.querySelector('.dataforge-wizard__section-heading')?.textContent,
    ).toBe('基础信息');
    expect(host.textContent).toContain('第 1 / 3 步');

    host
      .querySelectorAll<HTMLButtonElement>(
        '.dataforge-wizard__steps button',
      )[1]!
      .click();
    button('下一步').click();
    expect(change.mock.calls).toEqual([[1], [1]]);
    button('返回列表').click();
    expect(close).toHaveBeenCalledOnce();
    expect(button('上一步')).toBeUndefined();
  });

  it('忙碌时锁定步骤、表单和返回，步骤切换保留内容并重置滚动', async () => {
    const { state, change, close } = await mountPage();
    const input = host.querySelector('input');
    state.busy = true;
    await nextTick();
    expect(host.querySelector('fieldset')?.disabled).toBe(true);
    expect(
      host.querySelector('.dataforge-wizard__main')?.hasAttribute('inert'),
    ).toBe(true);
    button('返回列表').click();
    host
      .querySelectorAll<HTMLButtonElement>(
        '.dataforge-wizard__steps button',
      )[1]!
      .click();
    expect(change).not.toHaveBeenCalled();
    expect(close).not.toHaveBeenCalled();

    const content = host.querySelector<HTMLDivElement>(
      '.dataforge-wizard__content',
    )!;
    content.scrollTop = 200;
    state.busy = false;
    state.current = 1;
    await nextTick();
    await nextTick();
    expect(content.scrollTop).toBe(0);
    expect(host.querySelector('input')).toBe(input);
    button('上一步').click();
    expect(change).toHaveBeenCalledWith(0);
  });

  it('确认页以只读方式核对配置，提交与返回步骤保留独立事件', async () => {
    const { change, confirm } = await mountPage(2);
    expect(host.querySelector('fieldset')?.disabled).toBe(true);
    expect(host.textContent).toContain('请核对以下配置');
    expect(button('下一步')).toBeUndefined();
    button('上一步').click();
    expect(change).toHaveBeenCalledWith(1);
    button('确认提交').click();
    expect(confirm).toHaveBeenCalledOnce();
  });
});
