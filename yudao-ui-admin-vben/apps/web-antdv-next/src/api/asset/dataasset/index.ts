import type { PageParam, PageResult } from '@vben/request';

import type { FoundationCategoryDataAssetApi } from '../categorydataasset';
import type { AssetQualityTaskApi } from '../qualitytask';
import type { AssetTagApi } from '../tag';
import type { ImportedAssetApi } from './imported';

import { consumptionHeaders } from '#/api/asset/consumption';
import { requestClient } from '#/api/request';

export namespace DataAssetApi {
  export interface RegistrationConfig {
    dataDomainId?: Id;
    dataDomainName?: string;
    entityId?: Id;
    granularityEntityIds?: Id[];
    businessEnglishName?: string;
    businessEntity?: string;
    timeEffect?: string;
    statGranularity?: string;
    namingCase?: string;
    tableType?: string;
    layer?: string;
  }
  export type Id = number | string;

  export interface DataAsset {
    registrationConfig?: RegistrationConfig;
    id?: Id;
    assetCode?: string;
    catalogItemUrn?: string;
    assetName?: string;
    assetType?: number;
    categoryId?: Id;
    categoryName?: string;
    metaTableId?: Id;
    resourceUrn?: string;
    publishedSnapshotId?: number;
    publishedVersion?: string;
    datasourceId?: number;
    sourceSystemName?: string;
    sourceRecordId?: string;
    sourceName?: string;
    sourceType?: string;
    fileName?: string;
    fileType?: string;
    filePath?: string;
    mapDisplayScore?: string;
    dbName?: string;
    schemaName?: string;
    tableName?: string;
    tableType?: string;
    layer?: string;
    subjectDomainId?: Id;
    subjectDomainName?: string;
    rowCount?: number;
    fieldCount?: number;
    storageSize?: number;
    healthScore?: number;
    description?: string;
    status?: number;
    publishTime?: number | string;
    publisher?: string;
    techLeaderUserId?: Id;
    techLeaderName?: string;
    techLeaderPhone?: string;
    businessLeaderUserId?: Id;
    businessLeaderName?: string;
    businessLeaderPhone?: string;
    ownerDeptId?: Id;
    viewCount?: number;
    shareAttribute?: number;
    shareAttr?: number;
    shareCondition?: string;
    updateCycle?: string;
    businessPurpose?: string;
    contactName?: string;
    contactPhone?: string;
    remark?: string;
    creator?: string;
    creatorName?: string;
    updater?: string;
    updaterName?: string;
    createTime?: number | string;
    updateTime?: number | string;
    tags?: AssetTagApi.Tag[];
    qualityTaskCount?: number;
    datasourceName?: string;
    datasourceType?: string;
    datasourceHost?: string;
    currentMetadataVersion?: string;
    currentMetadataPublishStatus?: number;
    metadataDrifted?: boolean;
    shareDenyReason?: string;
    providerDeptId?: Id;
    sourceDeptId?: Id;
    dutyBasis?: string;
    updateRule?: string;
    catalogRevision?: Id;
    publishedCatalogVersionId?: Id;
    publicationNotice?: string;
    retirementRequested?: boolean;
    pendingGrantRevocations?: Id;
    stopSupplyReason?: string;
    stopSupplyTime?: number | string;
    techAvailable?: number;
    externalCode?: string;
    expectedRevision?: Id;
  }

  export interface TechLink {
    id?: Id;
    assetId?: Id;
    providerModule?: string;
    resourceUrn?: string;
    datasourceId?: Id;
    schemaName?: string;
    objectName?: string;
    metaTableId?: Id;
    snapshotId?: Id;
    primaryFlag?: number;
  }

  export interface CatalogVersion {
    id?: Id;
    assetId?: Id;
    versionNo?: number;
    contentHash?: string;
    contentJson?: string;
    techSnapshotId?: Id;
    status?: number;
    submitTime?: string;
    checkNote?: string;
    publishTime?: string;
  }

  export interface ReviewTicket {
    id?: Id;
    assetId?: Id;
    catalogVersionId?: Id;
    issueType?: number;
    evidence?: string;
    demandDeptId?: Id;
    handleDeptId?: Id;
    status?: number;
    conclusion?: string;
    createTime?: string;
  }

