import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace GovernanceStandardElementApi {
  /** 数据标准--标准数据元信息 */
  export interface StandardElement {
    id: number; // 主键
    code: string; // 数据元编码
    name: string; // 中文名称
    engName: string; // 英文名称
    categoryId: number | string; // 关联 dh_foundation_category_datameta.id
    documentId?: number | string; // 标准登记 dh_governance_standard_doc.id，后端 Long 保持无损
    categoryName?: string;
    creatorName?: string;
    standardType?: string;
    contactNumber?: string;
    revision?: number | string;
    columnType: string; // 字段类型，如 VARCHAR、BIGINT
    dataLength?: number; // 数据长度
    dataPrecision?: number; // 数据精度（数值总位数）
    dataScale?: number; // 小数位数
    dataFormat?: string; // 数据格式，如 yyyy-MM-dd
    measureUnit?: string; // 计量单位
    valueRange?: string; // 值域范围说明
    nullableFlag?: number; // 是否可为空：0-不可空 1-可空
    defaultValue?: string; // 默认值
    lifecycleStatus?: string; // 生命周期状态：DRAFT/PUBLISHED/DEPRECATED
    codeSetId?: number | string; // 关联标准码表 dh_governance_standard_code_set.id
    personCharge?: number | string; // 负责人id
    personChargeName?: string;
    description: string; // 描述
    sort?: number; // 排序
    status?: number; // 状态
    remark: string; // 备注
    createTime?: number | string; // 创建时间
    creator?: string; // 创建者
    updateTime?: number | string; // 更新时间
    updater?: string; // 更新者
  }

  /** 标准数据元统计概览 */
  export interface Statistics {
    totalCount: number; // 数据元总数
    draftCount: number; // 草稿数量
    publishedCount: number; // 已发布数量
    deprecatedCount: number; // 已废止数量
    mappedElementCount: number; // 已落标数据元数量（元数据字段引用或建模字段落标）
    modelMappedElementCount: number; // 设计态（建模字段）落标数据元数量
    mappingRate: number; // 落标率（百分比）
    codeSetBoundCount: number; // 已绑定码表的数据元数量
  }

  /** 标准数据元落标字段信息 */
  export interface MappedColumn {
    mappingId?: number | string; // 落标映射编号，后端 Long 可能序列化为字符串
    mappingStatus?: string; // CONFIRMED/PROPOSED/REVOKED/STALE
    columnId: number; // 元数据字段编号
    columnName: string; // 字段名
    businessName?: string; // 业务名称
    dataType?: string; // 数据类型
    dataLength?: number; // 数据长度
    nullableFlag?: number; // 是否可空
    tableId?: number; // 所属表编号
    tableName?: string; // 所属表名
    tableBusinessName?: string; // 所属表业务名称
    layer?: string; // 数仓分层
    complianceResult?: string; // 贯标检查结果：PASS/FAIL/SKIP
    complianceIssues?: string[]; // 贯标不符合项说明
  }

  /** 标准数据元导入结果 */
  export interface ImportResult {
    createNames: string[]; // 创建成功的英文名
    updateNames: string[]; // 更新成功的英文名
    failureReasons: Record<string, string>; // 失败原因
  }

}

/** 查询数据标准--标准数据元分页 */
export function getStandardElementPage(
  params: PageParam & {
    categoryId?: number | string;
    categoryIds?: string;
    documentId?: number | string;
    engName?: string;
    name?: string;
    sortField?: 'createTime' | 'id';
    sortOrder?: 'asc' | 'desc';
  },
) {
  return requestClient.get<
    PageResult<GovernanceStandardElementApi.StandardElement>
  >('/governance/standard-element/page', { params });
}

/** 查询数据标准--标准数据元详情 */
export function getStandardElement(id: number) {
  return requestClient.get<GovernanceStandardElementApi.StandardElement>(
    `/governance/standard-element/get?id=${id}`,
  );
}

/** 新增数据标准--标准数据元 */
export function createStandardElement(
  data: GovernanceStandardElementApi.StandardElement,
) {
  return requestClient.post('/governance/standard-element/create', data);
}

/** 修改数据标准--标准数据元 */
export function updateStandardElement(
  data: GovernanceStandardElementApi.StandardElement,
) {
  return requestClient.put('/governance/standard-element/update', data);
}

