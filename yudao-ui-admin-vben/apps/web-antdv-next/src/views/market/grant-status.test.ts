import { describe, expect, it } from 'vitest';

import {
  applyAccessLabel,
  controlActionText,
  deliveryActionLabel,
  deliveryColor,
  deliveryLabel,
} from './grant-status';

describe('grant status presentation', () => {
  it.each([
    ['GRANT', '请求授权'],
    ['SUSPEND', '请求暂停'],
    ['REVOKE', '请求撤销'],
    ['UNKNOWN', '动作待核查'],
    [undefined, '动作待核查'],
    [null, '动作待核查'],
    ['', '动作待核查'],
    ['FUTURE_ACTION', '动作待核查'],
    ['SUSPENDED', '动作待核查'],
    ['suspend', '动作待核查'],
    ['constructor', '动作待核查'],
    ['__proto__', '动作待核查'],
  ])(
    'shows action %s as intent, never as execution success',
    (action, label) => {
      expect(deliveryActionLabel(action)).toBe(label);
    },
  );

  it('distinguishes confirmed suspension from terminal revocation', () => {
    expect(deliveryLabel('SUSPENDED')).toBe('暂停已确认');
    expect(deliveryColor('SUSPENDED')).toBe('warning');
    expect(deliveryLabel('REVOKED')).toBe('撤销已确认');
    expect(deliveryColor('REVOKED')).toBe('default');
  });

  it.each([true, false, undefined])(
    'never calls a suspended apply usable even when usable is %s',
    (usable) => {
      expect(applyAccessLabel(true, 'SUSPENDED', usable)).toBe(
        '已批准，暂停已确认',
      );
      expect(applyAccessLabel(false, 'SUSPENDED', usable)).toBe(
        '未生效，暂停已确认',
      );
      expect(applyAccessLabel(true, 'REVOKED', usable)).toBe(
        '已批准，撤销已确认',
      );
      expect(applyAccessLabel(false, 'REVOKED', usable)).toBe(
        '未生效，撤销已确认',
      );
      expect(applyAccessLabel(false, 'APPLIED', usable)).toBe('未生效');
    },
  );

  it.each([
    'APPLIED',
    'PENDING',
    'REJECTED',
    'SUPERSEDED',
    'UNKNOWN',
    undefined,
  ] as const)(
    'preserves ineffective %s semantics even if usable is incorrectly true',
    (resolution) => {
      expect(applyAccessLabel(false, resolution, true)).toBe('未生效');
      expect(applyAccessLabel(undefined, resolution, true)).toBe('未生效');
    },
  );

  it('does not turn unknown delivery into success', () => {
    expect(deliveryLabel('UNKNOWN')).toBe('结果待确认');
    expect(deliveryLabel('PENDING')).toBe('待生效');
    expect(deliveryLabel('APPLIED')).toBe('已生效');
    expect(deliveryLabel('REJECTED')).toBe('交付被拒绝');
    expect(deliveryLabel(undefined)).toBe('无需交付');
  });

  it('keeps an approved application with an unconfirmed delivery out of "已开通"', () => {
    expect(applyAccessLabel(true, 'APPLIED', true)).toBe('可用');
    expect(applyAccessLabel(true, undefined, true)).toBe('可用');
    expect(applyAccessLabel(true, 'APPLIED', false)).toBe('已批准，当前不可用');
    expect(applyAccessLabel(true, 'APPLIED')).toBe(
      '已批准，实际访问范围待确认',
    );
    expect(applyAccessLabel(true, 'SUPERSEDED', true)).toBe(
      '已批准，已被更新操作取代',
    );
    expect(applyAccessLabel(true, 'REVOKED', true)).toBe('已批准，撤销已确认');
    expect(applyAccessLabel(true, 'PENDING')).toBe('已批准，待生效');
    expect(applyAccessLabel(true, 'UNKNOWN')).toBe('已批准，结果待确认');
    expect(applyAccessLabel(true, 'REJECTED')).toBe('已批准，交付被拒绝');
    // 审批未生效时不受交付结果影响，也不能显示为可用。
    expect(applyAccessLabel(false, 'APPLIED')).toBe('未生效');
  });

  it('uses different wording for revoking a source and disabling an application', () => {
    expect(controlActionText(true)).toBe('整体禁用（保留来源记录，可再启用）');
    expect(controlActionText(false)).toBe('重新启用（按来源账本恢复）');
    expect(controlActionText(true)).not.toBe(controlActionText(false));
  });
});
