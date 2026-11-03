interface MenuPath {
  children?: MenuPath[];
  path: string;
}

/** 隐藏辅助页归属现有菜单，使用路径边界匹配，避免侧栏因隐藏地址而被清空。 */
export function resolveHiddenMenuPath(
  path: string,
  activePath: unknown,
  menus: MenuPath[],
): string | undefined {
  const paths: string[] = [];
  function collect(items: MenuPath[]) {
    for (const item of items) {
      paths.push(item.path);
      if (item.children) collect(item.children);
    }
  }
  collect(menus);
  if (typeof activePath === 'string' && paths.includes(activePath)) return activePath;
  return paths
    .filter((candidate) => candidate !== '/' && (path === candidate || path.startsWith(`${candidate}/`)))
    .toSorted((first, second) => second.length - first.length)[0];
}