  export interface MetadataTable {
    id?: Id;
    resourceUrn?: string;
    dbId?: Id;
    dbName?: string;
    schemaName?: string;
    datasourceId?: number;
    dbType?: string;
    sourceSystemName?: string;
    tableName?: string;
    tableType?: string;
    sourceComment?: string;
    customComment?: string;
    businessName?: string;
    businessDefinition?: string;
    layer?: string;
    columnCount?: number;
    rowCount?: number;
    storageSize?: number;
    subjectDomainId?: Id;
    subjectDomainName?: string;
    publishedVersion?: string;
    publishedSnapshotId?: number;
    publishTime?: string;
    healthScore?: number;
    catalogLinked?: number;
  }

  export interface MetadataColumn {
    id?: Id;
    resourceUrn?: string;
    tableId?: Id;
    columnName?: string;
    ordinalPosition?: number;
    sourceComment?: string;
    customComment?: string;
    businessName?: string;
    dataType?: string;
    dataLength?: number;
    dataPrecision?: number;
    dataScale?: number;
    nullableFlag?: number;
    defaultValue?: string;
    pkFlag?: number;
    fkFlag?: number;
    uniqueFlag?: number;
    autoIncrementFlag?: number;
    businessDefinition?: string;
    measuringUnit?: string;
    standardElementId?: number;
    standardElementCode?: string;
    standardElementName?: string;
    revision?: number;
    safetyLevelId?: Id;
    sensitiveFlag?: number;
    objectStatus?: number;
    termId?: Id;
    termName?: string;
    remark?: string;
  }

  export interface ColumnUpdate {
    assetId: Id;
    id: Id;
    revision: number;
    customComment?: string;
    businessName?: string;
    businessDefinition?: string;
    measuringUnit?: string;
    standardElementId?: number;
    safetyLevelId?: Id;
    sensitiveFlag?: number;
    remark?: string;
  }

  export interface Detail {
    asset?: DataAsset;
    columns?: MetadataColumn[];
    qualityTasks?: AssetQualityTaskApi.QualityTask[];
    importedSnapshot?: ImportedAssetApi.Snapshot;
  }

  export interface Facet {
    key: string;
    label: string;
    parentKey?: string;
    count: number;
  }

  export interface Facets {
    total?: number;
    categories?: Facet[];
    subjectDomains?: Facet[];
    layers?: Facet[];
  }

  export interface RegisterItem {
    registrationConfig?: RegistrationConfig;
    metaTableId: Id;
    assetName?: string;
    categoryId?: Id;
    subjectDomainId?: Id;
    description?: string;
    remark?: string;
  }

  export interface PreviewFilter {
    column: string;
    operator: string;
    value?: string;
  }

  export interface PreviewColumn {
    name: string;
    label: string;
    typeName?: string;
  }

  export interface Preview {
    columns?: PreviewColumn[];
    rows?: Record<string, unknown>[];
    truncated?: boolean;
    total?: number;
    rowIds?: string[];
    rowRevisions?: number[];
    /** 被动态脱敏的列（原值不在 rows 中）；为空表示没有列被脱敏 */
    maskedColumns?: { categoryNames?: string[]; name: string; reason?: string }[];
  }
}

export const ASSET_TYPE_OPTIONS: {
  disabled?: boolean;
  label: string;
  value: number;
}[] = [
  { label: '数据库表', value: 1 },
  { label: '外部 API', value: 2 },
  { label: '非结构化数据', value: 3 },
];

export const ASSET_STATUS_OPTIONS = [
  { label: '未发布', value: 0, color: 'orange' },
  { label: '已发布', value: 1, color: 'green' },
  { label: '已下线', value: 2, color: 'default' },
];

export const SHARE_ATTR_OPTIONS = [
  { label: '待核验', value: 0, color: 'default' },
  { label: '无条件共享', value: 1, color: 'green' },
  { label: '有条件共享', value: 2, color: 'blue' },
  { label: '不予共享', value: 3, color: 'red' },
];

