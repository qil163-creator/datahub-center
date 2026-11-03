import { describe, expect, it } from 'vitest';

import {
  isUnresolvedMaterialization,
  MATERIALIZED_STATUS_OPTIONS,
  materializedStatus,
  recoveryAction,
} from './state';

describe('materialized recovery actions', () => {
  const row = { modelId: '9007199254740993123', id: '9007199254740993127' };

  it.each(['RUNNING', 'UNKNOWN'])(
    'keeps %s blocked from republication and reconciles the owned attempt',
    (status) => {
      const record = { ...row, status, attemptId: '9007199254740993129' };
      expect(isUnresolvedMaterialization(record)).toBe(true);
      expect(recoveryAction(record)).toBe('reconcile');
    },
  );

  it('requires a materialized record ID for reconciliation', () => {
    expect(recoveryAction({ modelId: row.modelId, publishStatus: 'UNKNOWN' })).toBeUndefined();
  });

  it.each(['SUCCESS', 'FAIL', 'UNPUBLISHED'])(
    'never offers a recovery action for %s',
    (status) => {
      expect(recoveryAction({ ...row, status, attemptId: '9007199254740993129' })).toBeUndefined();
      expect(isUnresolvedMaterialization({ ...row, status })).toBe(false);
    },
  );

  it('uses the current publishStatus and gives UNKNOWN an explicit label', () => {
    expect(materializedStatus({ publishStatus: 'UNKNOWN', status: 'SUCCESS' })).toBe('UNKNOWN');
    expect(MATERIALIZED_STATUS_OPTIONS.find((option) => option.value === 'UNKNOWN')?.label).toBe('结果未知');
  });
});
