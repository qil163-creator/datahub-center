import type { VxeTableGridOptions } from '@vben/plugins/vxe-table';

import { describe, expect, it } from 'vitest';

import { withDescriptionEllipsis } from './description-columns';

type Row = Record<string, unknown>;
type Columns = NonNullable<VxeTableGridOptions<Row>['columns']>;

describe('列表描述列适配', () => {
  it('普通描述与备注列统一两行，其他文案和 schema comment 保持原样', () => {
    const columns: Columns = [
      'description',
      'remark',
      'remarks',
      'desc',
      'name',
      'comment',
    ].map((field) => ({ field }));
    const result = withDescriptionEllipsis(columns)!;
    expect(
      result
        .slice(0, 4)
        .every((column) => column.cellRender?.name === 'CellTextEllipsis'),
    ).toBe(true);
    expect(result[0]?.cellRender?.props).toEqual({ line: 2, center: true });
    expect(result[0]?.showOverflow).toBe(false);
    expect(result[4]).toBe(columns[4]);
    expect(result[5]).toBe(columns[5]);
    expect(columns.every((column) => column.cellRender === undefined)).toBe(
      true,
    );
  });

  it('保留编辑、自定义插槽、格式化、已有 renderer 与显式退出', () => {
    const columns: Columns = [
      { field: 'description', editRender: { name: 'input' } },
      { field: 'remark', slots: { default: 'remark' } },
      { field: 'remarks', formatter: ({ cellValue }) => String(cellValue) },
      { field: 'description', cellRender: { name: 'OwnRenderer' } },
      { field: 'remark', params: { descriptionEllipsis: false } },
    ];
    expect(withDescriptionEllipsis(columns)).toEqual(columns);
  });

  it('精确识别描述备注表头，不将注释或代码当作普通描述', () => {
    const columns: Columns = [
      { field: 'comment', title: '描述' },
      { field: 'note', title: '备注' },
      { field: 'comment', title: '注释' },
      { field: 'sql', title: 'SQL代码' },
      { field: 'note', title: '备注', slots: { default: 'note' } },
    ];
    const result = withDescriptionEllipsis(columns);
    expect(result[0]?.cellRender?.name).toBe('CellTextEllipsis');
    expect(result[1]?.cellRender?.name).toBe('CellTextEllipsis');
    expect(result.slice(2)).toEqual(columns.slice(2));
  });

  it('递归分组列，保留组及其余配置且不修改原定义', () => {
    const columns: Columns = [
      {
        title: '扩展信息',
        children: [
          { field: 'extra.description', minWidth: 240 },
          { field: 'name' },
        ],
      },
    ];
    const result = withDescriptionEllipsis(columns)!;
    expect(result[0]?.title).toBe('扩展信息');
    expect(result[0]?.children?.[0]).toMatchObject({
      field: 'extra.description',
      minWidth: 240,
      cellRender: { name: 'CellTextEllipsis' },
    });
    expect(columns[0]?.children?.[0]?.cellRender).toBeUndefined();
  });

  it('无列定义时保持 undefined', () => {
    expect(withDescriptionEllipsis(undefined)).toBeUndefined();
  });

  it('保留动态更新中的可选列定义及原回调类型', () => {
    const formatter = ({ row }: { row: Row }) => String(row.name);
    const columns = [
      undefined,
      { field: 'remark', title: '备注' },
      { field: 'name', formatter },
    ];
    const result = withDescriptionEllipsis(columns);
    expect(result[0]).toBeUndefined();
    expect(result[1]).toMatchObject({
      field: 'remark',
      cellRender: { name: 'CellTextEllipsis' },
    });
    expect(result[2]?.formatter).toBe(formatter);
    expect(columns[1]).toEqual({ field: 'remark', title: '备注' });
  });
});
