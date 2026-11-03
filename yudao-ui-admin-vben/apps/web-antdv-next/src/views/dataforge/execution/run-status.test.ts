import { describe, expect, it } from 'vitest';

import { getRunState } from './run-status';

describe('run state is domain and evidence aware', () => {
  it('never confuses Access UNKNOWN=5 with ETL STOPPED=5', () => {
    expect(
      getRunState('ACCESS', { runStatus: 5, occupiesSlot: true }).label,
    ).toContain('核查');
    expect(getRunState('ETL', { runStatus: 5 }).label).toBe('已确认停止');
  });
  it('does not call an accepted cancellation stopped', () => {
    const state = getRunState('ETL', {
      runStatus: 2,
      stopRequested: true,
      occupiesSlot: true,
    });
    expect(state.label).toContain('待确认');
    expect(state.canStop).toBe(false);
    expect(state.canDelete).toBe(false);
    expect(state.shouldPoll).toBe(true);
  });
  it('keeps terminated partial writes blocked for review', () => {
    const state = getRunState('ACCESS', {
      runStatus: 4,
      occupiesSlot: true,
      reviewRequired: true,
      rowVersion: '9',
    });
    expect(state.label).toContain('核查');
    expect(state.canResolve).toBe(true);
    expect(state.canDelete).toBe(false);
  });
  it('keeps quality pending and unknown distinct from ordinary success', () => {
    expect(
      getRunState('ETL', {
        runStatus: 3,
        qualityStatus: 'PENDING',
        occupiesSlot: true,
      }).canResolve,
    ).toBe(false);
    expect(
      getRunState('ETL', {
        runStatus: 3,
        qualityStatus: 'UNKNOWN',
        occupiesSlot: true,
        rowVersion: 7,
      }).canResolve,
    ).toBe(true);
    expect(
      getRunState('ETL', {
        runStatus: 3,
        qualityStatus: 'PASSED',
        occupiesSlot: false,
      }).canDelete,
    ).toBe(true);
  });
});
