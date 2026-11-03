import { requestClient } from '#/api/request';

export interface MasterPushCategory {
  id: number | string;
  parentId: number | string;
  name: string;
  sort: number;
  status: number;
}

export function getMasterPushCategoryTree() {
  return requestClient.get<MasterPushCategory[]>(
    '/governance/master-push-category/tree',
  );
}
