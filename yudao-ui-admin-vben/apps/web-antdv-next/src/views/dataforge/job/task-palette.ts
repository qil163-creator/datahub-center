import type { JobApi } from '#/api/dataforge/job';

export interface PaletteCategory {
  id: number | string;
  name: string;
  parentId?: number | string;
  kind: JobApi.TaskOption['kind'];
}
export type PaletteRow =
  | { count: number; depth: number; key: string; name: string; type: 'category'; }
  | { depth: number; key: string; task: JobApi.TaskOption; type: 'task'; };

/** 类目按编号组织，保留同名类目及其祖先路径；检索父类目时包含其下的任务。 */
export function taskPalette(tasks: JobApi.TaskOption[], categories: PaletteCategory[], search: string, collapsed: string[]) {
  const query = search.trim().toLowerCase();
  return (['INTEGRATION', 'DEVELOPMENT'] as const).map((kind) => {
    const groupTasks = tasks.filter((task) => task.kind === kind);
    const byId = new Map<string, PaletteCategory>(categories.filter((category) => category.kind === kind).map((category) => [String(category.id), {
      ...category,
      // 0 是目录根标识，不能被“未分类”任务生成的 0 类目当作父节点。
      parentId: String(category.parentId) === '0' ? undefined : category.parentId,
    }] as const));
    for (const task of groupTasks) {
      const id = String(task.categoryId ?? 'uncategorized');
      if (!byId.has(id)) byId.set(id, { id, name: task.categoryName || '未分类', kind });
    }
    const path = (id: string) => {
      const ancestors: PaletteCategory[] = [];
      const seen = new Set<string>();
      let item = byId.get(id);
      while (item && !seen.has(String(item.id))) {
        seen.add(String(item.id)); ancestors.unshift(item);
        item = byId.get(String(item.parentId));
      }
      return ancestors;
    };
    const matched = groupTasks.filter((task) => !query || `${task.name}/${path(String(task.categoryId ?? 'uncategorized')).map((item) => item.name).join('/')}`.toLowerCase().includes(query));
    const rows: PaletteRow[] = [];
    const visited = new Set<string>();
    const append = (category: PaletteCategory, depth: number) => {
      const id = String(category.id);
      if (visited.has(id)) return;
      visited.add(id);
      const members = matched.filter((task) => path(String(task.categoryId ?? 'uncategorized')).some((item) => String(item.id) === id));
      if (query && members.length === 0) return;
      const key = `${kind}/${id}`;
      rows.push({ type: 'category', key, name: category.name, depth, count: members.length });
      if (!query && collapsed.includes(key)) return;
      for (const child of byId.values()) if (String(child.parentId) === id) append(child, depth + 1);
      for (const task of matched) if (String(task.categoryId ?? 'uncategorized') === id) rows.push({ type: 'task', key: `${kind}:task:${task.id}`, task, depth: depth + 1 });
    };
    for (const category of byId.values()) if (!byId.has(String(category.parentId))) append(category, 1);
    // 损坏的历史类目仍可定位，不让循环关系导致页面无限递归。
    for (const category of byId.values()) if (!visited.has(String(category.id)) && byId.has(String(path(String(category.id))[0]?.parentId))) append(category, 1);
    return { kind, name: kind === 'INTEGRATION' ? '数据集成' : '数据开发', count: matched.length, rows };
  });
}
