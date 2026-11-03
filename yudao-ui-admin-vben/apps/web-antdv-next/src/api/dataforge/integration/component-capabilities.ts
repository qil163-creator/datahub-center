import type { IntegrationApi } from './index';

import { requestClient } from '#/api/request';

export type IntegrationComponentStatus = 'PARTIAL' | 'SUPPORTED' | 'UNAVAILABLE';

export interface IntegrationComponentCapability {
  componentCode: string;
  status: IntegrationComponentStatus;
  reason: string;
}

export interface IntegrationComponentCapabilities {
  engine: IntegrationApi.Engine;
  mode: IntegrationApi.Mode;
  components: IntegrationComponentCapability[];
}

export const getIntegrationComponentCapabilities = (params: {
  engine: IntegrationApi.Engine;
  mode: IntegrationApi.Mode;
}) =>
  requestClient.get<IntegrationComponentCapabilities>(
    '/dataforge/integration/component-capabilities',
    { params, silentError: true },
  );
