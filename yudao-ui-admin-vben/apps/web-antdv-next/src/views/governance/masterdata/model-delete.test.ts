import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { describe, expect, it } from 'vitest';

import { canDeleteMasterModel } from './model-delete';

describe('主数据模型删除策略', () => {
  it('草稿和已下线可删除，曾发布记录不再阻止下线模型删除', () => {
    const id = '9007199254740997';
    const offline = {
      id,
      status: 2,
      publishState: 'READY' as const,
      releaseTime: 1_791_770_400_000,
    };
    expect(canDeleteMasterModel({ id, status: 0 })).toBe(true);
    expect(canDeleteMasterModel({ id, status: 0, publishState: 'READY' })).toBe(
      true,
    );
    expect(canDeleteMasterModel(offline)).toBe(true);
    expect(canDeleteMasterModel({ id, status: 2 })).toBe(true);
    expect(canDeleteMasterModel({ id, status: 0, publishState: null })).toBe(
      true,
    );
    expect(canDeleteMasterModel({ id, status: 2, publishState: null })).toBe(
      true,
    );
    expect(offline.id).toBe(id);
  });

  it.each([undefined, null, 'READY', 'APPLYING', 'UNKNOWN'] as const)(
    '已发布模型在发布状态 %s 下均不可删除',
    (publishState) => {
      expect(canDeleteMasterModel({ id: 1, status: 1, publishState })).toBe(
        false,
      );
    },
  );

  it.each([0, 2])('状态%s的发布结果未确认时不可删除', (status) => {
    expect(
      canDeleteMasterModel({ id: 1, status, publishState: 'APPLYING' }),
    ).toBe(false);
    expect(
      canDeleteMasterModel({ id: 1, status, publishState: 'UNKNOWN' }),
    ).toBe(false);
    expect(
      canDeleteMasterModel({
        id: 1,
        status,
        publishState:
          'FUTURE_STATE' as GovernanceMasterDataApi.Model['publishState'],
      }),
    ).toBe(false);
  });

  it.each([undefined, null, -1, 3, '0', '2'])(
    '未知或非数字模型状态%s不可删除',
    (status) => {
      expect(
        canDeleteMasterModel({
          id: '9007199254740997',
          status: status as GovernanceMasterDataApi.Model['status'],
          publishState: 'READY',
        }),
      ).toBe(false);
    },
  );

  it('无对象或没有记录编号时不可删除', () => {
    expect(canDeleteMasterModel()).toBe(false);
    expect(canDeleteMasterModel({ status: 0, publishState: 'READY' })).toBe(
      false,
    );
  });
});