export const CATALOG_VERSION_STATUS_OPTIONS = [
  { label: '草稿', value: 0 },
  { label: '已提交', value: 1 },
  { label: '退回', value: 2 },
  { label: '审核通过', value: 3 },
  { label: '已发布', value: 4 },
];

export const TICKET_ISSUE_OPTIONS = [
  { label: '错误', value: 1 },
  { label: '缺失', value: 2 },
  { label: '口径不清', value: 3 },
  { label: '更新不及时', value: 4 },
  { label: '服务不可用', value: 5 },
];

export const TICKET_STATUS_OPTIONS = [
  { label: '已提交', value: 0 },
  { label: '已认领', value: 1 },
  { label: '整改中', value: 2 },
  { label: '已反馈', value: 3 },
  { label: '复核中', value: 4 },
  { label: '办结', value: 5 },
  { label: '退回', value: 6 },
];

export const LAYER_OPTIONS = ['ODS', 'DIM', 'DWD', 'DWS', 'ADS'].map(
  (value) => ({
    label: value,
    value,
  }),
);

export function getDataAssetPage(
  params: PageParam & {
    assetType?: number;
    categoryId?: DataAssetApi.Id;
    dataDomainId?: DataAssetApi.Id;
    datasourceId?: DataAssetApi.Id;
    keyword?: string;
    layer?: string;
    status?: number;
    subjectDomainId?: DataAssetApi.Id;
    tagId?: DataAssetApi.Id;
  },
) {
  return requestClient.get<PageResult<DataAssetApi.DataAsset>>(
    '/asset/data-asset/page',
    { params },
  );
}

export function getDataAssetFacets() {
  return requestClient.get<DataAssetApi.Facets>('/asset/data-asset/facets');
}

export function getAssetCandidatePage(
  params: PageParam & { keyword?: string; onlyUnlinked?: boolean },
) {
  return requestClient.get<PageResult<DataAssetApi.MetadataTable>>(
    '/asset/data-asset/candidate-page',
    { params },
  );
}

export function batchRegisterDataAsset(items: DataAssetApi.RegisterItem[]) {
  return requestClient.post<number[]>('/asset/data-asset/batch-register', {
    items,
  });
}

export function updateDataAsset(data: Partial<DataAssetApi.DataAsset>) {
  return requestClient.put('/asset/data-asset/update', data);
}

export function updateDataAssetColumn(data: DataAssetApi.ColumnUpdate) {
  return requestClient.put('/asset/data-asset/update-column', data);
}

/** 通过资产维护权限读取字段当前业务属性，冻结列表只作为字段身份来源。 */
export function getDataAssetColumn(
  assetId: DataAssetApi.Id,
  id: DataAssetApi.Id,
) {
  return requestClient.get<DataAssetApi.MetadataColumn>(
    '/asset/data-asset/column-get',
    { params: { assetId, id } },
  );
}

export function refreshDataAsset(id: DataAssetApi.Id) {
  return requestClient.put(`/asset/data-asset/refresh?id=${id}`);
}

export function publishDataAsset(id: DataAssetApi.Id) {
  return requestClient.put(`/asset/data-asset/publish?id=${id}`);
}

export function unpublishDataAsset(id: DataAssetApi.Id) {
  return requestClient.put(`/asset/data-asset/unpublish?id=${id}`);
}

export function deleteDataAsset(id: DataAssetApi.Id) {
  return requestClient.delete<boolean>(`/asset/data-asset/delete?id=${id}`);
}

export function getDataAssetDetail(id: DataAssetApi.Id) {
  return requestClient.get<DataAssetApi.Detail>(
    `/asset/data-asset/get?id=${id}`,
  );
}

export function previewDataAsset(
  id: DataAssetApi.Id,
  limit = 20,
  filters: DataAssetApi.PreviewFilter[] = [],
  consumptionKey?: string,
  pagination?: { pageNo: number; pageSize: number },
) {
  return requestClient.post<DataAssetApi.Preview>(
    '/asset/data-asset/preview',
    {
      id,
      limit,
      filters,
      ...pagination,
    },
    { headers: consumptionHeaders(consumptionKey) },
  );
}

