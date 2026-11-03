import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace FoundationDatabaseTypeApi {
  /** 数据库类型定义信息 */
  export interface DatabaseType {
    id: number | string; // 主键
    typeCode: string; // 数据源编码
    typeName: string; // 数据源名称
    description?: string; // 连接器描述
    categoryCode: string; // 连接分类
    protocolType: string; // 连接协议类型
    iconPath: string; // 连接图标路径
    urlTemplates: string; // 数据源URL
    portTemplates: string; // 默认端口
    validationQuery: string; // 默认连接测试SQL
    sort?: number; // 排序
    status?: number; // 状态
    remark: string; // 备注
    createTime?: number; // 创建时间（毫秒时间戳）
    productId?: string;
    cursorTargets?: readonly string[];
    capabilities?: Record<
      string,
      {
        reason: string;
        status: 'DECLARED' | 'UNSUPPORTED' | 'UNVERIFIED' | 'VERIFIED';
      }
    >;
  }
}

/** 查询数据库类型定义分页 */
export function getDatabaseTypePage(params: PageParam) {
  return requestClient.get<PageResult<FoundationDatabaseTypeApi.DatabaseType>>(
    '/dataforge/database-type/page',
    { params },
  );
}

/** 查询开启的数据库类型列表（按分类筛选，用于新建连接） */
export function getDatabaseTypeSimpleList(
  params?: {
    categoryCode?: string;
    typeName?: string;
  },
  requestOptions?: { silentError?: boolean },
) {
  return requestClient.get<FoundationDatabaseTypeApi.DatabaseType[]>(
    '/dataforge/database-type/simple-list',
    { params, ...requestOptions },
  );
}

/** 查询数据库类型定义详情 */
export function getDatabaseType(
  id: FoundationDatabaseTypeApi.DatabaseType['id'],
) {
  return requestClient.get<FoundationDatabaseTypeApi.DatabaseType>(
    '/dataforge/database-type/get',
    { params: { id } },
  );
}

/** 新增数据库类型定义 */
export function createDatabaseType(
  data: FoundationDatabaseTypeApi.DatabaseType,
) {
  return requestClient.post('/dataforge/database-type/create', data);
}

/** 修改数据库类型定义 */
export function updateDatabaseType(
  data: FoundationDatabaseTypeApi.DatabaseType,
) {
  return requestClient.put('/dataforge/database-type/update', data);
}

/** 更新数据库类型状态 */
export function updateDatabaseTypeStatus(data: {
  id: FoundationDatabaseTypeApi.DatabaseType['id'];
  status: number;
}) {
  return requestClient.put<boolean>('/dataforge/database-type/update-status', data);
}

/** 删除数据库类型定义 */
export function deleteDatabaseType(
  id: FoundationDatabaseTypeApi.DatabaseType['id'],
) {
  return requestClient.delete(`/dataforge/database-type/delete?id=${id}`);
}

/** 批量删除数据库类型定义 */
export function deleteDatabaseTypeList(
  ids: FoundationDatabaseTypeApi.DatabaseType['id'][],
) {
  return requestClient.delete(
    `/dataforge/database-type/delete-list?ids=${ids.join(',')}`,
  );
}

/** 导出数据库类型定义 */
export function exportDatabaseType(params: any) {
  return requestClient.download('/dataforge/database-type/export-excel', {
    params,
  });
}
