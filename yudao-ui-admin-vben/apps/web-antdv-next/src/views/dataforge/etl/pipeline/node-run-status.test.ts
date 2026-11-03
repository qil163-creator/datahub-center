import { describe, expect, it } from 'vitest';

import {
  formatDurationMs,
  groupNodeRunStatuses,
  summarizeNodeRecords,
} from './node-run-status';

describe('子流程运行状态', () => {
  it('子节点尚未完成时不能把父调用显示为成功', () => {
    expect(
      groupNodeRunStatuses([
        { nodeNo: 'child-a', designNodeNo: 'call', runStatus: 3 },
        { nodeNo: 'child-b', designNodeNo: 'call', runStatus: 1 },
      ]),
    ).toEqual([
      { id: 'call', status: 2, readCount: undefined, writeCount: undefined },
    ]);
    expect(
      groupNodeRunStatuses([
        { nodeNo: 'child-a', designNodeNo: 'call', runStatus: 3 },
        { nodeNo: 'child-b', designNodeNo: 'call', runStatus: 4 },
      ]),
    ).toEqual([
      { id: 'call', status: 4, readCount: undefined, writeCount: undefined },
    ]);
  });

  it('单条记录透传读写行数，子流程多条子记录不求和也不透传', () => {
    expect(
      groupNodeRunStatuses([
        {
          nodeNo: 'source',
          designNodeNo: 'source',
          runStatus: 3,
          readCount: 120,
          writeCount: 118,
        },
        {
          nodeNo: 'child-a',
          designNodeNo: 'call',
          runStatus: 3,
          readCount: 10,
          writeCount: 10,
        },
        {
          nodeNo: 'child-b',
          designNodeNo: 'call',
          runStatus: 3,
          readCount: 20,
          writeCount: 20,
        },
      ]),
    ).toEqual([
      { id: 'source', status: 3, readCount: 120, writeCount: 118 },
      { id: 'call', status: 3, readCount: undefined, writeCount: undefined },
    ]);
  });

  it('后端以字符串返回的大数行数原样透传，不做数值转换', () => {
    const [result] = groupNodeRunStatuses([
      {
        nodeNo: 'source',
        runStatus: 3,
        readCount: '9007199254740993' as unknown as number,
      },
    ]);
    expect(result).toMatchObject({
      id: 'source',
      readCount: '9007199254740993',
      writeCount: undefined,
    });
  });
});

describe('节点记录诊断汇总', () => {
  it('已完成记录按差值计算耗时与吞吐，运行中记录按当前时间推算并标记进行中', () => {
    const [finished, running, waiting] = summarizeNodeRecords(
      [
        {
          id: 1,
          nodeNo: 'a',
          runStatus: 3,
          startTime: 1000,
          endTime: 3000,
          writeCount: 500,
        },
        { id: 2, nodeNo: 'b', runStatus: 2, startTime: 2000, writeCount: 30 },
        { id: 3, nodeNo: 'c', runStatus: 1 },
      ],
      5000,
    );
    expect(finished).toMatchObject({
      durationMs: 2000,
      running: false,
      throughputPerSecond: 250,
      slowest: true,
    });
    expect(running).toMatchObject({
      durationMs: 3000,
      running: true,
      throughputPerSecond: 10,
      slowest: false,
    });
    expect(waiting).toMatchObject({
      durationMs: null,
      running: false,
      throughputPerSecond: null,
      slowest: false,
    });
  });

  it('只有开始时间但已失败时耗时为空，不按当前时间推算', () => {
    const [failed] = summarizeNodeRecords(
      [{ id: 1, nodeNo: 'a', runStatus: 4, startTime: 1000, writeCount: 5 }],
      9000,
    );
    expect(failed).toMatchObject({
      durationMs: null,
      running: false,
      throughputPerSecond: null,
      slowest: false,
    });
  });

  it('耗时为零或写入行数缺失时吞吐为空，字符串行数按近似数值计算', () => {
    const [zero, missing, text] = summarizeNodeRecords([
      {
        id: 1,
        nodeNo: 'a',
        runStatus: 3,
        startTime: 1000,
        endTime: 1000,
        writeCount: 10,
      },
      { id: 2, nodeNo: 'b', runStatus: 3, startTime: 1000, endTime: 2000 },
      {
        id: 3,
        nodeNo: 'c',
        runStatus: 3,
        startTime: 1000,
        endTime: 3000,
        writeCount: '1000' as unknown as number,
      },
    ]);
    expect(zero).toMatchObject({ durationMs: 0, throughputPerSecond: null });
    expect(missing).toMatchObject({
      durationMs: 1000,
      throughputPerSecond: null,
    });
    expect(text).toMatchObject({ durationMs: 2000, throughputPerSecond: 500 });
    expect(text?.writeCount).toBe('1000');
  });

  it('最慢节点只在已完成记录中选唯一一条，耗时相同取先出现者，全部未完成则没有', () => {
    const summaries = summarizeNodeRecords(
      [
        {
          id: 1,
          nodeNo: 'slow-running',
          runStatus: 2,
          startTime: 0,
        },
        {
          id: 2,
          nodeNo: 'first',
          runStatus: 4,
          startTime: 1000,
          endTime: 4000,
        },
        {
          id: 3,
          nodeNo: 'second',
          runStatus: 5,
          startTime: 1000,
          endTime: 4000,
        },
        { id: 4, nodeNo: 'quick', runStatus: 3, startTime: 1000, endTime: 1500 },
      ],
      100_000,
    );
    expect(summaries[0]?.durationMs).toBe(100_000);
    expect(summaries.filter((item) => item.slowest).map((item) => item.nodeNo))
      .toEqual(['first']);
    expect(
      summarizeNodeRecords(
        [
          { id: 1, nodeNo: 'a', runStatus: 2, startTime: 0 },
          { id: 2, nodeNo: 'b', runStatus: 1 },
        ],
        5000,
      ).some((item) => item.slowest),
    ).toBe(false);
  });

  it('耗时格式化覆盖空值、毫秒、秒与分钟', () => {
    expect(formatDurationMs(null)).toBe('-');
    expect(formatDurationMs(undefined)).toBe('-');
    expect(formatDurationMs(0)).toBe('0 ms');
    expect(formatDurationMs(850)).toBe('850 ms');
    expect(formatDurationMs(1000)).toBe('1.0 s');
    expect(formatDurationMs(12_345)).toBe('12.3 s');
    expect(formatDurationMs(60_000)).toBe('1m 0s');
    expect(formatDurationMs(119_999)).toBe('1m 59s');
    expect(formatDurationMs(3_725_000)).toBe('62m 5s');
  });
});
