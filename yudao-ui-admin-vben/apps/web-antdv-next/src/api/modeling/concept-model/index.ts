import type { PageResult } from '@vben/request';

import { requestClient } from '#/api/request';

export namespace ConceptModelApi {
  export type Id = number | string;
  export type Status = 'ACTIVE' | 'DRAFT';
  export type Cardinality = 'MANY_TO_MANY' | 'ONE_TO_MANY' | 'ONE_TO_ONE';
  export interface Attribute {
    key: string;
    name: string;
    code: string;
    description?: null | string;
    dataType?: 'BOOLEAN' | 'DATE' | 'DATETIME' | 'NUMBER' | 'STRING' | null;
    primary?: boolean | null;
  }
  export interface Entity {
    key: string;
    name: string;
    code: string;
    entityType?: 'ACTIVITY' | 'OBJECT';
    description?: null | string;
    attributes: Attribute[];
    posX?: null | number;
    posY?: null | number;
  }
  export interface Relation {
    key: string;
    name: string;
    sourceEntityKey: string;
    targetEntityKey: string;
    cardinality: Cardinality;
    description?: null | string;
  }
  export interface Design {
    entities: Entity[];
    relations: Relation[];
  }
  export interface SaveReq {
    name: string;
    code: string;
    dataDomainId: Id;
    subjectDomainId?: Id | null;
    description?: null | string;
    design: Design;
  }
  export interface Model extends Omit<SaveReq, 'design'> {
    id: Id;
    revision: Id;
    status: Status;
    dataDomainName?: string;
    subjectDomainName?: string;
    /** 列表只返回概要，详情包含设计。 */
    design?: Design | null;
    createTime?: number;
    updateTime?: number;
  }
  export interface PageReq {
    pageNo: number;
    pageSize: number;
    keyword?: string;
    status?: Status;
    dataDomainId?: Id;
    subjectDomainId?: Id;
  }
}

export function getConceptModelPage(params: ConceptModelApi.PageReq) {
  return requestClient.get<PageResult<ConceptModelApi.Model>>(
    '/modeling/concept-model/page',
    { params },
  );
}

export function getConceptModel(id: ConceptModelApi.Id) {
  return requestClient.get<ConceptModelApi.Model>(
    '/modeling/concept-model/get',
    { params: { id } },
  );
}

export function createConceptModel(data: ConceptModelApi.SaveReq) {
  return requestClient.post<ConceptModelApi.Id>(
    '/modeling/concept-model/create',
    data,
  );
}

export function updateConceptModel(
  data: ConceptModelApi.SaveReq & {
    expectedRevision: ConceptModelApi.Id;
    id: ConceptModelApi.Id;
  },
) {
  return requestClient.put<boolean>('/modeling/concept-model/update', data);
}

export function updateConceptModelStatus(
  id: ConceptModelApi.Id,
  expectedRevision: ConceptModelApi.Id,
  status: ConceptModelApi.Status,
) {
  return requestClient.put<boolean>('/modeling/concept-model/update-status', {
    id,
    expectedRevision,
    status,
  });
}

export function deleteConceptModel(
  id: ConceptModelApi.Id,
  expectedRevision: ConceptModelApi.Id,
) {
  return requestClient.delete<boolean>('/modeling/concept-model/delete', {
    params: { id, expectedRevision },
  });
}
