import { requestClient } from '#/api/request';

export namespace ProjectCategoryApi {
  export type Id = number | string;
  export type Count = number | string;
  export type Kind = 'development' | 'integration' | 'masterPush';

  export interface Category {
    id: Id;
    parentId: Id;
    name: string;
    description?: null | string;
    status: number;
    sort: number;
    taskCount: Count | null;
    childCount: Count;
    creator?: string;
    creatorName?: string;
    createTime?: number;
    updateTime?: number;
  }

  export interface Save {
    kind: Kind;
    id?: Id;
    parentId: Id;
    name: string;
    description: string;
    status: number;
    sort: number;
  }
}

const base = '/dataforge/project-category';
const masterPushBase = '/governance/master-push-category';

function independentBase(kind: ProjectCategoryApi.Kind) {
  if (kind === 'masterPush') return masterPushBase;
  return undefined;
}

export function getProjectCategoryList(kind: ProjectCategoryApi.Kind) {
  const categoryBase = independentBase(kind);
  if (categoryBase) {
    return requestClient.get<ProjectCategoryApi.Category[]>(`${categoryBase}/list`);
  }
  return requestClient.get<ProjectCategoryApi.Category[]>(`${base}/list`, {
    params: { kind },
  });
}

export function getProjectCategory(
  kind: ProjectCategoryApi.Kind,
  id: ProjectCategoryApi.Id,
) {
  const categoryBase = independentBase(kind);
  if (categoryBase) {
    return requestClient.get<ProjectCategoryApi.Category>(`${categoryBase}/get`, {
      params: { id },
    });
  }
  return requestClient.get<ProjectCategoryApi.Category>(`${base}/get`, {
    params: { kind, id },
  });
}

export function createProjectCategory(data: ProjectCategoryApi.Save) {
  const categoryBase = independentBase(data.kind);
  if (categoryBase) {
    const { kind: _kind, ...body } = data;
    return requestClient.post<ProjectCategoryApi.Id>(
      `${categoryBase}/create`,
      body,
    );
  }
  return requestClient.post<ProjectCategoryApi.Id>(`${base}/create`, data);
}

export function updateProjectCategory(data: ProjectCategoryApi.Save) {
  const categoryBase = independentBase(data.kind);
  if (categoryBase) {
    const { kind: _kind, ...body } = data;
    return requestClient.put<boolean>(`${categoryBase}/update`, body);
  }
  return requestClient.put<boolean>(`${base}/update`, data);
}

export function updateProjectCategoryStatus(
  kind: ProjectCategoryApi.Kind,
  id: ProjectCategoryApi.Id,
  status: number,
) {
  const categoryBase = independentBase(kind);
  if (categoryBase) {
    return requestClient.put<boolean>(`${categoryBase}/update-status`, {
      id,
      status,
    });
  }
  return requestClient.put<boolean>(`${base}/update-status`, {
    kind,
    id,
    status,
  });
}

export function deleteProjectCategory(
  kind: ProjectCategoryApi.Kind,
  id: ProjectCategoryApi.Id,
) {
  const categoryBase = independentBase(kind);
  if (categoryBase) {
    return requestClient.delete<boolean>(`${categoryBase}/delete`, {
      params: { id },
    });
  }
  return requestClient.delete<boolean>(`${base}/delete`, {
    params: { kind, id },
  });
}

export function deleteProjectCategoryList(
  kind: ProjectCategoryApi.Kind,
  ids: ProjectCategoryApi.Id[],
) {
  const categoryBase = independentBase(kind);
  if (categoryBase) {
    return requestClient.delete<boolean>(`${categoryBase}/delete-list`, {
      params: { ids },
    });
  }
  return requestClient.delete<boolean>(`${base}/delete-list`, {
    params: { kind, ids },
  });
}
