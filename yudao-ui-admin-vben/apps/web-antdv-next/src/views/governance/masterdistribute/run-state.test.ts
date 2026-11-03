import { describe, expect, it } from 'vitest';

import {
  isRunBlocked,
  nextRunTimeLabel,
  optionLabel,
  releaseTimeLabel,
  releaseUserLabel,
  RUN_STATUS_OPTIONS,
} from './data';

describe('主数据派发持久占位', () => {
  it.each([1, 2, 5, 99, undefined])(
    '状态 %s 不允许直接重跑',
    (blockingRunStatus) => {
      expect(isRunBlocked({ blockingRunStatus })).toBe(true);
    },
  );
  it('只有服务端明确空闲允许新执行', () => {
    expect(isRunBlocked({ id: '9007199254740993', blockingRunStatus: 0 })).toBe(
      false,
    );
  });
  it('等待和未知在执行记录中可筛选并显示', () => {
    expect(optionLabel(RUN_STATUS_OPTIONS, 1)).toBe('等待派发');
    expect(optionLabel(RUN_STATUS_OPTIONS, 5)).toBe('结果未知，待核查');
    expect(optionLabel(RUN_STATUS_OPTIONS, 6)).toBe('已人工核查处置（非成功）');
  });
});

describe('派发空字段按真实语义展示', () => {
  it('一次性任务没有下次执行时间，未发布周期任务未排期', () => {
    expect(nextRunTimeLabel({ taskType: 1, status: 1 })).toBe(
      '不适用（一次性）',
    );
    expect(nextRunTimeLabel({ taskType: 2, status: 0 })).toBe('未排期');
    expect(nextRunTimeLabel({ taskType: 2, status: 1 })).toBe('未记录');
  });
  it('取消发布后仍展示最近发布信息，缺少昵称时保留大整数编号', () => {
    expect(releaseUserLabel({ status: 0, releaseUserName: '管理员' })).toBe(
      '管理员',
    );
    expect(
      releaseUserLabel({ status: 0, releaseUserId: '9007199254740993' }),
    ).toBe('用户 #9007199254740993');
    expect(
      releaseTimeLabel({ status: 0, releaseTime: 1790488800000 }),
    ).not.toBe('暂无发布记录');
  });
  it('尚无发布元数据与已发布却未记录元数据分别回显', () => {
    expect(releaseUserLabel({ status: 0 })).toBe('暂无发布记录');
    expect(releaseTimeLabel({ status: 0 })).toBe('暂无发布记录');
    expect(releaseUserLabel({ status: 1 })).toBe('未记录');
    expect(releaseTimeLabel({ status: 1 })).toBe('未记录');
  });
});
