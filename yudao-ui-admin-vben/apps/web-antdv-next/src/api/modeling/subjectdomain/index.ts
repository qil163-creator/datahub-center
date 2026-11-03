import { requestClient } from '#/api/request';

export namespace ModelingSubjectDomainApi {
  export interface ListReq {
    dataDomainId?: number | string;
    parentId?: number | string;
    name?: string;
    status?: number;
  }
  /** 分析主题信息 */
  export interface SubjectDomain {
    id?: number | string;
    parentId?: number | string; // 父级编号，0 表示顶级节点
    name?: string; // 分析主题名称
    code?: string; // 英文缩写
    leaderUserId?: number | string; // 负责人用户编号
    leaderPhone?: string; // 负责人电话
    status?: number; // 状态：0-启用 1-停用
    description?: string; // 描述
    remark?: string; // 备注
    creator?: string; // 创建者编号
    createTime?: number | string;
    updater?: string; // 更新者编号
    updateTime?: number | string;
    children?: SubjectDomain[];
    dataDomainId?: number | string;
    dataDomainName?: string;
    abbreviation?: string;
    leaderName?: string;
    creatorName?: string;
    creatorPhone?: string;
    businessObjectCount?: null | number;
  }
}

/** 查询分析主题列表（树形数据平铺返回） */
export function getSubjectDomainList(params: ModelingSubjectDomainApi.ListReq) {
  return requestClient.get<ModelingSubjectDomainApi.SubjectDomain[]>(
    '/modeling/subject-domain/list',
    { params },
  );
}

/** 查询分析主题详情 */
export function getSubjectDomain(id: number | string) {
  return requestClient.get<ModelingSubjectDomainApi.SubjectDomain>(
    `/modeling/subject-domain/get?id=${id}`,
  );
}

/** 新增分析主题 */
export function createSubjectDomain(
  data: ModelingSubjectDomainApi.SubjectDomain,
) {
  return requestClient.post('/modeling/subject-domain/create', data);
}

/** 修改分析主题 */
export function updateSubjectDomain(
  data: ModelingSubjectDomainApi.SubjectDomain,
) {
  return requestClient.put('/modeling/subject-domain/update', data);
}

/** 修改分析主题状态 */
export function updateSubjectDomainStatus(id: number | string, status: number) {
  return requestClient.put('/modeling/subject-domain/update-status', {
    id,
    status,
  });
}

/** 删除分析主题 */
export function deleteSubjectDomain(id: number | string) {
  return requestClient.delete(`/modeling/subject-domain/delete?id=${id}`);
}
