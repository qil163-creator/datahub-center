import type { SupplyDataApiApi } from '#/api/supply/dataapi';

import { requestClient } from '#/api/request';

export namespace SupplyApiTestApi {
  export interface Pair {
    name: string;
    value: string;
  }
  export interface Api {
    id: number | string;
    categoryId?: number | string;
    name: string;
    method: string;
    path: string;
    version: string;
    authType: string;
    configType: string;
    resultFormat: string;
    status: number;
  }
  export interface Catalog {
    publicBaseUrl: string;
    categories: {
      id: number | string;
      name: string;
      parentId?: number | string;
    }[];
    apis: Api[];
  }
  export interface Detail extends Api {
    requestParams: SupplyDataApiApi.ParamItem[];
    publicUrl: string;
  }
  export interface SendRequest {
    mode: 'EXTERNAL' | 'REGISTERED';
    apiId?: number | string;
    url?: string;
    method: string;
    query: Pair[];
    headers: Pair[];
    body?: string;
    timeoutSeconds: number;
    followRedirects: boolean;
  }
  export interface Response {
    statusCode: number;
    statusText: string;
    headers: Pair[];
    body: string;
    // oxlint-disable-next-line unicorn/text-encoding-identifier-case -- 保持后端固定枚举。
    bodyEncoding: 'BASE64' | 'UTF8';
    bodyBytes: number;
    durationMs: number;
    truncated: boolean;
    url: string;
    redirectChain: { statusCode: number; url: string }[];
  }
}

export function getApiTestCatalog(signal?: AbortSignal) {
  return requestClient.get<SupplyApiTestApi.Catalog>(
    '/supply/api-test/catalog',
    { signal },
  );
}

export function getApiTestDetail(id: number | string, signal?: AbortSignal) {
  return requestClient.get<SupplyApiTestApi.Detail>('/supply/api-test/detail', {
    params: { id },
    signal,
  });
}

export function sendApiTest(
  data: SupplyApiTestApi.SendRequest,
  signal?: AbortSignal,
) {
  return requestClient.post<SupplyApiTestApi.Response>(
    '/supply/api-test/send',
    data,
    {
      signal,
      timeout: (data.timeoutSeconds + 5) * 1000,
    },
  );
}
