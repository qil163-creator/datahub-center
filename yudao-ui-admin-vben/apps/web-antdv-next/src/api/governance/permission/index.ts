import type { PageParam } from '@vben/request';

import { getRulePage } from '#/api/governance/rule';
import { requestClient } from '#/api/request';

export namespace SafetyPermissionApi {
  export interface Database {
    id: number | string;
    name?: string;
    usageType?: number;
    productType?: string;
  }

  export interface TableNode {
    schemaName: string;
    tableReference: string;
    tableName: string;
    remarks?: string;
    viewTable?: boolean;
  }

  export interface Column {
    fieldName: string;
    fieldType?: string;
    fieldComment?: string;
    pkFlag?: boolean;
    safetyLevelId?: number;
  }

  export interface TablePermission {
    permissionType: number;
    securityLevelId?: number;
    writePolicy?: null | WritePolicy;
    writeChangeReason?: string;
  }

  export interface WritePolicy {
    operations: ('DELETE' | 'INSERT' | 'UPDATE')[];
    writeColumns: string[];
    filterColumns: string[];
    rowScope: Record<string, null | string>;
    unrestrictedRows: boolean;
  }

  export interface WritePreflightResult {
    sql: string;
    operation: string;
    schemaName: string;
    tableName: string;
    configurationRevision: string;
    parameterCount: number;
  }

  export interface FieldPermission {
    fieldName: string;
    fieldType?: string;
    fieldComment?: string;
    permissionType: number;
    maskingRuleId?: null | number;
    securityLevelId?: number;
  }

  export interface RolePermissionConfig {
    roleId: number | string;
    roleName?: string;
    legacyTablePermissionId?: number | string;
    legacyFieldPermissionIds?: (number | string)[];
    tablePermission?: TablePermission;
    fieldPermissions?: FieldPermission[];
  }

  export interface PermissionConfig {
    schemaName?: null | string;
    tableReference?: string;
    legacyUnbound?: boolean;
    databaseId: number | string;
    tableName: string;
    rolePermissionConfigs: RolePermissionConfig[];
  }
}

export function getPermissionDatabaseList() {
  return requestClient.get<SafetyPermissionApi.Database[]>(
    '/governance/safety-permission/database-list',
  );
}

export function getPermissionTableList(
  databaseId: number | string,
  options?: { silentError?: boolean },
) {
  return requestClient.get<SafetyPermissionApi.TableNode[]>(
    '/governance/safety-permission/table-list',
    { params: { databaseId }, silentError: options?.silentError },
  );
}

export function getPermissionColumnList(
  databaseId: number | string,
  table: SafetyPermissionApi.TableNode,
) {
  return requestClient.get<SafetyPermissionApi.Column[]>(
    '/governance/safety-permission/column-list',
    {
      params: {
        databaseId,
        schemaName: table.schemaName,
        tableName: table.tableName,
        tableReference: table.tableReference,
      },
    },
  );
}

export function getPermissionConfig(
  databaseId: number | string,
  table: SafetyPermissionApi.TableNode,
  roleId: number | string,
) {
  return requestClient.get<SafetyPermissionApi.PermissionConfig>(
    '/governance/safety-permission/config',
    {
      params: {
        databaseId,
        schemaName: table.schemaName,
        tableName: table.tableName,
        tableReference: table.tableReference,
        roleId,
      },
    },
  );
}

export function getRolePermissionConfigs(roleId: number | string) {
  return requestClient.get<SafetyPermissionApi.PermissionConfig[]>(
    '/governance/safety-permission/role',
    { params: { roleId } },
  );
}

export function savePermissionConfig(
  data: Pick<SafetyPermissionApi.TableNode, 'schemaName' | 'tableReference'> &
    SafetyPermissionApi.PermissionConfig,
) {
  return requestClient.post('/governance/safety-permission/save', data);
}

export function getRuleSimpleList() {
  return getRulePage({ pageNo: 1, pageSize: 200 } as PageParam);
}

export function bindLegacyPermission(data: {
  databaseId: number | string;
  legacyTableName: string;
  note: string;
  roleId: number | string;
  schemaName: string;
  tableName: string;
  tableReference: string;
}) {
  return requestClient.post('/governance/safety-permission/bind-legacy', data);
}

export function preflightWritePermission(data: {
  databaseId: number | string;
  parameterValues: (null | string)[];
  roleId: number | string;
  sql: string;
}) {
  return requestClient.post<SafetyPermissionApi.WritePreflightResult>(
    '/governance/safety-permission/write-preflight',
    {
      databaseId: data.databaseId,
      roleId: data.roleId,
      sql: data.sql,
      parameterValues: data.parameterValues,
    },
  );
}
