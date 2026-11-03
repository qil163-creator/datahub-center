import type { DataforgeDataApiApi } from '#/api/dataforge/dataapi';
import type { IntegrationApi } from '#/api/dataforge/integration';

import { requestClient } from '#/api/request';

export interface ServiceMetadataRequest {
  id: IntegrationApi.Id;
  revision: number;
  resourceBindingId: string;
  apiUrl: string;
}

export interface WebServiceOperation {
  name: string;
  targetNamespace: string;
  parameters: { dataType?: string; name: string; required?: boolean }[];
  partial: boolean;
}

export interface WebServicePort {
  name: string;
  endpoint: string;
  apiMethod: 'httpGet' | 'httpPost' | 'soap11' | 'soap12';
  operations: WebServiceOperation[];
}

export interface WebServiceMetadata {
  services: { name: string; ports: WebServicePort[] }[];
  warnings: string[];
}

export function getIntegrationWebServiceMetadata(data: ServiceMetadataRequest) {
  return requestClient.post<WebServiceMetadata>(
    '/dataforge/integration/webservice-metadata',
    data,
    { silentError: true },
  );
}

export function getIntegrationHttpMetadata(data: {
  apiConfig: DataforgeDataApiApi.DataApi;
  id: IntegrationApi.Id;
  resourceBindingId: string;
  revision: number;
}) {
  return requestClient.post<{
    fields: IntegrationApi.SchemaField[];
    partial: boolean;
    warnings: string[];
  }>('/dataforge/integration/api-metadata', data, { silentError: true });
}
