/* eslint-disable vue/one-component-per-file -- 测试桩集中模拟表格单元格渲染 */
import type { App, PropType } from 'vue';

import type { DataAssetApi } from '#/api/asset/dataasset';

import { createApp, defineComponent, h, nextTick } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import ColumnsTab from './columns-tab.vue';

const mock = vi.hoisted(() => ({ codes: [] as string[], levels: vi.fn() }));
vi.mock('@vben/access', () => ({
  useAccess: () => ({
    hasAccessByCodes: (codes: string[]) =>
      codes.some((code) => mock.codes.includes(code)),
  }),
}));
vi.mock('#/api/governance/level', () => ({ getSimpleLevelList: mock.levels }));
vi.mock('@vben/icons', () => ({ IconifyIcon: { render: () => null } }));
vi.mock('./column-form.vue', () => ({ default: { render: () => null } }));
vi.mock('./term-bind-form.vue', () => ({ default: { render: () => null } }));
vi.mock('@vben/common-ui', () => ({
  useVbenModal: () => [{ render: () => null }, {}],
}));
vi.mock('antdv-next', () => {
  const box = defineComponent({
    setup:
      (_, { slots }) =>
      () =>
        h('section', slots.default?.()),
  });
  const table = defineComponent({
    props: {
      dataSource: {
        type: Array as PropType<DataAssetApi.MetadataColumn[]>,
        default: () => [],
      },
    },
    setup:
      (props, { slots }) =>
      () =>
        h(
          'section',
          props.dataSource.map((record) =>
            slots.bodyCell?.({ column: { key: 'safetyLevel' }, record }),
          ),
        ),
  });
  return {
    Button: box,
    Dropdown: box,
    CheckboxGroup: box,
    Input: box,
    Switch: box,
    Table: table,
    Tag: box,
    Tooltip: box,
  };
});

let app: App | undefined;
let host: HTMLDivElement;
async function mount() {
  app = createApp(ColumnsTab, {
    assetId: '9007199254740993',
    columns: [{ id: 1, columnName: 'name', safetyLevelId: 7 }],
  });
  app.directive('access', {});
  app.mount(host);
  for (let index = 0; index < 6; index++) await Promise.resolve();
  await nextTick();
}
beforeEach(() => {
  vi.resetAllMocks();
  mock.codes = [];
  mock.levels.mockResolvedValue([{ id: 7, safetyName: '内部', color: 'blue' }]);
  host = document.createElement('div');
  document.body.append(host);
});
afterEach(() => {
  app?.unmount();
  app = undefined;
  host.remove();
});

describe('asset columns level display permissions', () => {
  it('uses the existing level id without making an unauthorized governance request', async () => {
    await mount();
    expect(mock.levels).not.toHaveBeenCalled();
    expect(host.textContent).toContain('等级 #7');
  });
  it('enriches level names only with governance query permission', async () => {
    mock.codes = ['governance:level:query'];
    await mount();
    expect(mock.levels).toHaveBeenCalledOnce();
    expect(host.textContent).toContain('内部');
  });
});
