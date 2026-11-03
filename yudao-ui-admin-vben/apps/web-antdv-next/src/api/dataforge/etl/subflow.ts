import type { PageParam, PageResult } from '@vben/request';

import type { DataforgeEtlApi } from './index';

import { requestClient } from '#/api/request';

export interface SubflowField extends DataforgeEtlApi.FieldConstraints {
  name: string;
  type: string;
}
export interface SubflowPort {
  name: string;
  fields: SubflowField[];
}
export interface SubflowContract {
  pipelineId: DataforgeEtlApi.Id;
  versionId: DataforgeEtlApi.Id;
  contentHash: string;
  inputs: SubflowPort[];
  outputs: SubflowPort[];
  parameters: string[];
  parameterDefinitions?: DataforgeEtlApi.ParamItem[];
}
export interface SubflowPipeline {
  id: DataforgeEtlApi.Id;
  name: string;
  publishedRevision?: number;
  publishedVersionId?: DataforgeEtlApi.Id;
}
export interface SubflowPipelinePageRequest extends PageParam {
  name?: string;
}
export interface SubflowVersionPageRequest extends PageParam {
  pipelineId: DataforgeEtlApi.Id;
}
export function getSubflowPipelinePage(params: SubflowPipelinePageRequest) {
  return requestClient.get<PageResult<SubflowPipeline>>(
    '/dataforge/etl-pipeline/subflow/pipeline-page',
    { params },
  );
}
export function getSubflowVersionPage(params: SubflowVersionPageRequest) {
  return requestClient.get<PageResult<DataforgeEtlApi.Version>>(
    '/dataforge/etl-pipeline/subflow/version-page',
    { params },
  );
}
export function getSubflowContract(versionId: DataforgeEtlApi.Id) {
  return requestClient.get<SubflowContract>(
    '/dataforge/etl-pipeline/subflow-contract',
    { params: { versionId } },
  );
}
