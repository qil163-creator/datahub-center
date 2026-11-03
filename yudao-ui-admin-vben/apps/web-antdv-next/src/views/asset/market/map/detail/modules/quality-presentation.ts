import type { ImportedAssetApi } from '#/api/asset/dataasset/imported';
import type { QualityReportApi } from '#/api/governance/quality/report';

import { REPORT_DIMENSIONS } from '#/views/governance/quality/report/report-presentation';

/** 来源字典 att_rule_audit_q_dimension 的公开编码。 */
export function sourceDimension(value?: string) {
  if (value === '1') return 'COMPLETENESS';
  if (value === '2') return 'ACCURACY';
  if (value === '3') return 'CONSISTENCY';
  if (value === '4') return 'NORMALIZATION';
  if (value === '5') return 'TIMELINESS';
  return value || '';
}
export function qualityDimensions(
  snapshot?: ImportedAssetApi.Snapshot,
  report?: QualityReportApi.Detail,
) {
  if (snapshot) {
    const values = snapshot.quality?.statistics?.dimensions ?? [];
    const evaluations = snapshot.quality?.evaluations ?? [];
    return REPORT_DIMENSIONS.map((dimension) => {
      const count = evaluations.filter(
        (row) => sourceDimension(row.dimensionType) === dimension.value,
      ).length;
      const value = values.find(
        (row) => sourceDimension(row.dimensionType) === dimension.value,
      );
      return {
        ...dimension,
        ruleCount: count || undefined,
        issueCount: value?.problemTotal,
        totalCount: value?.total,
        proportion: value?.proportion,
        change: value?.yoyIncrease,
      };
    });
  }
  return REPORT_DIMENSIONS.map((dimension) => {
    const value = report?.dimensions.find(
      (row) => row.qualityDim === dimension.value,
    );
    return {
      ...dimension,
      ruleCount: value?.ruleCount || undefined,
      issueCount: value?.issueCount,
      totalCount: value?.totalCount,
      proportion: value?.problemRatio,
      change: undefined,
    };
  });
}
export function qualityTrend(
  snapshot?: ImportedAssetApi.Snapshot,
  report?: QualityReportApi.Detail,
) {
  if (!snapshot) return report?.trend ?? [];
  const trend = snapshot.quality?.statistics?.trend;
  return (trend?.title ?? []).map((date, index) => ({
    date,
    score: trend?.value?.[index] ?? null,
  }));
}
export function sourceEvaluation(
  snapshot: ImportedAssetApi.Snapshot | undefined,
  id?: ImportedAssetApi.Id,
) {
  return snapshot?.quality?.evaluations?.find(
    (row) => String(row.sourceId) === String(id),
  );
}
