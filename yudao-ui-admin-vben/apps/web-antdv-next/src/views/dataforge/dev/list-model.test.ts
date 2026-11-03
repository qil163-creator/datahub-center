import { describe, expect, it } from 'vitest';

import {
  buildDirectorySelectTree,
  buildDirectoryTree,
  buildProcedureTemplate,
  cronSummary,
  normalizeDirectoryId,
  resolveDirectoryName,
  resolveUserContact,
  resolveUserName,
} from './list-model';

describe('任务人员信息', () => {
  const users = [
    { id: '1', name: '创建者', mobile: 'fixture-contact' },
    { id: '9007199254740993', name: '负责人' },
  ];

  it.each([null, undefined, '', '   '])('空编号 %s 显示占位符', (id) => {
    expect(resolveUserName(id, users)).toBe('-');
    expect(resolveUserContact(id, users)).toBe('-');
  });

  it('数字编号可匹配用户且联系方式缺失时显示占位符', () => {
    expect(resolveUserName(1, users)).toBe('创建者');
    expect(resolveUserContact(1, users)).toBe('fixture-contact');
    expect(resolveUserContact('9007199254740993', users)).toBe('-');
    expect(resolveUserContact('9007199254740995', users)).toBe('-');
  });

  it('超过安全范围的用户编号无损匹配，未知编号保持原文', () => {
    expect(resolveUserName('9007199254740993', users)).toBe('负责人');
    expect(resolveUserName('9007199254740995', users)).toBe('9007199254740995');
    expect(resolveUserName('9007199254740993', [])).toBe('9007199254740993');
  });
});

describe('数据开发目录', () => {
  it.each([0, '0', null, undefined, '', '   '])(
    '顶级归属 %s 统一回显类目名称和选择值',
    (id) => {
      expect(normalizeDirectoryId(id)).toBe('0');
      expect(resolveDirectoryName(id, [])).toBe('数据开发类目');
      expect(buildDirectorySelectTree([], id)[0]).toMatchObject({
        value: '0',
        label: '数据开发类目',
        disabled: false,
      });
    },
  );

  it('数字和字符串类目编号匹配名称，大编号不发生精度转换', () => {
    const dirs = [
      { id: 7, name: '监测' },
      { id: '9007199254740993', name: '统计' },
    ];
    expect(resolveDirectoryName('7', dirs)).toBe('监测');
    expect(resolveDirectoryName('9007199254740993', dirs)).toBe('统计');
    expect(normalizeDirectoryId('9007199254740993')).toBe('9007199254740993');
    expect(resolveDirectoryName('9007199254740995', dirs)).toBe(
      '类目不可用（9007199254740995）',
    );
  });

  it('停用目录及其子目录不可重新选择，缺失目录保留可读回显', () => {
    const dirs = [
      { id: 7, name: '停用监测', status: 1 },
      { id: '8', parentId: '7', name: '统计', status: 0 },
    ];
    const root = buildDirectorySelectTree(dirs, '9007199254740993')[0]!;
    expect(root.children[0]).toMatchObject({
      value: '7',
      label: '停用监测',
      disabled: true,
      children: [{ value: '8', label: '统计', disabled: true }],
    });
    expect(root.children[1]).toMatchObject({
      value: '9007199254740993',
      label: '类目不可用（9007199254740993）',
      disabled: true,
    });
    expect(resolveDirectoryName('7', dirs)).toBe('停用监测');
  });

  it('无损构树并保留搜索命中的父节点', () => {
    const result = buildDirectoryTree(
      [
        { id: '9007199254740993', parentId: '0', name: '水雨情监测' },
        {
          id: '9007199254740995',
          parentId: '9007199254740993',
          name: '报表与统计',
        },
        { id: '9007199254740997', parentId: '0', name: '工程工情' },
      ],
      '报表',
    );
    expect(result.map((node) => node.key)).toEqual(['9007199254740993']);
    expect(result[0]?.children[0]?.key).toBe('9007199254740995');
  });
  it('搜索父级时保留其完整子树', () => {
    expect(
      buildDirectoryTree(
        [
          { id: '1', name: '监测' },
          { id: '2', parentId: '1', name: '统计' },
        ],
        '监测',
      )[0]?.children,
    ).toHaveLength(1);
  });
  it('未知Cron保持原文，避免虚报执行周期', () => {
    expect(cronSummary('0 * * * * ?')).toBe('每分钟执行');
    expect(cronSummary('0 5 2 * * ?')).toBe('每天 02:05 执行');
    expect(cronSummary('0 * * 1 * ?')).toBe('0 * * 1 * ?');
  });
  it.each([null, undefined, ''])(
    '空周期 %s 显示未配置，不转成文本',
    (value) => {
      expect(cronSummary(value)).toBe('未配置调度周期');
    },
  );
});

describe('存储过程模板提交契约', () => {
  it.each(['POSTGRESQL', 'ORACLE', 'DM'])(
    '%s 保存定义和调用脚本、独立行分段及参数快照',
    (product) => {
      const template = buildProcedureTemplate(product);
      const submitted = JSON.parse(JSON.stringify(template));
      expect(submitted.disabled).toBe(false);
      expect(submitted.content).toMatch(/CREATE (?:OR REPLACE )?PROCEDURE/i);
      expect(submitted.content).toMatch(/CALL [A-Za-z_]+\(\$\{row_id\}\)/i);
      expect(
        submitted.content.split('\n').filter((line: string) => line === '/'),
      ).toHaveLength(3);
      expect(submitted.executionConfig).toMatchObject({
        sqlType: 'PROCEDURE',
        statementSeparator: '/',
        localParams: [{ prop: 'row_id', type: 'INTEGER', value: '1' }],
      });
    },
  );
  it('未验证产品不提供可选择的过程调用模板', () => {
    expect(buildProcedureTemplate('MYSQL').disabled).toBe(true);
    expect(buildProcedureTemplate().disabled).toBe(true);
  });
});
