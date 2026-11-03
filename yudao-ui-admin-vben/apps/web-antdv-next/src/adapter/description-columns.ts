interface DescriptionColumn {
  cellRender?: unknown;
  children?: (DescriptionColumn | undefined)[];
  editRender?: unknown;
  field?: string;
  formatter?: unknown;
  params?: { descriptionEllipsis?: boolean };
  slots?: unknown;
  title?: unknown;
}

const descriptionFields = new Set(['desc', 'description', 'remark', 'remarks']);

/** 仅为普通文案展示列补两行省略；编辑器、自定义展示和显式退出均保留原契约。 */
export function withDescriptionEllipsis<
  T extends (DescriptionColumn | undefined)[] | undefined,
>(columns: T): T {
  return columns?.map((column) => {
    if (!column) return column;
    const next = column.children
      ? { ...column, children: withDescriptionEllipsis(column.children) }
      : column;
    if (
      (!descriptionFields.has(column.field?.split('.').at(-1) ?? '') &&
        column.title !== '描述' &&
        column.title !== '备注') ||
      column.params?.descriptionEllipsis === false ||
      column.editRender ||
      column.slots ||
      column.formatter ||
      column.cellRender
    )
      return next;
    return {
      ...next,
      showOverflow: false,
      cellRender: {
        name: 'CellTextEllipsis',
        props: { line: 2, center: true },
      },
    };
  }) as T;
}
