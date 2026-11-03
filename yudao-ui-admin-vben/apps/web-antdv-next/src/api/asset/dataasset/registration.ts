import type { PageParam, PageResult } from '@vben/request';

import type { DataAssetApi } from './index';

import { requestClient } from '#/api/request';

export namespace AssetRegistrationApi {
  export interface ContextOption {
    id: DataAssetApi.Id;
    name: string;
    code?: string;
  }
  export interface Subject extends ContextOption {
    parentId?: DataAssetApi.Id;
    dataDomainId: DataAssetApi.Id;
    abbreviation?: string;
  }
  export interface Entity extends ContextOption {
    subjectDomainId?: DataAssetApi.Id;
    entityType?: string;
    entitySubtype?: string;
  }
  export interface Layer extends ContextOption {
    tablePrefix?: string;
    defaultTableType?: string;
  }
  export interface Context {
    dataDomains: ContextOption[];
    selectedDataDomainId?: DataAssetApi.Id;
    selectionSource?: string;
    subjectDomains: Subject[];
    layers: Layer[];
    entities: Entity[];
    granularities: Entity[];
    namingRules: {
      daySuffix?: string;
      hourSuffix?: string;
      includeSubjectAbbreviation?: boolean;
      minuteSuffix?: string;
      prefix?: string;
      tableType: string;
    }[];
    timeEffects?: { label: string; value: string }[];
  }
  export interface ContextQuery {
    dataDomainId?: DataAssetApi.Id;
    tableType?: string;
    subjectDomainId?: DataAssetApi.Id;
  }
  export interface NamePreviewQuery extends ContextQuery {
    candidateId: DataAssetApi.Id;
    tableType: string;
    namingCase: string;
    entityId?: DataAssetApi.Id;
    granularityEntityIds?: DataAssetApi.Id[];
    timeEffect?: string;
  }
  export interface NamePreview {
    assetName: string;
    businessEnglishName: string;
    dataDomainId?: DataAssetApi.Id;
    ruleType?: string;
  }
  export interface Candidate extends DataAssetApi.MetadataTable {
    assetType?: number;
    registered?: boolean;
    name?: string;
    fileName?: string;
    fileType?: string;
    path?: string;
    method?: string;
    description?: string;
  }
  export interface CandidatePage extends PageParam {
    assetType: number;
    keyword?: string;
    onlyUnlinked?: boolean;
  }
  export interface Item {
    candidateId: DataAssetApi.Id;
    assetName: string;
    categoryId?: DataAssetApi.Id;
    subjectDomainId?: DataAssetApi.Id;
    description?: string;
    registrationConfig?: DataAssetApi.RegistrationConfig;
  }
}

export function getAssetRegistrationOptions() {
  return requestClient.get<DataAssetApi.Facets>(
    '/asset/data-asset/registration-options',
  );
}
export function getAssetRegistrationCandidates(
  params: AssetRegistrationApi.CandidatePage,
) {
  return requestClient.get<PageResult<AssetRegistrationApi.Candidate>>(
    '/asset/data-asset/registration-candidates',
    { params },
  );
}
export function registerGenericDataAssets(
  assetType: number,
  items: AssetRegistrationApi.Item[],
) {
  return requestClient.post<DataAssetApi.Id[]>(
    '/asset/data-asset/register-generic',
    { assetType, items },
  );
}

export function getAssetRegistrationContext(
  params: AssetRegistrationApi.ContextQuery = {},
) {
  return requestClient.get<AssetRegistrationApi.Context>(
    '/asset/data-asset/registration-context',
    { params },
  );
}
export function previewAssetRegistrationName(
  data: AssetRegistrationApi.NamePreviewQuery,
) {
  return requestClient.post<AssetRegistrationApi.NamePreview>(
    '/asset/data-asset/registration-name-preview',
    data,
  );
}
