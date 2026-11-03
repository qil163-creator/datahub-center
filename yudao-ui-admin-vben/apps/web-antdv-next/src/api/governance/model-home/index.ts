import { requestClient } from '#/api/request';

export namespace ModelHomeApi {
  export type Count = number | string;

  export type MetricKey =
    | 'conceptModel'
    | 'dataDomain'
    | 'indicatorModel'
    | 'logicalModel'
    | 'publishedModel'
    | 'subjectDomain';

  export interface Metric {
    key: MetricKey;
    label: string;
    count: Count | null;
    available: boolean;
    recentCount: Count | null;
    description: string;
  }

  export interface Distribution {
    key: string;
    name: string;
    code: null | string;
    count: Count;
  }

  export interface Activity {
    id: string;
    modelId: number | string;
    type: 'CREATE' | 'PUBLISH';
    title: string;
    occurredAt: number;
  }

  /** 计数遵循后端 Long 无损传输协议；时间为毫秒时间戳。 */
  export interface ModelOverview {
    dataDomainCount: number;
    businessDomainCount: number;
    modelCount: number;
    publishedCount: number;
    materializedCount: number;
    factCount: number;
    dimensionCount: number;
    metrics: Metric[];
    layerDistribution: Distribution[];
    materializedDistribution: Distribution[];
    recentActivities: Activity[];
    layerDistributionAvailable: boolean;
    materializedDistributionAvailable: boolean;
    recentActivitiesAvailable: boolean;
    generatedAt: number;
  }
}

/** 查询建模总览统计 */
export function getModelOverview() {
  return requestClient.get<ModelHomeApi.ModelOverview>(
    '/modeling/overview/get',
  );
}
