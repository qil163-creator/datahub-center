import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace DataforgeDataFileApi {
  export type Id = number | string;
  export interface Connection {
    id: Id;
    name: string;
    type: 'FTP' | 'HDFS';
    address: string;
    status: number;
    rootPath: string;
    supportsOverwrite: boolean;
  }
  export interface Entry {
    name: string;
    path: string;
    directory: boolean;
    type: string;
    size: Id | null;
    updateTime: null | number;
  }
  export interface Directory {
    name: string;
    path: string;
    directory: true;
  }
  export interface Summary {
    connectionStatus: 'CONNECTED' | 'FAILED';
    fileCount: Id | null;
    todayUploadCount: Id | null;
    directoryCount: Id | null;
    storageBytes: Id | null;
    complete: boolean;
    message?: string;
  }
  export interface Location {
    connectionId: Id;
    path: string;
  }
  export interface PageReq extends Location, PageParam {
    name?: string;
    type?: string;
    order?: 'asc' | 'desc';
  }
  export interface NameReq extends Location {
    name: string;
  }
  export interface PathsReq {
    connectionId: Id;
    paths: string[];
  }
  export interface DeleteResult {
    succeededPaths: string[];
    failures: { message: string; path: string }[];
  }
  export interface Preview {
    name: string;
    content: string;
    truncated: boolean;
    type: 'IMAGE' | 'PDF' | 'TEXT' | 'UNSUPPORTED';
  }
}

const base = '/dataforge/file-resource';

export function getFileConnections() {
  return requestClient.get<DataforgeDataFileApi.Connection[]>(
    `${base}/connections`,
  );
}
export function getFileSummary(connectionId: DataforgeDataFileApi.Id) {
  return requestClient.get<DataforgeDataFileApi.Summary>(`${base}/summary`, {
    params: { connectionId },
    timeout: 60_000,
  });
}
export function getFileDirectories(params: DataforgeDataFileApi.Location) {
  return requestClient.get<DataforgeDataFileApi.Directory[]>(`${base}/tree`, {
    params,
  });
}
export function getDataFilePage(params: DataforgeDataFileApi.PageReq) {
  return requestClient.get<PageResult<DataforgeDataFileApi.Entry>>(
    `${base}/page`,
    { params },
  );
}
export function uploadDataFile(
  file: File,
  location: DataforgeDataFileApi.Location,
  overwrite = false,
) {
  const form = new FormData();
  form.append('connectionId', String(location.connectionId));
  form.append('path', location.path);
  form.append('file', file);
  form.append('overwrite', String(overwrite));
  return requestClient.post<boolean>(`${base}/upload`, form, {
    headers: { 'Content-Type': 'multipart/form-data' },
    timeout: 300_000,
  });
}
export function downloadDataFile(params: DataforgeDataFileApi.Location) {
  return requestClient.download<Blob>(`${base}/download`, {
    params,
    timeout: 300_000,
  });
}
export function downloadDataFiles(data: DataforgeDataFileApi.PathsReq) {
  return requestClient.download<Blob>(`${base}/download-batch`, {
    method: 'POST',
    data,
    timeout: 300_000,
  });
}
export function previewDataFile(params: DataforgeDataFileApi.Location) {
  return requestClient.get<DataforgeDataFileApi.Preview>(`${base}/preview`, {
    params,
  });
}
export function renameDataFile(data: DataforgeDataFileApi.NameReq) {
  return requestClient.put<boolean>(`${base}/rename`, data);
}
export function previewDataFileContent(params: DataforgeDataFileApi.Location) {
  return requestClient.download<Blob>(`${base}/preview-content`, {
    params,
    timeout: 60_000,
  });
}
export function createFileDirectory(data: DataforgeDataFileApi.NameReq) {
  return requestClient.post<boolean>(`${base}/mkdir`, data);
}
export function deleteDataFiles(data: DataforgeDataFileApi.PathsReq) {
  return requestClient.post<DataforgeDataFileApi.DeleteResult>(
    `${base}/delete`,
    data,
    { timeout: 60_000 },
  );
}
