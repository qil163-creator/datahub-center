import type { App } from 'vue';

import type { BusinessEntityApi } from '#/api/modeling/business-entity';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import EntitySelector from './entity-selector.vue';

vi.mock('antdv-next', () => ({
  Popover: defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.content?.()]),
  }),
  Spin: (_: unknown, { slots }: { slots: { default?: () => unknown } }) =>
    slots.default?.(),
  Input: (
    props: { value?: string },
    { emit }: { emit: (event: string, value: string) => void },
  ) =>
    h('input', {
      value: props.value,
      onInput: (event: Event) =>
        emit('update:value', (event.target as HTMLInputElement).value),
    }),
}));

function entity(
  id: string,
  subtype: BusinessEntityApi.EntitySubtype,
): BusinessEntityApi.Entity {
  return {
    id,
    name: id === '9007199254740993' ? '订单' : '客户',
    code: id === '9007199254740993' ? 'ORDER' : 'CUSTOMER',
    dataDomainId: '1',
    subjectDomainId: '2',
    entityType: 'ACTIVITY',
    entitySubtype: subtype,
    status: 0,
    revision: '1',
    description: '',
    associatedEntityIds: [],
    predecessorEntityIds: [],
    processEventIds: [],
    logicalModelCount: 5,
  };
}
const entities = [
  entity('9007199254740993', 'EVENT'),
  entity('9007199254740995', 'PROCESS'),
];
let app: App | undefined;
let host: HTMLDivElement;
function mount(props: Record<string, unknown> = {}) {
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(EntitySelector, { entities, ...props });
  app.mount(host);
}
afterEach(() => {
  app?.unmount();
  host?.remove();
  app = undefined;
});

describe('业务实体分组选择', () => {
  it('显式可用实体清单优先于历史计数，并保留字符串ID', () => {
    const changed = vi.fn();
    mount({ createdEntityIds: ['9007199254740993'], onChange: changed });
    const options = host.querySelectorAll<HTMLButtonElement>('[role=option]');
    expect(options[0]!.disabled).toBe(false);
    expect(options[1]!.disabled).toBe(true);
    options[0]!.click();
    expect(changed).toHaveBeenCalledWith('9007199254740993');
  });
  it('当前值始终可展示选择，且分组与编码搜索一致', async () => {
    mount({ modelValue: '9007199254740995', createdEntityIds: [] });
    expect(
      host.querySelectorAll<HTMLButtonElement>('[role=option]')[1]!.disabled,
    ).toBe(false);
    const process = [...host.querySelectorAll('button')].find(
      (button) => button.textContent === '业务流程',
    )!;
    process.click();
    await nextTick();
    expect(host.querySelectorAll('[role=option]')).toHaveLength(1);
    const input = host.querySelector('input')!;
    input.value = 'CUSTOMER';
    input.dispatchEvent(new Event('input'));
    await nextTick();
    expect(host.querySelector('[role=option]')?.textContent).toContain('客户');
    input.value = 'ORDER';
    input.dispatchEvent(new Event('input'));
    await nextTick();
    expect(host.querySelectorAll('[role=option]')).toHaveLength(0);
  });
});
