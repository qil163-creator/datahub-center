/* eslint-disable vue/one-component-per-file -- 测试需要独立的弹窗替身与父组件。 */
import type { App, VNode } from 'vue';

import type { WeekMode } from './cron-semantics';
import type { ShortcutsType } from './types';

import { createApp, defineComponent, h, nextTick, ref } from 'vue';

import { Form } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import CronTab from './cron-tab.vue';

vi.mock('@vben/utils', () => ({
  formatDateTime: (date: Date) => date.toISOString(),
  getCurrentTimezone: () => 'Asia/Shanghai',
}));
vi.mock('antdv-next', async (importOriginal) => {
  const controls = await importOriginal<typeof import('antdv-next')>();
  return {
    ...controls,
    // 只移除弹窗动画，保留真实选择器、单选框、数字输入与标签页的交互契约。
    Modal: defineComponent({
      inheritAttrs: false,
      props: {
        open: Boolean,
        title: { type: String, default: '' },
        rootClass: { type: String, default: undefined },
        width: { type: [Number, String], default: undefined },
      },
      setup(props, { slots }) {
        return () =>
          props.open
            ? h(
                'section',
                {
                  role: 'dialog',
                  'aria-label': props.title,
                  class: props.rootClass,
                  'data-modal-width': props.width,
                },
                [slots.default?.(), slots.footer?.()],
              )
            : null;
      },
    }),
  };
});

let app: App | undefined;
let host: HTMLDivElement;
const model = ref('0 * * * * ?');
const updated = vi.fn((value: string) => {
  model.value = value;
});

async function flush() {
  for (let index = 0; index < 12; index++) await Promise.resolve();
  await nextTick();
}

async function mount(
  value = '0 * * * * ?',
  options: {
    disabled?: boolean;
    formDisabled?: boolean;
    modalClass?: string;
    modalWidth?: number | string;
    preview?: (value: { compact: boolean; expression: string }) => VNode;
    shortcuts?: ShortcutsType[];
    trigger?: (value: { disabled: boolean; open: () => void }) => VNode;
    weekMode?: WeekMode;
  } = {},
) {
  const { formDisabled, preview, trigger, ...props } = options;
  model.value = value;
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    setup: () => () => {
      const component = h(
        CronTab,
        {
          ...props,
          modelValue: model.value,
          'onUpdate:modelValue': updated,
        },
        { ...(preview ? { preview } : {}), ...(trigger ? { trigger } : {}) },
      );
      return formDisabled
        ? h(Form, { disabled: formDisabled }, { default: () => component })
        : component;
    },
  });
  app.mount(host);
  await flush();
}

function dialog() {
  return host.querySelector<HTMLElement>('[role="dialog"]')!;
}

function input(label: string) {
  return dialog().querySelector<HTMLInputElement>(
    `input[aria-label="${label}"]`,
  )!;
}

function expression() {
  return input('Cron 表达式').value;
}

function button(label: string) {
  const control = [
    ...dialog().querySelectorAll<HTMLButtonElement>('button'),
  ].find((item) => item.textContent?.replaceAll(/\s/g, '') === label);
  expect(control).toBeDefined();
  return control!;
}

async function choose(select: HTMLElement, label: string, search?: string) {
  select.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  await flush();
  if (search) {
    const control = select.querySelector<HTMLInputElement>('input')!;
    control.value = search;
    control.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
  }
  const option = [
    ...document.querySelectorAll<HTMLElement>(
      '.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option',
    ),
  ].find((item) => item.textContent?.trim() === label);
  expect(option).toBeDefined();
  option!.click();
  await flush();
}

async function shortcut(label: string) {
  await choose(
    host.querySelector<HTMLElement>('.input-with-select .ant-select')!,
    label,
  );
}

async function tab(label: string) {
  const control = [
    ...dialog().querySelectorAll<HTMLElement>('[role="tab"]'),
  ].find((item) => item.textContent?.trim() === label);
  expect(control).toBeDefined();
  control!.click();
  await flush();
}

async function rule(label: string) {
  const wrapper = [
    ...dialog().querySelectorAll<HTMLElement>(
      '[role="tabpanel"][aria-hidden="false"] .ant-radio-wrapper',
    ),
  ].find((item) => item.textContent?.trim().startsWith(label));
  expect(wrapper).toBeDefined();
  wrapper!.querySelector<HTMLInputElement>('input')!.click();
  await flush();
}

