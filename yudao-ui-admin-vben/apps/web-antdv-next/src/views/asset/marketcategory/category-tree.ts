import type { AssetMarketCategoryApi } from '#/api/asset/marketcategory';

import { handleTree } from '@vben/utils';

/** 树构造仅处理副本，保留原始 ID；查询中缺少父节点的记录仍作为根显示。 */
export function marketCategoryTree(
  rows: AssetMarketCategoryApi.MarketCategory[],
) {
  return handleTree<AssetMarketCategoryApi.MarketCategory>(
    rows.map((row) => ({ ...row, children: undefined })),
  );
}

/** 修改时排除自身及所有下级，避免让用户选择会造成循环的父节点。 */
export function marketCategoryParentOptions(
  rows: AssetMarketCategoryApi.MarketCategory[],
  editingId?: AssetMarketCategoryApi.Id,
) {
  const excluded = new Set<string>();
  if (editingId !== undefined) {
    const children = new Map<string, string[]>();
    for (const row of rows) {
      const parent = String(row.parentId);
      children.set(parent, [...(children.get(parent) ?? []), String(row.id)]);
    }
    const queue = [String(editingId)];
    for (let index = 0; index < queue.length; index++) {
      const id = queue[index]!;
      if (excluded.has(id)) continue;
      excluded.add(id);
      queue.push(...(children.get(id) ?? []));
    }
  }
  return [
    {
      id: 0,
      name: '顶级节点',
      children: marketCategoryTree(
        rows.filter((row) => !excluded.has(String(row.id))),
      ),
    },
  ];
}
