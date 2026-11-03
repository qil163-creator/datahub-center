import { describe, expect, it } from 'vitest';

import { resolveHiddenMenuPath } from './menu-active-path';

const menus = [{ path: '/model', children: [{ path: '/model/models', children: [{ path: '/model/models/source-table' }] }] }];

describe('隐藏页面的菜单归属', () => {
  it('隐藏创建模型页使用最近可见父菜单，保留完整侧栏', () => {
    expect(resolveHiddenMenuPath('/model/models/logic-model', undefined, menus))
      .toBe('/model/models');
  });
  it('辅助详情优先使用其明确归属的现有列表', () => {
    expect(resolveHiddenMenuPath('/modeling/table-model/detail/12', '/model/models/source-table', menus))
      .toBe('/model/models/source-table');
  });
  it('不将相似前缀视为父菜单，也不凭空补造菜单', () => {
    expect(resolveHiddenMenuPath('/model-other/detail/1', undefined, menus)).toBeUndefined();
    expect(resolveHiddenMenuPath('/model/models/logic-model', undefined, [])).toBeUndefined();
  });
});
