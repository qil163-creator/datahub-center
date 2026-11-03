import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

/** 兼容后端 snake_case 字段，并保证 sortOrder 为数字 */
function normalizeSystemSource(
  item: FoundationSystemSourceApi.SystemSource & { sort_order?: number },
): FoundationSystemSourceApi.SystemSource {
  const sortOrder = item.sortOrder ?? item.sort_order;
  return {
    ...item,
    sortOrder:
      sortOrder === null || sortOrder === undefined
        ? undefined
        : Number(sortOrder),
  };
}

export namespace FoundationSystemSourceApi {
  /** 来源系统信息 */
  export interface SystemSource {
    id: number; // 主键
    name?: string; // 系统名称
    icon: string; // 系统图标
    url: string; // 系统地址
    type?: string; // 系统类型
    ownerUserId?: number; // 负责人ID
    ownerUserName?: string; // 负责人名称
    ownerMobile?: string; // 负责人联系方式
    description: string; // 说明
    sortOrder?: number; // 排序
    deptId?: number; // 所属部门id
    deptName?: string; // 所属部门名称
    status?: number; // 状态：0停用 1启用
    remark: string; // 备注
    createTime?: Date | string; // 创建时间
    updateTime?: Date | string; // 更新时间
  }
}

/** 查询来源系统分页 */
export async function getSystemSourcePage(params: PageParam) {
  const result = await requestClient.get<
    PageResult<FoundationSystemSourceApi.SystemSource & { sort_order?: number }>
  >('/dataforge/system-source/page', { params });
  return {
    ...result,
    list: result.list.map((item) => normalizeSystemSource(item)),
  };
}

/** 查询来源系统详情 */
export async function getSystemSource(id: number) {
  const result = await requestClient.get<
    FoundationSystemSourceApi.SystemSource & { sort_order?: number }
  >(`/dataforge/system-source/get?id=${id}`);
  return normalizeSystemSource(result);
}

/** 新增来源系统 */
export function createSystemSource(
  data: FoundationSystemSourceApi.SystemSource,
) {
  return requestClient.post('/dataforge/system-source/create', data);
}

/** 修改来源系统 */
export function updateSystemSource(
  data: FoundationSystemSourceApi.SystemSource,
) {
  return requestClient.put('/dataforge/system-source/update', data);
}

/** 删除来源系统 */
export function deleteSystemSource(id: number) {
  return requestClient.delete(`/dataforge/system-source/delete?id=${id}`);
}

/** 批量删除来源系统 */
export function deleteSystemSourceList(ids: number[]) {
  return requestClient.delete(
    `/dataforge/system-source/delete-list?ids=${ids.join(',')}`,
  );
}

/** 导出来源系统 */
export function exportSystemSource(params: any) {
  return requestClient.download('/dataforge/system-source/export-excel', {
    params,
  });
}
