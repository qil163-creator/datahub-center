import { requestClient } from '#/api/request';

export interface PushCategory {
  id: number | string;
  parentId: number | string;
  name: string;
  sort: number;
  status: number;
  description?: null | string;
}

export function getPushCategoryTree() {
  return requestClient.get<PushCategory[]>('/dataforge/push-category/tree');
}
