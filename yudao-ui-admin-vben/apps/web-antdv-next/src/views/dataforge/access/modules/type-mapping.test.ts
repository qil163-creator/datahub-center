import type { DataforgeAccessApi } from '#/api/dataforge/access';

import { describe, expect, it } from 'vitest';

import { isTypeMappingPlan, matchesMappingDecision } from './type-mapping';

const column: DataforgeAccessApi.TypeMappingColumn = {
  column: 'amount',
  sourceNativeType: 'INTEGER',
  logicalType: 'INT64',
  targetNativeType: 'bigint',
  fidelity: 'LOSSY',
  accepted: false,
  mappingFingerprint: 'a'.repeat(64),
  lossCodes: ['SQLITE_AFFINITY', 'DEFAULT_DROPPED'],
  issues: [],
};
const plan: DataforgeAccessApi.TypeMappingPlan = {
  schemaVersion: 1,
  sourceProduct: 'SQLITE3',
  sourceSchema: 'main',
  sourceTable: 'orders',
  targetProduct: 'POSTGRESQL',
  targetSchema: 'public',
  targetTable: 'orders_copy',
  fidelity: 'LOSSY',
  accepted: false,
  totalColumns: 1,
  truncated: false,
  columns: [column],
};
const decision: DataforgeAccessApi.TypeMappingAcknowledgement = {
  targetProduct: plan.targetProduct,
  targetSchema: plan.targetSchema,
  targetTable: plan.targetTable,
  column: column.column,
  mappingFingerprint: column.mappingFingerprint,
  lossCodes: [...column.lossCodes],
};

describe('type mapping confirmation scope', () => {
  it('requires the exact product/schema/table/column/fingerprint and the full reason set', () => {
    expect(matchesMappingDecision(decision, plan, column)).toBe(true);
    expect(
      matchesMappingDecision(
        { ...decision, lossCodes: decision.lossCodes.toReversed() },
        plan,
        column,
      ),
    ).toBe(true);
    for (const changed of [
      { targetProduct: 'GREENPLUM' },
      { targetSchema: 'other' },
      { targetTable: 'other' },
      { column: 'other' },
      { mappingFingerprint: 'b'.repeat(64) },
      { lossCodes: ['SQLITE_AFFINITY'] },
      { lossCodes: ['SQLITE_AFFINITY', 'SQLITE_AFFINITY'] },
      { lossCodes: [...column.lossCodes, 'NEW_LOSS'] },
    ])
      expect(
        matchesMappingDecision({ ...decision, ...changed }, plan, column),
      ).toBe(false);
  });

  it('never approves unsupported/exact columns or a truncated plan', () => {
    expect(
      matchesMappingDecision(decision, plan, {
        ...column,
        fidelity: 'UNSUPPORTED',
      }),
    ).toBe(false);
    expect(
      matchesMappingDecision(decision, plan, { ...column, fidelity: 'EXACT' }),
    ).toBe(false);
    expect(
      matchesMappingDecision(decision, { ...plan, truncated: true }, column),
    ).toBe(false);
  });

  it('rejects malformed diagnostics and incomplete summaries that pretend to be complete', () => {
    expect(isTypeMappingPlan(plan)).toBe(true);
    expect(
      isTypeMappingPlan({ ...plan, truncated: true, totalColumns: 2 }),
    ).toBe(true);
    for (const invalid of [
      null,
      {},
      { ...plan, schemaVersion: 2 },
      { ...plan, columns: [] },
      { ...plan, truncated: undefined },
      { ...plan, totalColumns: 2 },
      { ...plan, totalColumns: 0 },
      { ...plan, columns: [column, column], totalColumns: 2 },
      { ...plan, columns: [{ ...column, mappingFingerprint: 'unverified' }] },
      { ...plan, columns: [{ ...column, lossCodes: [] }] },
      { ...plan, columns: [{ ...column, lossCodes: ['bad-code'] }] },
      { ...plan, columns: [{ ...column, issues: [null] }] },
    ])
      expect(isTypeMappingPlan(invalid)).toBe(false);
  });
});
