/** L1~L4 的默认配色，未知等级使用灰色兜底；后续新增等级时可由用户在表单中自选颜色 */
export const SAFETY_LEVEL_DEFAULT_COLORS: Record<string, string> = {
  L1: '#52c41a',
  L2: '#1677ff',
  L3: '#faad14',
  L4: '#f5222d',
};

/** 未匹配到默认色且未自定义颜色时的兜底灰色 */
export const SAFETY_LEVEL_FALLBACK_COLOR = '#8c8c8c';