export function createCatalogDraft(data: {
  assetName: string;
  categoryId?: DataAssetApi.Id;
  description?: string;
}) {
  return requestClient.post<DataAssetApi.Id>('/asset/data-asset/draft', data);
}

export function addAssetTechLink(data: {
  assetId: DataAssetApi.Id;
  metaTableId: DataAssetApi.Id;
  primaryFlag?: number;
}) {
  return requestClient.post<DataAssetApi.Id>(
    '/asset/data-asset/tech-link',
    data,
  );
}

export function getAssetTechLinks(assetId: DataAssetApi.Id) {
  return requestClient.get<DataAssetApi.TechLink[]>(
    '/asset/data-asset/tech-link/list',
    { params: { assetId } },
  );
}

export function submitCatalogVersion(
  assetId: DataAssetApi.Id,
  expectedRevision?: DataAssetApi.Id,
) {
  const query =
    expectedRevision === undefined
      ? ''
      : `&expectedRevision=${expectedRevision}`;
  return requestClient.post<DataAssetApi.Id>(
    `/asset/data-asset/catalog-version/submit?assetId=${assetId}${query}`,
  );
}

export function checkCatalogVersion(data: {
  checkNote?: string;
  id: DataAssetApi.Id;
  status: number;
}) {
  return requestClient.put('/asset/data-asset/catalog-version/check', data);
}

export function publishCatalogVersion(id: DataAssetApi.Id) {
  return requestClient.put(
    `/asset/data-asset/catalog-version/publish?id=${id}`,
  );
}

export function getCatalogVersions(assetId: DataAssetApi.Id) {
  return requestClient.get<DataAssetApi.CatalogVersion[]>(
    '/asset/data-asset/catalog-version/list',
    { params: { assetId } },
  );
}

export interface PublicationReadiness {
  assetId: DataAssetApi.Id;
  catalogRevision?: DataAssetApi.Id;
  missingItems: string[];
  openApplications: number | string;
  pendingRevocations: number | string;
  publishedEvidenceComplete: boolean;
  steps: string[];
  unclosedSources: number | string;
}
export function getPublicationReadiness(assetId: DataAssetApi.Id) {
  return requestClient.get<PublicationReadiness>(
    '/asset/data-asset/publication-readiness',
    { params: { assetId } },
  );
}

export function createReviewTicket(data: {
  assetId: DataAssetApi.Id;
  catalogVersionId?: DataAssetApi.Id;
  evidence?: string;
  issueType: number;
}) {
  return requestClient.post<number>('/asset/data-asset/ticket', data);
}

export function claimReviewTicket(id: DataAssetApi.Id) {
  return requestClient.put(`/asset/data-asset/ticket/claim?id=${id}`);
}

export function feedbackReviewTicket(id: DataAssetApi.Id, conclusion: string) {
  return requestClient.put(
    `/asset/data-asset/ticket/feedback?id=${id}&conclusion=${encodeURIComponent(conclusion)}`,
  );
}

export function reviewReviewTicket(
  id: DataAssetApi.Id,
  pass: boolean,
  conclusion?: string,
) {
  const extra = conclusion
    ? `&conclusion=${encodeURIComponent(conclusion)}`
    : '';
  return requestClient.put(
    `/asset/data-asset/ticket/review?id=${id}&pass=${pass}${extra}`,
  );
}

export function getReviewTicketPage(
  params: PageParam & {
    assetId?: DataAssetApi.Id;
    issueType?: number;
    status?: number;
  },
) {
  return requestClient.get<PageResult<DataAssetApi.ReviewTicket>>(
    '/asset/data-asset/ticket/page',
    { params },
  );
}

export function stopDataAssetSupply(data: {
  assetId: DataAssetApi.Id;
  reason: string;
}) {
  return requestClient.put<boolean>('/asset/data-asset/stop-supply', data);
}

/** 资产查询角色可读取的生产目录导航类目，维护操作仍使用独立类目权限。 */
export function getDataAssetNavigationCategories() {
  return requestClient.get<FoundationCategoryDataAssetApi.CategoryDataAsset[]>(
    '/asset/data-asset/navigation-categories',
  );
}
