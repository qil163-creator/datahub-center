import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace FoundationDatasourceDriverApi {
  /** 数据源驱动管理信息 */
  export interface DatasourceDriver {
    id: number; // 驱动编号
    driverCode: string; // 驱动编码
    driverName?: string; // 驱动名称
    productType?: string; // 数据库产品类型：MYSQL、POSTGRESQL、ORACLE、HIVE、DORIS、CLICKHOUSE   字典数据
    driverSource?: string; // 驱动来源：BUILTIN 内置驱动，UPLOAD 上传驱动    字典数据
    driverClassName?: string; // 驱动类名
    jdbcUrlTemplate?: string; // JDBC URL 模板
    testSql: string; // 默认连接测试 SQL
    icon: string; // 连接图标路径
    supportSchema?: number; // 是否有 schema 概念：1有 0无
    jarFileName: string; // 上传驱动 JAR 文件名称（内置为空）
    jarFilePath: string; // 上传驱动 JAR 文件路径（内置为空，走 classpath）
    jarFileMd5: string; // 上传驱动 JAR 文件 MD5
    jarFileSize: number; // 上传驱动 JAR 文件大小
    sort?: number; // 排序
    status?: number; // 状态
    remark: string; // 备注
  }
}

/** 查询数据源驱动管理分页 */
export function getDatasourceDriverPage(params: PageParam) {
  return requestClient.get<
    PageResult<FoundationDatasourceDriverApi.DatasourceDriver>
  >('/dataforge/datasource-driver/page', { params });
}

/** 查询数据源驱动管理详情 */
export function getDatasourceDriver(id: number) {
  return requestClient.get<FoundationDatasourceDriverApi.DatasourceDriver>(
    `/dataforge/datasource-driver/get?id=${id}`,
  );
}

/** 新增数据源驱动管理 */
export function createDatasourceDriver(
  data: FoundationDatasourceDriverApi.DatasourceDriver,
) {
  return requestClient.post('/dataforge/datasource-driver/create', data);
}

/** 修改数据源驱动管理 */
export function updateDatasourceDriver(
  data: FoundationDatasourceDriverApi.DatasourceDriver,
) {
  return requestClient.put('/dataforge/datasource-driver/update', data);
}

/** 删除数据源驱动管理 */
export function deleteDatasourceDriver(id: number) {
  return requestClient.delete(`/dataforge/datasource-driver/delete?id=${id}`);
}

/** 批量删除数据源驱动管理 */
export function deleteDatasourceDriverList(ids: number[]) {
  return requestClient.delete(
    `/dataforge/datasource-driver/delete-list?ids=${ids.join(',')}`,
  );
}

/** 导出数据源驱动管理 */
export function exportDatasourceDriver(params: any) {
  return requestClient.download('/dataforge/datasource-driver/export-excel', {
    params,
  });
}
