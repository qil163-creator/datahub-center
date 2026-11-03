import type { DataforgeEtlApi } from '#/api/dataforge/etl';

import { defaultApiConfig } from '#/views/dataforge/dataapi/modules/api-config';

export function defaultRestApiNodeConfig(
  nodeName = 'API接口',
): DataforgeEtlApi.RestApiNodeConfig {
  return {
    apiConfig: defaultApiConfig({
      apiName: nodeName,
      dataType: 1,
      dataKeys: ['$.data.rows'],
      requestHeader: [{ name: 'Accept', value: '*/*' }],
    }),
    fetchSize: 500,
  };
}
