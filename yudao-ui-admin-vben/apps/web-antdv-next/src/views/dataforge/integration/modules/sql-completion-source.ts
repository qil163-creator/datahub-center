import type { FoundationDatabaseApi } from '#/api/dataforge/database';
import type { SqlCompletionSource } from '#/components/monaco/sql-completion';

import { getDatabaseTableMeta } from '#/api/dataforge/database';

/**
 * 数据库节点自定义 SQL 的补全来源：节点已加载的表与视图目录，字段按表读取（失败不弹提示，稍后可重试）。
 * 没有数据连接时返回空来源，不回退到其他页面的补全数据。
 */
export function databaseCompletionSource(
  databaseId: FoundationDatabaseApi.Id | null | undefined,
  tables: readonly FoundationDatabaseApi.DatabaseTable[],
): SqlCompletionSource {
  if (databaseId === null || databaseId === undefined || databaseId === '')
    return { tables: [] };
  return {
    tables: tables.map((table) => ({
      name: table.tableName,
      comment: table.remarks,
    })),
    async loadColumns(tableName) {
      const meta = await getDatabaseTableMeta(databaseId, tableName, {
        silentError: true,
      });
      return (meta.columns ?? []).map((column) => ({
        name: column.fieldName,
        dataType: column.fieldTypeName,
        comment: column.remarks,
      }));
    },
  };
}
