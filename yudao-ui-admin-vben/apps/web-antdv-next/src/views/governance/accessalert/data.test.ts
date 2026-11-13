import type { SafetySecurityApi } from '#/api/governance/security';

import { describe, expect, it, vi } from 'vitest';

import { filterKeyRefs, keyRefFormError, useFormSchema } from '../keyref/data';
import {
  formatTime,
  ruleLabel,
  ruleUnit,
  statusView,
  useGridFormSchema,
  useHandleFormSchema,
} from './data';

vi.mock('@vben/hooks', () => ({
  getDictOptions: () => [
    { label: '开启', value: 0 },
    { label: '关闭', value: 1 },
  ],
}));
vi.mock('#/adapter/form', async () => {
  const { z } = await import('@vben/common-ui');
  return { z };
});
vi.mock('#/api/system/user', () => ({ getSimpleUserList: vi.fn() }));

function keyRef(
  partial: Partial<SafetySecurityApi.KeyRef>,
): SafetySecurityApi.KeyRef {
  return {
    id: 1,
    keyCode: 'pii_default',
    keyName: '个人信息',
    provider: 'LOCAL',
    keyType: 'AES',
    status: 0,
    ...partial,
  };
}

describe('access alert and key ref page data', () => {
  it('labels known codes and echoes unknown ones', () => {
    expect(ruleLabel('PLAINTEXT')).toBe('豁免明文访问过多');
    expect(ruleUnit('TABLE_SPREAD')).toBe('张表');
    expect(ruleLabel('NEW_RULE')).toBe('NEW_RULE');
    expect(statusView('OPEN').label).toBe('待处理');
    expect(statusView('LATER').label).toBe('LATER');
  });

  it('formats timestamps for display only', () => {
    expect(formatTime(undefined)).toBe('-');
    expect(formatTime(new Date(2026, 10, 12, 9, 5).getTime())).toBe(
      '2026-11-12 09:05',
    );
    expect(formatTime('not a date')).toBe('not a date');
  });

  it('defaults the alert search to open alerts', () => {
    const status = useGridFormSchema().find(
      (item) => item.fieldName === 'status',
    );
    expect(status?.defaultValue).toBe('OPEN');
  });

  it('requires a handle remark', () => {
    const remark = useHandleFormSchema().find(
      (item) => item.fieldName === 'remark',
    );
    const rule = remark?.rules as { safeParse: (v: unknown) => any };
    expect(rule.safeParse('   ').success).toBe(false);
    expect(rule.safeParse('已核实为批量对账').success).toBe(true);
  });

  it('validates key reference codes like the backend', () => {
    expect(keyRefFormError('pii_default', '个人信息')).toBeUndefined();
    expect(keyRefFormError('1bad', 'x')).toContain('字母开头');
    expect(keyRefFormError('ok', '  ')).toBe('请输入名称');

    const keyCode = useFormSchema().find(
      (item) => item.fieldName === 'keyCode',
    );
    const rule = keyCode?.rules as { safeParse: (v: unknown) => any };
    expect(rule.safeParse('pii_default').success).toBe(true);
    expect(rule.safeParse('1bad').success).toBe(false);
  });

  it('filters key references by keyword and status on the client', () => {
    const list = [
      keyRef({ id: 1, keyCode: 'pii_default', keyName: '个人信息' }),
      keyRef({ id: 2, keyCode: 'fin_card', keyName: '银行卡', status: 1 }),
    ];
    expect(filterKeyRefs(list, {}).map((item) => item.id)).toEqual([1, 2]);
    expect(filterKeyRefs(list, { keyword: 'PII' }).map((i) => i.id)).toEqual([
      1,
    ]);
    expect(filterKeyRefs(list, { keyword: '银行' }).map((i) => i.id)).toEqual([
      2,
    ]);
    expect(filterKeyRefs(list, { status: 1 }).map((i) => i.id)).toEqual([2]);
  });
});
