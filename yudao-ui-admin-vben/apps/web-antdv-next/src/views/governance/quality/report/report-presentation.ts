import type { QualityReportApi } from '#/api/governance/quality/report';

export const REPORT_DIMENSIONS = [
  { value: 'ACCURACY', label: '准确性', image: 'accuracy' },
  { value: 'NORMALIZATION', label: '规范性', image: 'normalization' },
  { value: 'TIMELINESS', label: '时效性', image: 'timeliness' },
  { value: 'CONSISTENCY', label: '一致性', image: 'consistency' },
  { value: 'COMPLETENESS', label: '完整性', image: 'completeness' },
] as const;

export const REPORT_STATUS_OPTIONS = [
  { label: '排队中', value: 'QUEUED' },
  { label: '运行中', value: 'RUNNING' },
  { label: '成功', value: 'SUCCESS' },
  { label: '失败', value: 'FAILED' },
  { label: '结果待确认', value: 'UNKNOWN' },
];

export function reportStatus(value?: string) {
  return (
    REPORT_STATUS_OPTIONS.find((item) => item.value === value)?.label ??
    value ??
    '-'
  );
}

export function dimensionLabel(value?: string) {
  return (
    REPORT_DIMENSIONS.find((item) => item.value === value)?.label ??
    (value === 'UNIQUENESS' ? '唯一性' : value || '-')
  );
}

/** 评分原文用于展示；仅图表坐标允许近似数值，不参与业务计算或提交。 */
export function chartScore(value: null | string | undefined) {
  if (value === null || value === undefined || value === '') return null;
  const score = Number(value);
  return Number.isFinite(score) ? Math.max(0, Math.min(100, score)) : null;
}

export function percentWidth(value: null | string | undefined) {
  return `${chartScore(value) ?? 0}%`;
}

export function dimensionCards(dimensions: QualityReportApi.Dimension[]) {
  const byCode = new Map(dimensions.map((item) => [item.qualityDim, item]));
  const known = REPORT_DIMENSIONS.map((item) => ({
    ...item,
    data: byCode.get(item.value),
  }));
  return [
    ...known,
    ...dimensions
      .filter(
        (item) =>
          !REPORT_DIMENSIONS.some((known) => known.value === item.qualityDim),
      )
      .map((data) => ({
        value: data.qualityDim,
        label: dimensionLabel(data.qualityDim),
        image: '',
        data,
      })),
  ];
}
