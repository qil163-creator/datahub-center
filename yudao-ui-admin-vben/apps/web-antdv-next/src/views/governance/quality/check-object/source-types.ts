// 类型描述连接在质量检查中的业务用途，与 PostgreSQL 等数据库引擎分别保存。
export const qualityObjectSourceTypes = [
  { label: '中台贴源库', value: 'ODS' },
  { label: '中台明细库', value: 'DWD' },
  { label: '中台主库', value: 'MAIN' },
  { label: '第三方库', value: 'THIRD_PARTY' },
];
