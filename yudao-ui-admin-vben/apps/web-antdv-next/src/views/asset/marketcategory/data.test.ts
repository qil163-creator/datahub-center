import { describe, expect, it, vi } from 'vitest';

import {
  useCategoryFormSchema,
  useGridColumns,
  useGridFormSchema,
} from './data';
vi.mock('@vben/hooks', () => ({
  getDictOptions: () => [
    { label: '启用', value: 0 },
    { label: '停用', value: 1 },
  ],
}));
vi.mock('#/adapter/form', async () => {
  const { z } = await import('@vben/common-ui');
  return { z };
});
describe('market category subject-domain layout schema', () => {
  it('shows only name and status filters and preserves status zero as a numeric option', () => {
    const schema = useGridFormSchema();
    expect(schema.map((field) => field.fieldName)).toEqual(['name', 'status']);
    const props = schema[1]!.componentProps as () => {
      options: { value: number }[];
    };
    expect(props().options.map((option) => option.value)).toEqual([0, 1]);
  });
  it('retains checkbox selection and numbering with a tree name, two-line description slot and readable actions', () => {
    const columns = useGridColumns()!;
    expect(columns[0]).toMatchObject({ type: 'checkbox' });
    expect(columns[1]).toMatchObject({ type: 'seq', title: '编号' });
    expect(columns.find((column) => column.field === 'name')).toMatchObject({
      treeNode: true,
      title: '集市类目名称',
    });
    expect(
      columns.find((column) => column.field === 'description'),
    ).toMatchObject({ showOverflow: false, slots: { default: 'description' } });
    expect(columns.at(-1)).toMatchObject({
      showOverflow: false,
      slots: { default: 'actions' },
    });
    expect(columns.map((column) => column.field).filter(Boolean)).toEqual([
      'name',
      'description',
      'status',
      'sort',
      'creatorName',
      'createTime',
    ]);
  });
  it('keeps the five real category fields with full-width description and a lossless parent selector', () => {
    const parents = [
      {
        id: 0,
        name: '顶级节点',
        children: [
          {
            id: '9223372036854775807',
            name: '父类目',
            parentId: 0,
            status: 0,
            sort: 0,
          },
        ],
      },
    ];
    const schema = useCategoryFormSchema(() => parents);
    expect(schema.map((field) => field.fieldName)).toEqual([
      'name',
      'parentId',
      'status',
      'sort',
      'description',
    ]);
    expect(
      schema.find((field) => field.fieldName === 'description')?.formItemClass,
    ).toBe('col-span-1 sm:col-span-2');
    const props = schema.find((field) => field.fieldName === 'parentId')!
      .componentProps as () => { treeData: unknown };
    expect(props().treeData).toEqual(parents);
  });
  it('enforces the real name and description bounds without unrelated subject-domain fields', () => {
    const schema = useCategoryFormSchema(() => []);
    const name = schema.find((field) => field.fieldName === 'name')!.rules as {
      safeParse: (value: unknown) => { success: boolean };
    };
    const description = schema.find(
      (field) => field.fieldName === 'description',
    )!.rules as { safeParse: (value: unknown) => { success: boolean } };
    expect(name.safeParse('  ').success).toBe(false);
    expect(name.safeParse('名'.repeat(50)).success).toBe(true);
    expect(name.safeParse('名'.repeat(51)).success).toBe(false);
    expect(description.safeParse('描'.repeat(256)).success).toBe(true);
    expect(description.safeParse('描'.repeat(257)).success).toBe(false);
    expect(description.safeParse(null).success).toBe(true);
  });
});
