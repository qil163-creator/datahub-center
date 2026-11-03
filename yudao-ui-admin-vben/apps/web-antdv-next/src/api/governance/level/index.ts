import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace SafetyLevelApi {
  /** 数据分级信息 */
  export interface Level {
    id: number; // 主键id
    safetyLevel?: string; // 安全等级
    safetyName?: string; // 显示名称
    color?: string; // 标志颜色（hex，如 #52c41a，为空时按等级使用默认颜色）
    status?: number; // 状态
    sort?: number; // 显示顺序
    description: string; // 规则描述
  }
}

/** 查询数据分级分页 */
export function getLevelPage(params: PageParam) {
  return requestClient.get<PageResult<SafetyLevelApi.Level>>(
    '/governance/safety-level/page',
    { params },
  );
}

/** 查询数据分级列表（不分页，用于下拉选择） */
export async function getSimpleLevelList() {
  const data = await getLevelPage({ pageNo: 1, pageSize: 100 });
  return data.list;
}

/** 查询数据分级详情 */
export function getLevel(id: number) {
  return requestClient.get<SafetyLevelApi.Level>(
    `/governance/safety-level/get?id=${id}`,
  );
}

/** 新增数据分级 */
export function createLevel(data: SafetyLevelApi.Level) {
  return requestClient.post('/governance/safety-level/create', data);
}

/** 修改数据分级 */
export function updateLevel(data: SafetyLevelApi.Level) {
  return requestClient.put('/governance/safety-level/update', data);
}

/** 删除数据分级 */
export function deleteLevel(id: number) {
  return requestClient.delete(`/governance/safety-level/delete?id=${id}`);
}

/** 批量删除数据分级 */
export function deleteLevelList(ids: number[]) {
  return requestClient.delete(
    `/governance/safety-level/delete-list?ids=${ids.join(',')}`,
  );
}

/** 导出数据分级 */
export function exportLevel(params: any) {
  return requestClient.download('/governance/safety-level/export-excel', {
    params,
  });
}
