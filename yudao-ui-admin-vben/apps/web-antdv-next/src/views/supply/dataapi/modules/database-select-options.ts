import type { FoundationDatabaseTypeApi } from '#/api/dataforge/databasetype';
import type { SupplyDataApiApi } from '#/api/supply/dataapi';

export interface DatabaseChoice {
  id: SupplyDataApiApi.Id;
  key: string;
  name: string;
  iconPath?: string;
  typeCode?: string;
  source: SupplyDataApiApi.DatabaseSimple;
}

export interface DatabaseTypeGroup {
  key: string;
  id?: SupplyDataApiApi.Id;
  name: string;
  iconPath?: string;
  typeCode?: string;
  databases: DatabaseChoice[];
}

export interface DatabaseCategoryGroup {
  code: string;
  name: string;
  count: number;
  types: DatabaseTypeGroup[];
}

/** 目录只补充展示信息，所有可选叶子均来自供给服务已授权的连接列表。 */
export function buildDatabaseSelectGroups(
  databases: readonly SupplyDataApiApi.DatabaseSimple[],
  catalog: readonly FoundationDatabaseTypeApi.DatabaseType[],
  categories: readonly { code: string; name: string }[],
  search = '',
): DatabaseCategoryGroup[] {
  const labels = new Map(categories.map((item) => [item.code, item.name]));
  const groups = new Map<string, DatabaseCategoryGroup>();
  const types = new Map(catalog.map((type) => [String(type.id), type]));
  const keyword = search.trim().toLocaleLowerCase();
  function category(code: string) {
    if (!groups.has(code)) {
      groups.set(code, {
        code,
        name: labels.get(code) || code || '未分类',
        count: 0,
        types: [],
      });
    }
    return groups.get(code)!;
  }
  function typeGroup(
    parent: DatabaseCategoryGroup,
    key: string,
    name: string,
    id?: SupplyDataApiApi.Id,
    iconPath?: string,
    typeCode?: string,
  ) {
    let group = parent.types.find((type) => type.key === key);
    if (!group) {
      group = { key, id, name, iconPath, typeCode, databases: [] };
      parent.types.push(group);
    } else if (!group.typeCode) {
      group.typeCode = typeCode;
    }
    return group;
  }
  for (const item of categories) category(item.code);
  for (const type of catalog) {
    typeGroup(
      category(type.categoryCode || ''),
      `type:${type.id}`,
      type.typeName || type.typeCode || `类型 ${type.id}`,
      type.id,
      type.iconPath,
      type.typeCode,
    );
  }
  const seen = new Set<string>();
  for (const database of databases) {
    if (database.id === undefined || database.id === null || database.id === '')
      continue;
    const key = String(database.id);
    if (seen.has(key)) continue;
    seen.add(key);
    const typeId = database.databaseType ?? undefined;
    const definition =
      typeId === undefined ? undefined : types.get(String(typeId));
    const typeCode = definition?.typeCode || database.productType;
    const parent = category(
      database.categoryCode ?? definition?.categoryCode ?? '',
    );
    const type = typeGroup(
      parent,
      typeId === undefined
        ? `product:${database.productType || ''}`
        : `type:${typeId}`,
      database.databaseTypeName ||
        definition?.typeName ||
        database.productType ||
        (typeId === undefined ? '未知类型' : `类型 ${typeId}`),
      typeId,
      database.iconPath || definition?.iconPath,
      typeCode,
    );
    const name = database.name?.trim() || `未命名连接 (${key})`;
    if (keyword && !name.toLocaleLowerCase().includes(keyword)) continue;
    type.databases.push({
      id: database.id,
      key,
      name,
      iconPath: database.iconPath || definition?.iconPath,
      typeCode,
      source: database,
    });
    parent.count++;
  }
  return [...groups.values()];
}
