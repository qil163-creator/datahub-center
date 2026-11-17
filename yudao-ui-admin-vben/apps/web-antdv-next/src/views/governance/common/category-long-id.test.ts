import { createApp } from 'vue';

import { describe, expect, it, vi } from 'vitest';

import DataMetaCategories from '../categorydatameta/index.vue';
import DataQualityCategories from '../categorydataquality/index.vue';

interface Row {
  id: number | string;
  parentId?: number | string;
}
interface GridOptions {
  columns: {
    cellRender?: {
      attrs: { beforeChange: (status: number, row: Row) => Promise<boolean> };
    };
    field: string;
  }[];
  proxyConfig: {
    ajax: { query: (page: object, filter: object) => Promise<Row[]> };
  };
}

const mocks = vi.hoisted(() => ({
  get: vi.fn(),
  grid: vi.fn<(options: { gridOptions: GridOptions }) => void>(),
  put: vi.fn(),
}));

vi.mock('#/api/request', () => ({ requestClient: mocks }));
vi.mock('#/adapter/business-vxe-table', () => ({
  ACTION_ICON: {},
  TableAction: () => null,
  useVbenVxeGrid: (options: { gridOptions: GridOptions }) => {
    mocks.grid(options);
    return [() => null, {}];
  },
}));
vi.mock('@vben/common-ui', () => ({
  confirm: vi.fn(),
  Page: () => null,
  useVbenModal: () => [() => null, {}],
}));
vi.mock('@vben/hooks', () => ({ getDictOptions: () => [] }));
vi.mock('antdv-next', () => ({ message: { success: vi.fn() } }));
vi.mock('#/locales', () => ({ $t: (key: string) => key }));
vi.mock('../categorydatameta/modules/form.vue', () => ({ default: {} }));
vi.mock('../categorydataquality/modules/form.vue', () => ({ default: {} }));
vi.mock('./tree-category-detail.vue', () => ({ default: {} }));

describe('治理分类的长编号', () => {
  it.each([
    ['category-datameta', DataMetaCategories],
    ['category-data-quality', DataQualityCategories],
  ] as const)('%s 切换子节点状态时保留原始父编号', async (path, component) => {
    const parentId = '9007199254740993';
    const childId = '9223372036854775807';
    mocks.get.mockResolvedValue([{ id: parentId }, { id: childId, parentId }]);

    const app = createApp(component);
    app.mount(document.createElement('div'));
    try {
      const { gridOptions } = mocks.grid.mock.lastCall![0];
      const [root, child] = await gridOptions.proxyConfig.ajax.query({}, {});
      expect(root?.parentId).toBe(0);
      const status = gridOptions.columns.find((column) => column.field === 'status');
      await status!.cellRender!.attrs.beforeChange(1, child!);
      expect(mocks.put).toHaveBeenLastCalledWith(
        `/governance/${path}/update`,
        expect.objectContaining({ id: childId, parentId, status: 1 }),
      );
    } finally {
      app.unmount();
    }
  });
});
