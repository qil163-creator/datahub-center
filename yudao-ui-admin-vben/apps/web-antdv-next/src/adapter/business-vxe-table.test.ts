import { describe, expect, it, vi } from 'vitest';

import { useVbenVxeGrid } from './business-vxe-table';

const harness = vi.hoisted(() => ({ useGrid: vi.fn(() => [null, {}]) }));

vi.mock('./vxe-table', () => ({ useVbenVxeGrid: harness.useGrid }));

describe('业务列表网格工具栏', () => {
  it('关闭全屏并保留页面配置的搜索、刷新和列设置', () => {
    const refresh = vi.fn();
    useVbenVxeGrid({
      gridOptions: {
        toolbarConfig: {
          custom: true,
          refresh: true,
          refreshOptions: { queryMethod: refresh },
          search: true,
          zoom: true,
        },
      },
    });
    expect(harness.useGrid).toHaveBeenCalledWith({
      gridOptions: {
        toolbarConfig: {
          custom: true,
          refresh: true,
          refreshOptions: { queryMethod: refresh },
          search: true,
          zoom: false,
        },
      },
    });
  });
});
