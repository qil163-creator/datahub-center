import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { DataforgeDataFileApi } from '#/api/dataforge/datafile';

import { formatDate } from '@vben/utils';

export const FILE_TYPE_OPTIONS = [
  { label: '文件夹', value: 'DIRECTORY' },
  ...[
    'TXT',
    'CSV',
    'JSON',
    'XML',
    'SQL',
    'LOG',
    'XLS',
    'XLSX',
    'PDF',
    'DOC',
    'DOCX',
    'ZIP',
    'PNG',
    'JPG',
    'FILE',
  ].map((value) => ({ label: value === 'FILE' ? '无扩展名' : value, value })),
];
export const MAX_UPLOAD_BYTES = 500 * 1024 * 1024;
export const MAX_BATCH_FILES = 20;

/** 使用整数运算格式化字节数，避免后端 Long 字符串先被转换成不精确浮点数。 */
export function formatSize(size?: DataforgeDataFileApi.Id | null) {
  if (size === null || size === undefined || !/^\d+$/.test(String(size)))
    return '-';
  const bytes = BigInt(size);
  // oxlint-disable-next-line unicorn/prefer-bigint-literals -- 当前构建目标不支持 bigint 字面量，使用构造函数保留无损计算。
  const kilobyte = BigInt(1024);
  // oxlint-disable-next-line unicorn/prefer-bigint-literals -- 同上，避免构建器保留目标语法不支持的字面量。
  const hundred = BigInt(100);
  if (bytes < kilobyte) return `${bytes} B`;
  const units = ['KB', 'MB', 'GB', 'TB', 'PB', 'EB'];
  let divisor = kilobyte;
  let index = 0;
  while (bytes >= divisor * kilobyte && index < units.length - 1) {
    divisor *= kilobyte;
    index++;
  }
  // oxlint-disable-next-line unicorn/prefer-bigint-literals -- 同上。
  const hundredths = (bytes * hundred + divisor / BigInt(2)) / divisor;
  return `${hundredths / hundred}.${String(hundredths % hundred).padStart(2, '0')} ${units[index]}`;
}

export function validateFileName(name: string) {
  if (!name.trim()) return '请输入文件名';
  if (
    name === '.' ||
    name === '..' ||
    /[/\\]/.test(name) ||
    /%[\da-f]{2}/i.test(name) ||
    [...name].some(
      (character) =>
        character.codePointAt(0)! < 32 || character.codePointAt(0) === 127,
    )
  )
    return '文件名不能包含路径分隔符、百分号编码、控制字符或相对目录';
  if (name.length > 50) return '文件名不能超过 50 个字符';
  return '';
}

export function parentPath(path: string) {
  const position = path.lastIndexOf('/');
  return position > 0 ? path.slice(0, position) : '/';
}

export function useGridColumns(): VxeTableGridOptions<DataforgeDataFileApi.Entry>['columns'] {
  return [
    { type: 'checkbox', width: 45 },
    { type: 'seq', title: '编号', width: 60 },
    {
      field: 'name',
      title: '文件名',
      width: 260,
      align: 'left',
      slots: { default: 'name' },
    },
    {
      field: 'type',
      title: '文件类型',
      width: 120,
      align: 'left',
      slots: { default: 'type' },
    },
    { field: 'path', title: '所在路径', minWidth: 260, align: 'left' },
    {
      field: 'size',
      title: '文件大小',
      width: 100,
      align: 'left',
      formatter: ({ row }) => (row.directory ? '-' : formatSize(row.size)),
    },
    {
      field: 'updateTime',
      title: '更新时间',
      width: 170,
      align: 'left',
      sortable: true,
      formatter: ({ cellValue }) => formatDate(cellValue, 'YYYY-MM-DD HH:mm'),
    },
    {
      field: 'actions',
      title: '操作',
      width: 300,
      fixed: 'right',
      showOverflow: false,
      slots: { default: 'actions' },
    },
  ];
}

export interface FileTreeNode {
  key: string;
  title: string;
  isLeaf: boolean;
  children?: FileTreeNode[];
}

export function filterDirectoryTree(
  nodes: FileTreeNode[],
  keyword: string,
): FileTreeNode[] {
  const query = keyword.trim().toLocaleLowerCase();
  if (!query) return nodes;
  return nodes.flatMap((node) => {
    const children = filterDirectoryTree(node.children ?? [], keyword);
    return node.title.toLocaleLowerCase().includes(query) || children.length > 0
      ? [{ ...node, children }]
      : [];
  });
}
