import type { BusinessEntityApi } from '#/api/modeling/business-entity';

type EntityTreeInput = Omit<
  BusinessEntityApi.Entity,
  'children' | 'inheritEntityId'
> & {
  children?: null | readonly EntityTreeInput[];
  inheritEntityId?: BusinessEntityApi.Id | null;
};

export interface EntityTreeRow extends Omit<
  BusinessEntityApi.Entity,
  'children' | 'id' | 'inheritEntityId'
> {
  id: string;
  inheritEntityId?: string;
  children?: EntityTreeRow[];
}

export function normalizeEntityTree(
  rows: readonly EntityTreeInput[],
): EntityTreeRow[] {
  return rows.map(({ children, inheritEntityId, ...row }) => ({
    ...row,
    id: String(row.id),
    inheritEntityId:
      inheritEntityId === null || inheritEntityId === undefined
        ? undefined
        : String(inheritEntityId),
    ...(children?.length ? { children: normalizeEntityTree(children) } : {}),
  }));
}
