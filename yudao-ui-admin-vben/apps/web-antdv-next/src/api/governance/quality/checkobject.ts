import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace QualityCheckObjectApi {
  /** 检查对象 */
  export interface CheckObject {
    id?: number;
    objectName?: string;
    datasourceConnId?: number;
    datasourceConnName?: string;
    datasourceType?: string;
    datasourceInstance?: string;
    formatName?: string;
    tableName?: string;
    tableId?: number;
    remark?: string;
    creator?: string;
    createTime?: string;
  }
}

/** 检查对象分页 */
export function getCheckObjectPage(
  params: PageParam & {
    datasourceConnId?: number;
    datasourceType?: string;
    objectName?: string;
    tableName?: string;
  },
) {
  return requestClient.get<PageResult<QualityCheckObjectApi.CheckObject>>(
    '/governance/quality-check-object/page',
    { params },
  );
}

/** 检查对象详情 */
export function getCheckObject(id: number) {
  return requestClient.get<QualityCheckObjectApi.CheckObject>(
    `/governance/quality-check-object/get?id=${id}`,
  );
}

/** 检查对象精简列表 */
export function getCheckObjectSimpleList() {
  return requestClient.get<QualityCheckObjectApi.CheckObject[]>(
    '/governance/quality-check-object/simple-list',
  );
}

/** 新增检查对象 */
export function createCheckObject(data: QualityCheckObjectApi.CheckObject) {
  return requestClient.post('/governance/quality-check-object/create', data);
}

/** 修改检查对象 */
export function updateCheckObject(data: QualityCheckObjectApi.CheckObject) {
  return requestClient.put('/governance/quality-check-object/update', data);
}

/** 删除检查对象 */
export function deleteCheckObject(id: number) {
  return requestClient.delete(
    `/governance/quality-check-object/delete?id=${id}`,
  );
}
