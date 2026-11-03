/* eslint-disable vue/one-component-per-file -- 用轻量控件验证版本回滚的确认、权限和租户边界。 */
import type { App, PropType } from 'vue';

import type { DataforgeDevApi } from '#/api/dataforge/dev';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { message, Modal } from 'antdv-next';
import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import * as devApi from '#/api/dataforge/dev';

import VersionModal from './version-modal.vue';

const permissions = new Set<string>();
const accessStore = reactive({
  accessToken: 1,
  refreshToken: undefined as number | undefined,
});

vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => permissions.has(code)),
  }),
}));
vi.mock('@vben/stores', () => ({ useAccessStore: () => accessStore }));
vi.mock('@vben/utils', () => ({
  formatDateTime: (value: unknown) => String(value ?? ''),
}));
vi.mock('#/api/request', () => ({ requestClient: {} }));
vi.mock('#/api/dataforge/dev', async (importActual) => ({
  ...(await importActual<typeof import('#/api/dataforge/dev')>()),
  getDevTask: vi.fn(),
  getDevTaskVersionList: vi.fn(),
  rollbackDevTaskVersion: vi.fn(),
}));
vi.mock('#/components/monaco/sql-editor.vue', () => ({
  default: defineComponent(() => () => null),
}));
vi.mock('antdv-next', () => {
  const slot = defineComponent({
    setup:
      (_props, { slots }) =>
      () =>
        h('div', slots.default?.()),
  });
  return {
    Button: defineComponent({
      props: { disabled: Boolean },
      emits: ['click'],
      setup:
        (props, { emit, slots }) =>
        () =>
          h(
            'button',
            {
              disabled: props.disabled,
              onClick: () => emit('click'),
            },
            slots.default?.(),
          ),
    }),
    Drawer: defineComponent({
      props: { open: Boolean },
      setup:
        (props, { slots }) =>
        () =>
          props.open ? h('section', slots.default?.()) : null,
    }),
    Table: defineComponent({
      props: {
        dataSource: {
          type: Array as PropType<DataforgeDevApi.TaskVersion[]>,
          default: () => [],
        },
      },
      setup:
        (props, { slots }) =>
        () =>
          h(
            'div',
            props.dataSource?.map((record) =>
              h('div', [
                slots.bodyCell?.({ column: { key: 'version' }, record }),
                slots.bodyCell?.({ column: { key: 'actions' }, record }),
              ]),
            ),
          ),
    }),
    Tag: slot,
    Modal: { confirm: vi.fn(() => ({ destroy: vi.fn() })) },
    message: { success: vi.fn() },
  };
});

let app: App;
let host: HTMLDivElement;
let drawer: {
  close: () => void;
  open: (id: DataforgeDevApi.Id) => Promise<void>;
};
let changed: ReturnType<typeof vi.fn>;

async function flush() {
  for (let count = 0; count < 6; count++) await Promise.resolve();
  await nextTick();
}

function confirmRollback() {
  const rollback = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (button) => button.textContent?.trim() === '回滚',
  );
  expect(rollback).toBeDefined();
  rollback!.click();
  const confirmation = vi.mocked(Modal.confirm).mock.calls.at(-1)?.[0];
  expect(confirmation).toBeDefined();
  return confirmation!.onOk!;
}

beforeEach(() => {
  vi.clearAllMocks();
  permissions.clear();
  permissions.add('dataforge:dev-task:save');
  accessStore.accessToken = 1;
  accessStore.refreshToken = undefined;
  vi.mocked(devApi.getDevTask).mockImplementation(async (id) => ({
    id,
    revision: 7,
    currentVersionId: '9007199254740995',
  }));
  vi.mocked(devApi.getDevTaskVersionList).mockResolvedValue([
    {
      id: '9007199254740995',
      taskId: '9007199254740993',
      version: 2,
      content: 'SELECT 1',
    },
  ]);
  vi.mocked(devApi.rollbackDevTaskVersion).mockResolvedValue(undefined);
  changed = vi.fn();
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(VersionModal, { canEdit: true, onChanged: changed });
  app.directive('access', () => {});
  drawer = app.mount(host) as unknown as typeof drawer;
});

afterEach(() => {
  app.unmount();
  host.remove();
});

describe('版本回滚确认边界', () => {
  it('正常回滚保留版本大编号与修订号，随后刷新当前版本', async () => {
    await drawer.open('9007199254740993');
    expect(host.textContent).toContain('当前版本');
    const confirm = confirmRollback();
    await confirm();
    expect(devApi.rollbackDevTaskVersion).toHaveBeenCalledWith(
      '9007199254740995',
      7,
    );
    expect(devApi.getDevTask).toHaveBeenCalledTimes(2);
    expect(changed).toHaveBeenCalledOnce();
  });

  it('关闭抽屉后确认旧弹窗，不发送回滚', async () => {
    await drawer.open('9007199254740993');
    const confirm = confirmRollback();
    drawer.close();
    await confirm();
    expect(devApi.rollbackDevTaskVersion).not.toHaveBeenCalled();
  });

  it('重新打开其他任务后，旧确认不能回滚旧任务', async () => {
    await drawer.open('9007199254740993');
    const confirm = confirmRollback();
    await drawer.open('9007199254740997');
    await confirm();
    expect(devApi.rollbackDevTaskVersion).not.toHaveBeenCalled();
  });

  it('租户切换关闭抽屉并使旧确认失效', async () => {
    await drawer.open('9007199254740993');
    const confirm = confirmRollback();
    accessStore.refreshToken = 2;
    await confirm();
    await flush();
    expect(host.querySelector('section')).toBeNull();
    expect(devApi.rollbackDevTaskVersion).not.toHaveBeenCalled();
  });

  it('确认时重新检查保存权限，撤销权限后不回滚', async () => {
    await drawer.open('9007199254740993');
    const confirm = confirmRollback();
    permissions.clear();
    await confirm();
    expect(devApi.rollbackDevTaskVersion).not.toHaveBeenCalled();
  });

  it('旧任务回滚慢响应不刷新新任务或发出成功事件', async () => {
    let finish!: () => void;
    vi.mocked(devApi.rollbackDevTaskVersion).mockImplementation(
      () =>
        new Promise<void>((resolve) => {
          finish = resolve;
        }),
    );
    await drawer.open('9007199254740993');
    const confirm = confirmRollback();
    const pending = confirm();
    await flush();
    await drawer.open('9007199254740997');
    finish();
    await pending;
    expect(devApi.getDevTask).toHaveBeenCalledTimes(2);
    expect(message.success).not.toHaveBeenCalled();
    expect(changed).not.toHaveBeenCalled();
  });
});
