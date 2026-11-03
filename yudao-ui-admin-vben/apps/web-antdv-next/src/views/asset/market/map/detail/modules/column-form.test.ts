import type { App } from 'vue';

import { createApp, defineComponent, h, nextTick, reactive } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import {
  getDataAssetColumn,
  updateDataAssetColumn,
} from '#/api/asset/dataasset';
import { getSimpleLevelList } from '#/api/governance/level';

import ColumnForm from './column-form.vue';

const harness = vi.hoisted(() => ({
  options: undefined as
    | undefined
    | {
        onConfirm: () => Promise<void>;
        onOpenChange: (open: boolean) => Promise<void>;
      },
  data: {
    assetId: '9007199254740993',
    column: {
      id: '9007199254740994',
      resourceUrn: 'urn:column:stable',
      revision: 1,
      businessName: '冻结旧值',
    },
  },
  state: { confirmDisabled: true, loading: false },
  locked: false,
  access: undefined as
    | undefined
    | { accessToken: string; refreshToken: string },
}));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: (options: typeof harness.options) => {
    harness.options = options;
    return [
      defineComponent({
        setup:
          (_, { slots }) =>
          () =>
            h('section', slots.default?.()),
      }),
      {
        getData: () => harness.data,
        lock: () => {
          harness.locked = true;
        },
        unlock: () => {
          harness.locked = false;
        },
        setState: (state: object) => Object.assign(harness.state, state),
        close: () => options?.onOpenChange(false),
      },
    ];
  },
}));
vi.mock('@vben/stores', () => {
  harness.access = reactive({ accessToken: 'session-1', refreshToken: '' });
  return { useAccessStore: () => harness.access };
});
vi.mock('#/api/asset/dataasset', () => ({
  getDataAssetColumn: vi.fn(),
  updateDataAssetColumn: vi.fn(),
}));
vi.mock('#/api/governance/level', () => ({ getSimpleLevelList: vi.fn() }));
vi.mock('#/views/governance/components/published-standard-picker.vue', () => ({
  default: defineComponent({
    setup: (_, { expose }) => {
      expose({ close: () => {}, open: () => {} });
      return () => h('div');
    },
  }),
}));
vi.mock('antdv-next', () => {
  const container = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('div', [slots.default?.(), slots.action?.()]),
  });
  const input = defineComponent({
    props: { value: [String, Number] },
    setup: (props) => () => h('input', { value: props.value }),
  });
  return {
    Alert: container,
    Button: defineComponent({
      setup:
        (_, { slots }) =>
        () =>
          h('button', slots.default?.()),
    }),
    Form: container,
    FormItem: container,
    Input: Object.assign(input, { TextArea: input }),
    InputNumber: input,
    Radio: container,
    RadioGroup: container,
    Select: container,
    message: { success: vi.fn(), warning: vi.fn() },
  };
});
let app: App | undefined;
let host: HTMLDivElement;
const detail = {
  id: '9007199254740994',
  revision: 8,
  columnName: 'station_code',
  businessName: '最新业务名称',
  resourceUrn: 'urn:column:stable',
  standardElementId: 12,
  standardElementName: '站点编码',
  standardElementCode: 'stationCode',
  safetyLevelId: '9223372036854775807',
  remark: '保留现有备注',
};
function deferred<T>() {
  let resolve!: (value: T) => void;
  const promise = new Promise<T>((yes) => {
    resolve = yes;
  });
  return { promise, resolve };
}
beforeEach(() => {
  vi.resetAllMocks();
  Object.assign(harness.access!, {
    accessToken: 'session-1',
    refreshToken: '',
  });
  harness.data = {
    assetId: '9007199254740993',
    column: {
      id: '9007199254740994',
      resourceUrn: 'urn:column:stable',
      revision: 1,
      businessName: '冻结旧值',
    },
  };
  harness.locked = false;
  vi.mocked(getDataAssetColumn).mockResolvedValue(detail);
  vi.mocked(updateDataAssetColumn).mockResolvedValue(true);
  vi.mocked(getSimpleLevelList).mockResolvedValue([]);
  host = document.createElement('div');
  document.body.append(host);
  app = createApp(ColumnForm);
  app.mount(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});
describe('资产冻结字段身份与最新编辑合同', () => {
  it('冻结列表只提供身份，通过资产入口读取最新修订和关联标准', async () => {
    await harness.options!.onOpenChange(true);
    await nextTick();
    expect(getDataAssetColumn).toHaveBeenCalledExactlyOnceWith(
      '9007199254740993',
      '9007199254740994',
    );
    expect(host.textContent).toContain('站点编码（stationCode）');
    await harness.options!.onConfirm();
    expect(updateDataAssetColumn).toHaveBeenCalledWith(
      expect.objectContaining({
        assetId: '9007199254740993',
        id: '9007199254740994',
        revision: 8,
        businessName: '最新业务名称',
        standardElementId: 12,
        safetyLevelId: '9223372036854775807',
        remark: '保留现有备注',
      }),
    );
    expect(harness.locked).toBe(false);
  });
  it('读取失败不能写冻结旧值，重试保留资产与字段大ID', async () => {
    vi.mocked(getDataAssetColumn).mockRejectedValueOnce(new Error('读取失败'));
    await harness.options!.onOpenChange(true);
    await harness.options!.onConfirm();
    expect(updateDataAssetColumn).not.toHaveBeenCalled();
    await nextTick();
    [...host.querySelectorAll('button')]
      .find((button) => button.textContent?.trim() === '重新加载')!
      .click();
    await expect.poll(() => harness.state.confirmDisabled).toBe(false);
    expect(getDataAssetColumn).toHaveBeenLastCalledWith(
      '9007199254740993',
      '9007199254740994',
    );
  });
  it('安全等级目录不可用时仍保留原Long关联，冲突后禁止盲目覆盖', async () => {
    vi.mocked(getSimpleLevelList).mockRejectedValueOnce(
      new Error('目录不可用'),
    );
    await harness.options!.onOpenChange(true);
    vi.mocked(updateDataAssetColumn).mockRejectedValueOnce(
      new Error('revision冲突'),
    );
    await harness.options!.onConfirm();
    expect(updateDataAssetColumn).toHaveBeenCalledWith(
      expect.objectContaining({
        safetyLevelId: '9223372036854775807',
        revision: 8,
      }),
    );
    expect(harness.state.confirmDisabled).toBe(true);
    expect(harness.locked).toBe(false);
    await harness.options!.onConfirm();
    expect(updateDataAssetColumn).toHaveBeenCalledTimes(1);
  });
  it('旧对象响应不覆盖新对象和其loading', async () => {
    const old = deferred<typeof detail>();
    const latest = deferred<typeof detail>();
    vi.mocked(getDataAssetColumn)
      .mockReturnValueOnce(old.promise)
      .mockReturnValueOnce(latest.promise);
    const first = harness.options!.onOpenChange(true);
    harness.data.column.id = '9007199254740995';
    const second = harness.options!.onOpenChange(true);
    old.resolve(detail);
    await first;
    expect(harness.state.loading).toBe(true);
    latest.resolve({ ...detail, id: '9007199254740995', revision: 9 });
    await second;
    await harness.options!.onConfirm();
    expect(updateDataAssetColumn).toHaveBeenCalledWith(
      expect.objectContaining({ id: '9007199254740995', revision: 9 }),
    );
  });
  it.each(['close', 'session', 'unmount'] as const)(
    '%s后当前字段响应失效',
    async (action) => {
      const pending = deferred<typeof detail>();
      vi.mocked(getDataAssetColumn).mockReturnValueOnce(pending.promise);
      const opening = harness.options!.onOpenChange(true);
      if (action === 'close') await harness.options!.onOpenChange(false);
      if (action === 'session') {
        harness.access!.refreshToken = 'session-2';
        await nextTick();
      }
      if (action === 'unmount') {
        app!.unmount();
        app = undefined;
      }
      pending.resolve(detail);
      await opening;
      await harness.options!.onConfirm();
      expect(updateDataAssetColumn).not.toHaveBeenCalled();
    },
  );
});