beforeEach(() => {
  updated.mockClear();
  vi.useFakeTimers({ toFake: ['Date'] });
  vi.setSystemTime(new Date(2026, 9, 11, 22, 43, 17));
});

afterEach(async () => {
  app?.unmount();
  app = undefined;
  await flush();
  host?.remove();
  vi.useRealTimers();
});

describe('cron 生成器交互（真实 Antdv Next 控件）', () => {
  it('业务页面可独立指定弹窗容器类和宽度', async () => {
    await mount('0 15 8 * * ?', {
      modalClass: 'full-sync-preview',
      modalWidth: 912,
    });
    await shortcut('自定义');
    expect(dialog().classList.contains('full-sync-preview')).toBe(true);
    expect(dialog().dataset.modalWidth).toBe('912');
    expect(expression()).toBe('0 15 8 * * ?');
  });
  it('业务页面可用配置按钮打开同一生成器并回写完整表达式', async () => {
    await mount('0 15 8 * * ?', {
      trigger: ({ open, disabled }) => h('button', { type: 'button', disabled, onClick: open }, '配置'),
    });
    const trigger = [...host.querySelectorAll('button')].find((item) => item.textContent === '配置')!;
    expect(trigger).toBeDefined();
    expect(host.querySelector('.ant-select')).toBeNull();
    trigger.click();
    await flush();
    expect(expression()).toBe('0 15 8 * * ?');
    button('确定').click();
    await flush();
    expect(updated).toHaveBeenCalledExactlyOnceWith('0 15 8 * * ?');
  });
  it('每次选择自定义都能打开，关闭后放弃草稿并从当前值重新加载', async () => {
    await mount('0 15 8 * * ?');
    for (let index = 0; index < 3; index++) {
      await shortcut('自定义');
      expect(dialog()).not.toBeNull();
      expect(expression()).toBe('0 15 8 * * ?');
      expect(
        dialog().querySelector('[role="tab"][aria-selected="true"]')
          ?.textContent,
      ).toBe('秒');
      await rule('周期从');
      expect(expression()).toBe('1-2 15 8 * * ?');
      button('关闭').click();
      await flush();
      expect(dialog()).toBeNull();
      expect(model.value).toBe('0 15 8 * * ?');
    }
    expect(updated).not.toHaveBeenCalled();
  });

  it('编辑和预览使用草稿，确定后只回传一次完整表达式', async () => {
    await mount();
    await shortcut('自定义');
    await rule('周期从');
    dialog()
      .querySelector<HTMLButtonElement>('[aria-label="增大秒范围开始"]')!
      .click();
    await flush();
    expect(expression()).toBe('2-2 * * * * ?');
    expect(dialog().querySelectorAll('.cron-next-times li')).toHaveLength(5);
    expect(updated).not.toHaveBeenCalled();
    expect(model.value).toBe('0 * * * * ?');
    button('确定').click();
    await flush();
    expect(updated).toHaveBeenCalledExactlyOnceWith('2-2 * * * * ?');
    expect(dialog()).toBeNull();
  });

  it('重置为每分钟执行并清除年份，确定前不修改表单值', async () => {
    await mount('0 15 8 10 11 ? 2028');
    await shortcut('自定义');
    await tab('年');
    button('重置').click();
    await flush();
    expect(expression()).toBe('0 * * * * ?');
    expect(input('年字段').value).toBe('');
    expect(
      dialog().querySelector('[role="tab"][aria-selected="true"]')?.textContent,
    ).toBe('秒');
    expect(updated).not.toHaveBeenCalled();
    button('确定').click();
    await flush();
    expect(updated).toHaveBeenCalledExactlyOnceWith('0 * * * * ?');
  });

  it('切换日或周规则时把另一字段设为不指定', async () => {
    await mount();
    await shortcut('自定义');
    await tab('周');
    await rule('周，');
    expect(input('日字段').value).toBe('?');
    expect(input('周字段').value).toBe('*');
    await tab('日');
    await rule('本月最后一天');
    expect(input('日字段').value).toBe('L');
    expect(input('周字段').value).toBe('?');
    expect(expression()).toBe('0 * * L * ?');
  });

  it.each(['invalid * * * * ?', '0 */0 * * * ?'])(
    '无效表达式 %s 显示错误并阻止确认',
    async (value) => {
      await mount(value);
      await shortcut('自定义');
      expect(
        dialog().querySelector('[role="alert"]')?.textContent,
      ).toBeTruthy();
      expect(dialog().querySelectorAll('.cron-next-times li')).toHaveLength(0);
      expect(button('确定').disabled).toBe(true);
      button('确定').click();
      await flush();
      expect(updated).not.toHaveBeenCalled();
      expect(dialog()).not.toBeNull();
      button('重置').click();
      await flush();
      expect(dialog().querySelector('[role="alert"]')).toBeNull();
      expect(button('确定').disabled).toBe(false);
    },
  );

  it('展示全部七个字段并保留已有组合范围步长，确认时不丢失语义', async () => {
    const value = '0-30/10 0/15 8-17 ? 11,12 2-6/2 2026-2030';
    await mount(value);
    await shortcut('自定义');
    const labels = ['秒', '分钟', '小时', '日', '月', '周', '年'];
    expect(labels.map((label) => input(`${label}字段`).value)).toEqual(
      value.split(' '),
    );
    expect(labels.every((label) => input(`${label}字段`).readOnly)).toBe(true);
    expect(expression()).toBe(value);
    expect(input('秒表达式').value).toBe('0-30/10');
    await tab('周');
    expect(input('周表达式').value).toBe('2-6/2');
    expect(button('确定').disabled).toBe(false);
    button('确定').click();
    await flush();
    expect(updated).toHaveBeenCalledExactlyOnceWith(value);
  });

  it('指定年份使用字符串选项，已有年份正确选中且新增年份可回传', async () => {
    await mount('0 0 0 * * ? 2028');
    await shortcut('自定义');
    await tab('年');
    const years = dialog().querySelector<HTMLElement>(
      '[role="tabpanel"][aria-hidden="false"] .cron-appoint',
    )!;
    expect(years.textContent).toContain('2028');
    await choose(years, '2029', '2029');
    expect(input('年字段').value).toBe('2028,2029');
    expect(expression()).toBe('0 0 0 * * ? 2028,2029');
    expect(updated).not.toHaveBeenCalled();
    button('确定').click();
    await flush();
    expect(updated).toHaveBeenCalledExactlyOnceWith('0 0 0 * * ? 2028,2029');
  });

  it('快捷项立即回传，自定义快捷项和外部 v-model 更新都保留', async () => {
    await mount(undefined, {
      shortcuts: [{ text: '工作日早九点', value: '0 0 9 ? * 2-6' }],
    });
    await shortcut('每小时');
    expect(updated).toHaveBeenLastCalledWith('0 0 * * * ?');
    expect(dialog()).toBeNull();
    await shortcut('工作日早九点');
    expect(updated).toHaveBeenLastCalledWith('0 0 9 ? * 2-6');
    model.value = '0 30 8 * * ?';
    await flush();
    expect(host.querySelector<HTMLInputElement>('input.ant-input')!.value).toBe(
      '0 30 8 * * ?',
    );
    await shortcut('自定义');
    expect(expression()).toBe('0 30 8 * * ?');
    expect(updated).toHaveBeenCalledTimes(2);
  });

  it('直接输入通过 v-model 回传新值', async () => {
    await mount();
    const control = host.querySelector<HTMLInputElement>('input.ant-input')!;
    control.value = '0 0 12 * * ?';
    control.dispatchEvent(new Event('input', { bubbles: true }));
    await flush();
    expect(updated).toHaveBeenCalledExactlyOnceWith('0 0 12 * * ?');
  });

  it('禁用状态同时禁用输入和生成器', async () => {
    await mount('0 0 12 * * ?', { disabled: true });
    expect(
      host.querySelector<HTMLInputElement>('input.ant-input')!.disabled,
    ).toBe(true);
    const generator = host.querySelector<HTMLElement>(
      '.input-with-select .ant-select',
    )!;
    expect(generator.classList.contains('ant-select-disabled')).toBe(true);
    generator.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    await flush();
    expect(dialog()).toBeNull();
    expect(
      document.querySelector(
        '.ant-select-dropdown:not(.ant-select-dropdown-hidden)',
      ),
    ).toBeNull();
    expect(updated).not.toHaveBeenCalled();
  });

  it('未显式传 disabled 时，输入和生成器仍继承只读 Form 的禁用状态', async () => {
    await mount('0 0 12 * * ?', { formDisabled: true });
    expect(
      host.querySelector<HTMLInputElement>('input.ant-input')!.disabled,
    ).toBe(true);
    const generator = host.querySelector<HTMLElement>(
      '.input-with-select .ant-select',
    )!;
    expect(generator.classList.contains('ant-select-disabled')).toBe(true);
    generator.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
    await flush();
    expect(dialog()).toBeNull();
    expect(
      document.querySelector(
        '.ant-select-dropdown:not(.ant-select-dropdown-hidden)',
      ),
    ).toBeNull();
    expect(updated).not.toHaveBeenCalled();
  });

  it('有年份时空指定字段仍视为错误，不能因字段左移而误判为合法表达式', async () => {
    await mount('0 0 0 * * ? 2028');
    await shortcut('自定义');
    const seconds = dialog().querySelector<HTMLElement>(
      '[role="tabpanel"][aria-hidden="false"] .cron-appoint',
    )!;
    seconds
      .querySelector<HTMLElement>('.ant-select-selection-item-remove')!
      .click();
    await flush();
    expect(input('秒字段').value).toBe('');
    expect(input('年字段').value).toBe('2028');
    expect(dialog().querySelector('[role="alert"]')?.textContent).toBeTruthy();
    expect(button('确定').disabled).toBe(true);
    button('确定').click();
    await flush();
    expect(updated).not.toHaveBeenCalled();
  });

  it('打开已有 Spring 日与周联合规则时保留原样，确认不扩大调度范围', async () => {
    const value = '0 0 0 1 * 2';
    await mount(value, { weekMode: 'spring' });
    await shortcut('自定义');
    expect(input('日字段').value).toBe('1');
    expect(input('周字段').value).toBe('2');
    expect(expression()).toBe(value);
    expect(dialog().querySelector('[role="alert"]')).toBeNull();
    expect(button('确定').disabled).toBe(false);
    button('确定').click();
    await flush();
    expect(updated).toHaveBeenCalledExactlyOnceWith(value);
  });

  it('打开 Quartz 同时指定日与周的无效规则时不静默修复', async () => {
    const value = '0 0 0 1 * 2';
    await mount(value);
    await shortcut('自定义');
    expect(input('日字段').value).toBe('1');
    expect(input('周字段').value).toBe('2');
    expect(expression()).toBe(value);
    expect(dialog().querySelector('[role="alert"]')?.textContent).toBeTruthy();
    expect(button('确定').disabled).toBe(true);
    expect(updated).not.toHaveBeenCalled();
  });

  it.each(['手动输入', '外部更新'])(
    '%s 修改已有快捷项后，可再次选择同一快捷项',
    async (source) => {
      await mount();
      await shortcut('每小时');
      expect(model.value).toBe('0 0 * * * ?');
      if (source === '手动输入') {
        const control =
          host.querySelector<HTMLInputElement>('input.ant-input')!;
        control.value = '0 0 12 * * ?';
        control.dispatchEvent(new Event('input', { bubbles: true }));
      } else {
        model.value = '0 0 12 * * ?';
      }
      await flush();
      expect(model.value).toBe('0 0 12 * * ?');
      await shortcut('每小时');
      expect(model.value).toBe('0 0 * * * ?');
      expect(updated).toHaveBeenLastCalledWith('0 0 * * * ?');
      expect(updated).toHaveBeenCalledTimes(source === '手动输入' ? 3 : 2);
    },
  );

  it('自定义预览插槽也显示本地无效草稿错误，且不把移位字段交给预览', async () => {
    const preview = vi.fn(({ expression }: { expression: string }) =>
      h('div', { 'data-testid': 'custom-preview' }, expression),
    );
    await mount('0 0 0 * * ? 2028', { preview });
    await shortcut('自定义');
    expect(preview).toHaveBeenCalled();
    expect(
      dialog().querySelector('[data-testid="custom-preview"]'),
    ).not.toBeNull();
    preview.mockClear();
    const seconds = dialog().querySelector<HTMLElement>(
      '[role="tabpanel"][aria-hidden="false"] .cron-appoint',
    )!;
    seconds
      .querySelector<HTMLElement>('.ant-select-selection-item-remove')!
      .click();
    await flush();
    expect(input('秒字段').value).toBe('');
    expect(input('年字段').value).toBe('2028');
    expect(dialog().querySelector('[role="alert"]')?.textContent).toBeTruthy();
    expect(dialog().querySelector('[data-testid="custom-preview"]')).toBeNull();
    expect(preview).not.toHaveBeenCalled();
    expect(button('确定').disabled).toBe(true);
  });
});
