import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace StandardDocApi {
  export type Id = number | string;
  /** 标准文件登记信息 */
  export interface Doc {
    id: Id; // 标准文件编号
    categoryId?: Id | null;
    categoryName?: null | string;
    versionNo?: null | string;
    abolishDate?: null | string;
    creator?: string;
    creatorName?: string;
    updateTime?: number;
    stdCode: null | string; // 标准编号
    nameCn: string; // 标准名称
    nameEn: null | string; // 标准英文名称（已废弃）
    stdType: string; // 标准分类
    publishOrg: null | string; // 发布机构
    publishDate: null | string; // 发布日期
    implementDate: null | string; // 实施日期
    implementStatus: string; // 实施状态
    registryNo: null | string; // 入库编号
    registryTime: number; // 入库时间，epoch milliseconds
    fileId: Id | null; // 标准原文文件编号
    fileName: null | string; // 标准原文文件名快照
    fileUrl: null | string; // 标准原文访问地址快照
    replaceDocId: Id | null; // 被本标准替代的旧标准编号
    summary: null | string; // 标准摘要/适用范围说明
    status: number; // 状态
    sort: number; // 显示顺序
    remark: null | string; // 备注
    createTime: number; // 创建时间，epoch milliseconds
  }

  /** 标准文件登记保存参数 */
  export interface DocSave {
    categoryId?: Id | null;
    versionNo?: null | string;
    abolishDate?: null | string;
    fileId?: Id | null;
    fileName?: null | string;
    fileUrl?: null | string;
    id?: Id;
    implementDate?: null | string;
    implementStatus: string;
    nameCn: string;
    nameEn?: null | string;
    publishDate?: null | string;
    publishOrg?: null | string;
    registryNo?: null | string;
    /** epoch milliseconds；DatePicker valueFormat="x" 返回数字字符串 */
    registryTime?: null | number | string;
    remark?: null | string;
    replaceDocId?: Id | null;
    sort: number;
    status: number;
    stdCode?: null | string;
    stdType: string;
    summary?: null | string;
  }

  /** 新登记入口必填信息，不改变历史检索页保存协议。 */
  export interface RegistrationSave extends DocSave {
    categoryId: Id;
    fileId: Id;
    stdCode: string;
  }

  /** 标准文件登记分页查询参数 */
  export interface DocPageParams extends PageParam {
    categoryId?: Id;
    implementStatus?: string;
    nameCn?: string;
    nameEn?: string;
    publishOrg?: string;
    registryNo?: string;
    registryTime?: [string, string];
    status?: number;
    stdCode?: string;
    stdType?: string;
  }

  export interface Category {
    id: Id;
    parentId?: Id | null;
    categoryName?: string;
    children?: Category[];
  }

  /** 替代标准候选 */
  export interface SimpleDoc {
    id: Id;
    implementStatus: string;
    nameCn: string;
    registryNo: null | string;
    stdCode: null | string;
    stdType?: string;
  }

  /** 标准文件版本快照 */
  export interface DocVersion {
    id: Id;
    objectType: string;
    objectId: Id;
    version: number;
    snapshot: null | string;
    changeSummary: null | string;
    creator: null | string;
    createTime: number;
  }
}

/** 查询标准类目，权限与标准登记查询一致。 */
export function getDocCategories() {
  return requestClient.get<StandardDocApi.Category[]>(
    '/governance/standard-doc/categories',
  );
}

/** 查询数据标准--标准文件登记分页 */
export function getDocPage(params: StandardDocApi.DocPageParams) {
  return requestClient.get<PageResult<StandardDocApi.Doc>>(
    '/governance/standard-doc/page',
    { params },
  );
}

/** 查询数据标准--标准文件登记详情 */
export function getDoc(id: StandardDocApi.Id) {
  return requestClient.get<StandardDocApi.Doc>(
    `/governance/standard-doc/get?id=${id}`,
  );
}

/** 获取标准原文的最新可访问 URL */
export function getDocPreviewUrl(id: StandardDocApi.Id) {
  return requestClient.get<string>('/governance/standard-doc/preview-url', {
    params: { id },
  });
}

/** 查询替代标准候选列表 */
export function getDocSimpleList(excludeId?: StandardDocApi.Id) {
  return requestClient.get<StandardDocApi.SimpleDoc[]>(
    '/governance/standard-doc/simple-list',
    { params: { excludeId } },
  );
}

/** 新增数据标准--标准文件登记 */
export function createDoc(data: StandardDocApi.DocSave) {
  return requestClient.post<StandardDocApi.Id>(
    '/governance/standard-doc/create',
    data,
  );
}

export function createDocRegistration(data: StandardDocApi.RegistrationSave) {
  return requestClient.post<StandardDocApi.Id>(
    '/governance/standard-doc/registration/create',
    data,
  );
}

export function updateDocRegistration(
  data: StandardDocApi.RegistrationSave & { id: StandardDocApi.Id },
) {
  return requestClient.put<boolean>(
    '/governance/standard-doc/registration/update',
    data,
  );
}

/** 修改数据标准--标准文件登记 */
export function updateDoc(
  data: StandardDocApi.DocSave & { id: StandardDocApi.Id },
) {
  return requestClient.put<boolean>('/governance/standard-doc/update', data);
}

/** 删除数据标准--标准文件登记 */
export function deleteDoc(id: StandardDocApi.Id) {
  return requestClient.delete<boolean>(
    `/governance/standard-doc/delete?id=${id}`,
  );
}

/** 批量删除数据标准--标准文件登记 */
export function deleteDocList(ids: StandardDocApi.Id[]) {
  return requestClient.delete<boolean>(
    `/governance/standard-doc/delete-list?ids=${ids.join(',')}`,
  );
}

/** 导出数据标准--标准文件登记 */
export function exportDoc(params: StandardDocApi.DocPageParams) {
  return requestClient.download('/governance/standard-doc/export-excel', {
    params,
  });
}

/** 生成标准文件版本快照 */
export function publishDocVersion(
  id: StandardDocApi.Id,
  changeSummary?: string,
) {
  return requestClient.put<number>(
    '/governance/standard-doc/publish-version',
    null,
    { params: { id, changeSummary } },
  );
}

/** 查询标准文件版本快照列表 */
export function getDocVersions(id: StandardDocApi.Id) {
  return requestClient.get<StandardDocApi.DocVersion[]>(
    '/governance/standard-doc/versions',
    { params: { id } },
  );
}
