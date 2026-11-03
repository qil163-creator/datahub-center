import type { PageParam, PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export interface StandardAssociation {
  id: number | string;
  assetName: string;
  description?: string;
  tableName: string;
  columnNames: string | string[];
  creatorName?: string;
  createTime?: number | string;
  updateTime?: number | string;
}
export async function getStandardAssociations(
  kind: 'codeset' | 'element',
  params: PageParam & { id: number | string },
): Promise<PageResult<StandardAssociation>> {
  const hasColumns = await requestClient.get<boolean>(
    '/governance/standard-association/has-columns',
    { params: { id: params.id, kind } },
  );
  if (!hasColumns) {
    return { list: [], total: 0 };
  }
  return requestClient.get<PageResult<StandardAssociation>>(
    '/asset/standard-association/page',
    { params: { ...params, kind } },
  );
}
