/** 在线填报各页面共享的状态/枚举展示映射 */

export interface StatusDisplay {
  color: string;
  text: string;
}

function toOptions(map: Record<number, StatusDisplay>) {
  return Object.entries(map).map(([value, item]) => ({
    label: item.text,
    value: Number(value),
  }));
}

/** 工作项主状态 */
export const WORK_ITEM_STATUS_TEXT: Record<number, StatusDisplay> = {
  0: { color: 'default', text: '待填报' },
  1: { color: 'processing', text: '填报中' },
  2: { color: 'processing', text: '提交校验中' },
  3: { color: 'processing', text: '待启动审批' },
  4: { color: 'warning', text: '审批中' },
  5: { color: 'error', text: '整改中' },
  6: { color: 'processing', text: '待合并' },
  7: { color: 'processing', text: '合并中' },
  8: { color: 'error', text: '合并失败' },
  9: { color: 'success', text: '已合并' },
  10: { color: 'default', text: '已取消' },
};

export const WORK_ITEM_STATUS_OPTIONS = toOptions(WORK_ITEM_STATUS_TEXT);

/** 提交审批状态 */
export const APPROVAL_STATUS_TEXT: Record<number, StatusDisplay> = {
  0: { color: 'default', text: '待启动' },
  1: { color: 'processing', text: '审批中' },
  2: { color: 'success', text: '审批通过' },
  3: { color: 'error', text: '审批驳回' },
  4: { color: 'warning', text: '已撤回' },
  5: { color: 'default', text: '已终止' },
};

/** 数据更正状态 */
export const CORRECTION_STATUS_TEXT: Record<number, StatusDisplay> = {
  0: { color: 'processing', text: '整改中' },
  1: { color: 'warning', text: '审批中' },
  2: { color: 'success', text: '已合并' },
  3: { color: 'default', text: '已取消' },
};

/** 采集计划状态 */
export const PLAN_STATUS_TEXT: Record<number, StatusDisplay> = {
  0: { color: 'default', text: '草稿' },
  1: { color: 'success', text: '启用中' },
  2: { color: 'warning', text: '已停用' },
};

/** 采集任务状态 */
export const TASK_STATUS_TEXT: Record<number, StatusDisplay> = {
  0: { color: 'default', text: '待开始' },
  1: { color: 'processing', text: '进行中' },
  2: { color: 'success', text: '已完成' },
  3: { color: 'default', text: '已取消' },
};

/** 周期类型 */
export const PERIOD_TYPE_OPTIONS = [
  { label: '一次性', value: 1 },
  { label: '每日', value: 2 },
  { label: '每周', value: 3 },
  { label: '每月', value: 4 },
  { label: '每季度', value: 5 },
];

export const PERIOD_TYPE_TEXT: Record<number, string> = Object.fromEntries(
  PERIOD_TYPE_OPTIONS.map((item) => [item.value, item.label]),
);

/** 周制生成日 */
export const WEEK_DAY_OPTIONS = [
  { label: '周一', value: 1 },
  { label: '周二', value: 2 },
  { label: '周三', value: 3 },
  { label: '周四', value: 4 },
  { label: '周五', value: 5 },
  { label: '周六', value: 6 },
  { label: '周日', value: 7 },
];
