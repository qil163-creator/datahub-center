import type { DataforgeEtlApi } from './index';

import { requestClient } from '#/api/request';

export type EtlVersionCompareChangeType =
  | 'EDGE_ADDED'
  | 'EDGE_REMOVED'
  | 'ENVIRONMENT_CHANGED'
  | 'NODE_ADDED'
  | 'NODE_CONFIG_CHANGED'
  | 'NODE_METADATA_CHANGED'
  | 'NODE_REMOVED'
  | 'NODE_TOOL_CHANGED'
  | 'PARAMETERS_CHANGED'
  | 'QUALITY_TASK_CHANGED'
  | 'SCHEDULE_CHANGED'
  | 'SUBFLOW_VERSION_CHANGED';

export interface EtlVersionCompare {
  sourceVersionId: DataforgeEtlApi.Id;
  sourceRevision?: number;
  targetVersionId: DataforgeEtlApi.Id;
  targetRevision?: number;
  changes: {
    count: number;
    objectId: string;
    type: EtlVersionCompareChangeType;
  }[];
  changeCounts: Partial<Record<EtlVersionCompareChangeType, number>>;
}

export function compareEtlVersions(params: {
  sourceVersionId: DataforgeEtlApi.Id;
  targetVersionId: DataforgeEtlApi.Id;
}) {
  return requestClient.get<EtlVersionCompare>(
    '/dataforge/etl-pipeline/version/compare',
    { params },
  );
}
