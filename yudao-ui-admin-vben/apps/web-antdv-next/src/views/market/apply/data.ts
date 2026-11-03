import type { DepartmentAssetsApi } from '#/api/asset/market/department-assets';

import { marketCheckTime } from '../check/data';

export const DEPARTMENT_ASSET_FONT =
  "'Helvetica Neue',Helvetica,'PingFang SC','Hiragino Sans GB','Microsoft YaHei',Arial,sans-serif";
export const DEPARTMENT_ASSET_PERMISSION = 'asset:market:query';
export const DEPARTMENT_ASSET_FIELD_WIDTHS: Record<string, number> = {
  id: 60,
  businessName: 185,
  columnName: 180,
  businessDefinition: 240,
  dataType: 140,
  dataLength: 80,
  dataScale: 80,
  nullableFlag: 80,
  pkFlag: 100,
  safetyLevelName: 100,
  termName: 200,
  codeTableName: 200,
  standardElementName: 200,
  creatorName: 120,
  createTime: 150,
};
export const DEPARTMENT_ASSET_DETAIL_ROUTE = 'MarketApplyAssetDetail';
export const DEPARTMENT_ASSET_DELIVERIES = [
  { value: 1, label: '数据库表' },
  { value: 2, label: 'API接口' },
  { value: 3, label: '文件' },
];
export const DEPARTMENT_ASSET_COLUMNS = [
  {
    title: '编号',
    key: 'applyId',
    width: 55,
    sorter: true,
    align: 'center' as const,
  },
  { title: '数据集市名称', key: 'marketName', width: 200 },
  { title: '集市类目', key: 'marketCategoryName', width: 150 },
  { title: '交付形态', key: 'deliveryTypes', width: 260 },
  {
    title: '申请时间',
    key: 'applyTime',
    width: 170,
    sorter: true,
    align: 'center' as const,
  },
  { title: '有效期', key: 'period', width: 220, align: 'center' as const },
  { title: '状态', key: 'status', minWidth: 120, align: 'center' as const },
  {
    title: '数据更新周期',
    key: 'frequency',
    width: 150,
    align: 'center' as const,
  },
  {
    title: '操作',
    key: 'actions',
    width: 180,
    align: 'center' as const,
    fixed: 'right' as const,
  },
];
export function departmentAssetPeriod(row: DepartmentAssetsApi.Asset) {
  return row.applyType === 1
    ? '长期有效'
    : `${marketCheckTime(row.startTime)} 至 ${marketCheckTime(row.endTime)}`;
}
export function departmentAssetIcon(name: string) {
  return `/assets/qdata-project-category/${name}.png`;
}
export function departmentDeliveryLabel(value: number) {
  return (
    DEPARTMENT_ASSET_DELIVERIES.find((item) => item.value === value)?.label ||
    String(value)
  );
}
