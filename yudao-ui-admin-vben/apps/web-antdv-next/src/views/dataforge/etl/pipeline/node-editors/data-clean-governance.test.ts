import { describe, expect, it } from 'vitest';

import {
  createGovernanceCleanSelection,
  governanceCleanSelectionField,
  governanceCleanSelectionFields,
  governanceRuleParameters,
  isGovernanceCleanSelectionEditable,
  selectGovernanceCleanRules,
  setGovernanceCleanSelectionField,
  setGovernanceCleanSelectionFields,
} from './data-clean-governance';

describe('etl governance clean-rule selector contract', () => {
  it('only offers enabled field-level rules with an ETL-supported function', () => {
    const result = selectGovernanceCleanRules([
      {
        id: '9007199254740993',
        name: 'Trim',
        ruleLevel: 1,
        status: 0,
        strategyKey: 'REMOVE_WHITESPACE',
        engineSupported: true,
        rowTransformSupported: true,
      },
      {
        id: 2,
        ruleLevel: 1,
        status: 1,
        strategyKey: 'REMOVE_WHITESPACE',
        engineSupported: true,
      },
      {
        id: 3,
        ruleLevel: 2,
        status: 0,
        strategyKey: 'REMOVE_WHITESPACE',
        engineSupported: true,
      },
      {
        id: 4,
        ruleLevel: 1,
        status: 0,
        strategyKey: 'MENU_CUSTOM',
        engineSupported: true,
        rowTransformSupported: true,
      },
      {
        id: 5,
        ruleLevel: 2,
        status: 0,
        strategyKey: 'KEEP_LATEST_OR_FIRST',
        engineSupported: true,
        rowTransformSupported: false,
      },
      {
        id: 6,
        ruleLevel: 1,
        status: 0,
        strategyKey: 'UNKNOWN',
        engineSupported: false,
        rowTransformSupported: false,
      },
    ]);
    expect(result.map((rule) => String(rule.id))).toEqual([
      '9007199254740993',
      '4',
      '5',
    ]);
  });

  it('组合规则保存多个字段，过期规则仍保存单个日期字段', () => {
    const selection = createGovernanceCleanSelection(
      {
        id: '9007199254740993',
        strategyKey: 'KEEP_LATEST_OR_FIRST',
        rowTransformSupported: false,
        columnBinding: 'MULTIPLE',
      },
      'id',
    );
    setGovernanceCleanSelectionFields(selection, ['id', 'version']);
    expect(selection).toEqual({
      ruleId: '9007199254740993',
      fields: ['id', 'version'],
    });
    expect(governanceCleanSelectionFields(selection)).toEqual([
      'id',
      'version',
    ]);
    expect(isGovernanceCleanSelectionEditable(selection)).toBe(true);
    expect(
      createGovernanceCleanSelection(
        {
          id: '7',
          strategyKey: 'CHECK_EXPIRATION',
          rowTransformSupported: false,
        },
        'date',
      ),
    ).toEqual({ ruleId: '7', field: 'date' });
  });

  it('集合执行的单字段算法与整行算法依据绑定元数据分别处理', () => {
    expect(
      createGovernanceCleanSelection(
        {
          id: '40',
          strategyKey: 'NUMBER_FILL_NULL',
          rowTransformSupported: false,
          columnBinding: 'SINGLE',
        },
        'amount',
      ),
    ).toEqual({ ruleId: '40', field: 'amount' });
    const selection = createGovernanceCleanSelection(
      {
        id: '41',
        strategyKey: 'REMOVE_EMPTY_ROW',
        rowTransformSupported: false,
        columnBinding: 'ALL',
      },
      'ignored',
    );
    expect(selection).toEqual({ ruleId: '41' });
    expect(isGovernanceCleanSelectionEditable(selection)).toBe(true);
  });

  it('stores IDs losslessly and keeps only the rule and field binding in a new node', () => {
    const selected = createGovernanceCleanSelection(
      {
        id: '9007199254740993',
        status: 0,
        ruleLevel: 1,
        strategyKey: 'FIX_TO_PRECISION',
      },
      'amount',
    );
    expect(selected).toEqual({
      ruleId: '9007199254740993',
      field: 'amount',
    });
  });

  it('rejects malformed drafts without mutating their stored configuration', () => {
    const malformed = { ruleId: '3', config: {} };
    expect(isGovernanceCleanSelectionEditable(malformed)).toBe(false);
    expect(malformed).toEqual({ ruleId: '3', config: {} });
    expect(
      isGovernanceCleanSelectionEditable({
        ruleId: '4',
        config: { columns: [] },
      }),
    ).toBe(false);
    expect(isGovernanceCleanSelectionEditable({ ruleId: '5', field: '' })).toBe(
      true,
    );
    expect(
      isGovernanceCleanSelectionEditable({
        ruleId: '5',
        config: { columns: ['amount'], scale: 2 },
      }),
    ).toBe(true);
    expect(isGovernanceCleanSelectionEditable(null)).toBe(false);
  });

  it('preserves legacy config for server-side assertion and only changes its field binding', () => {
    const legacy = { ruleId: '5', config: { columns: ['amount'], scale: 2 } };
    expect(governanceCleanSelectionField(legacy)).toBe('amount');
    setGovernanceCleanSelectionField(legacy, 'total');
    expect(legacy).toEqual({
      ruleId: '5',
      config: { columns: ['total'], scale: 2 },
    });
    expect(governanceRuleParameters(legacy.config)).toBe('{"scale":2}');
  });

  it('distinguishes historical missing parameters from an explicitly empty object', () => {
    expect(governanceRuleParameters(null)).toBe('未配置持久化参数');
    expect(governanceRuleParameters(undefined)).toBe('未配置持久化参数');
    expect(governanceRuleParameters({})).toBe('{}');
  });
});