/** 独立状态变更，使用当前详情 revision 防止并发覆盖。 */
export function updateStandardElementStatus(
  id: number,
  status: number,
  revision: number | string,
) {
  return requestClient.put(
    '/governance/standard-element/update-status',
    undefined,
    { params: { id, status, revision } },
  );
}

/** 绑定数据标准--标准数据元的标准码表 */
export function bindStandardElementCodeSet(
  id: number | string,
  codeSetId: number | string,
) {
  return requestClient.put<boolean>(
    '/governance/standard-element/bind-code-set',
    undefined,
    { params: { id, codeSetId } },
  );
}

/** 发布数据标准--标准数据元（草稿 → 已发布） */
export function publishStandardElement(id: number) {
  return requestClient.put(`/governance/standard-element/publish?id=${id}`);
}

/** 撤销落标映射（同时解除元数据字段绑定） */
export function revokeStandardElementMapping(mappingId: number | string) {
  return requestClient.put(
    `/governance/standard-element/revoke-mapping?mappingId=${mappingId}`,
  );
}

/** 查询数据元的设计态落标（建模模块绑定该标准的模型字段） */
export function getStandardElementModelFields(id: number | string) {
  return requestClient.get<
    {
      cnName?: string;
      createTime?: number | string;
      creator?: string;
      dataDomainName?: string;
      dataLength?: number;
      dataType?: string;
      description?: string;
      enabled?: number;
      fieldName: string;
      fieldUid?: string;
      mappingStatus?: string;
      modelId: number | string;
      modelName?: string;
      modelStatus?: string;
      sourceInfo?: string;
      subjectDomainName?: string;
      tableName?: string;
      tableType?: string;
    }[]
  >(`/governance/standard-element/model-fields?id=${id}`);
}

/** 生成落标字段的码值内容稽核 SQL */
export function getStandardElementEnumCheckSqls(id: number) {
  return requestClient.get<
    {
      allowedValues: string[];
      checkSql: string;
      codeSetName?: string;
      columnId: number;
      columnName: string;
      tableName: string;
    }[]
  >(`/governance/standard-element/enum-check-sql?id=${id}`);
}

/** 标准数据元影响分析 */
export function getStandardElementImpact(id: number) {
  return requestClient.get<{
    directTables: {
      mappedColumnCount: number;
      tableBusinessName?: string;
      tableId: number;
      tableName: string;
    }[];
    mappedColumnCount: number;
  }>(`/governance/standard-element/impact?id=${id}`);
}

/** 废止数据标准--标准数据元（已发布 → 已废止） */
export function deprecateStandardElement(id: number) {
  return requestClient.put(`/governance/standard-element/deprecate?id=${id}`);
}

/** 查询数据标准--标准数据元的落标字段列表 */
export function getStandardElementMappedColumns(id: number) {
  return requestClient.get<GovernanceStandardElementApi.MappedColumn[]>(
    `/governance/standard-element/mapped-columns?id=${id}`,
  );
}

/** 查询数据标准--标准数据元统计概览 */
export function getStandardElementStatistics() {
  return requestClient.get<GovernanceStandardElementApi.Statistics>(
    '/governance/standard-element/statistics',
  );
}

/** 下载数据标准--标准数据元导入模板 */
export function downloadStandardElementImportTemplate() {
  return requestClient.download(
    '/governance/standard-element/get-import-template',
  );
}

/** 导入数据标准--标准数据元 */
export function importStandardElement(file: File, updateSupport: boolean) {
  return requestClient.upload<GovernanceStandardElementApi.ImportResult>(
    `/governance/standard-element/import?updateSupport=${updateSupport}`,
    { file },
  );
}

/** 删除数据标准--标准数据元 */
export function deleteStandardElement(id: number) {
  return requestClient.delete(`/governance/standard-element/delete?id=${id}`);
}

/** 批量删除数据标准--标准数据元 */
export function deleteStandardElementList(ids: number[]) {
  return requestClient.delete(
    `/governance/standard-element/delete-list?ids=${ids.join(',')}`,
  );
}

/** 导出数据标准--标准数据元 */
export function exportStandardElement(params: any) {
  return requestClient.download('/governance/standard-element/export-excel', {
    params,
  });
}

export function getStandardElementCategories() {
  return requestClient.get<
    {
      categoryName?: string;
      id: number | string;
      parentId?: number | string;
      sort?: number;
    }[]
  >('/governance/standard-element/category-list');
}
