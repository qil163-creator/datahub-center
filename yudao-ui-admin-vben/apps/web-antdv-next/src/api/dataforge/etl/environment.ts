import type { DataforgeEtlApi } from './index';

import { requestClient } from '#/api/request';

export interface EtlEnvironment {
  id: DataforgeEtlApi.Id;
  revision: number;
  code: string;
  name: string;
  enabled: boolean;
}
export interface EtlEnvironmentBinding {
  id: DataforgeEtlApi.Id;
  revision: number;
  environmentId: DataforgeEtlApi.Id;
  logicalKey: string;
  databaseId: DataforgeEtlApi.Id;
  schemaName: string;
}
type Save<T> = Omit<T, 'id' | 'revision'> &
  Partial<Pick<EtlEnvironment, 'id' | 'revision'>>;
const base = '/dataforge/etl/environment';
export const getEtlEnvironments = () =>
  requestClient.get<EtlEnvironment[]>(`${base}/list`);
export const saveEtlEnvironment = (data: Save<EtlEnvironment>) =>
  requestClient.post<DataforgeEtlApi.Id>(`${base}/save`, data);
export const deleteEtlEnvironment = (
  id: DataforgeEtlApi.Id,
  revision: number,
) =>
  requestClient.delete<boolean>(`${base}/delete`, { params: { id, revision } });
export const getEtlEnvironmentBindings = (environmentId: DataforgeEtlApi.Id) =>
  requestClient.get<EtlEnvironmentBinding[]>(`${base}/binding/list`, {
    params: { environmentId },
  });
export const saveEtlEnvironmentBinding = (data: Save<EtlEnvironmentBinding>) =>
  requestClient.post<DataforgeEtlApi.Id>(`${base}/binding/save`, data);
export const deleteEtlEnvironmentBinding = (
  id: DataforgeEtlApi.Id,
  revision: number,
) =>
  requestClient.delete<boolean>(`${base}/binding/delete`, {
    params: { id, revision },
  });
