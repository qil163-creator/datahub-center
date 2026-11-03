import { describe, expect, it } from 'vitest';

import { defaultNamingRules, namingPreview, parentOptions } from './state';

describe('业务规划规则', () => {
  it('事实表使用原样板块英文名、可切换的主题域缩写和日后缀', () => {
    const rule = defaultNamingRules()[1]!;
    expect(namingPreview(rule, ' EC_RETAIL ')).toBe('dwd_EC_RETAIL_theme_da');
    rule.includeSubjectAbbreviation = false;
    rule.daySuffix = '_custom';
    expect(namingPreview(rule, 'EC_RETAIL')).toBe('dwd_EC_RETAIL_custom');
    expect(namingPreview(defaultNamingRules()[0]!, 'IGNORED')).toBe('ods_order');
    expect(namingPreview(defaultNamingRules()[3]!, 'IGNORED')).toBe('dws_order');
    const summaryRule = defaultNamingRules()[3]!;
    summaryRule.daySuffix = '_dd';
    expect(namingPreview(summaryRule, 'IGNORED')).toBe('dws_order_dd');
  });

  it('新开向导的命名规则不会继承前一份表单的编辑', () => {
    const previous = defaultNamingRules();
    previous[1]!.daySuffix = '_modified';
    expect(defaultNamingRules()[1]!.daySuffix).toBe('_da');
  });

  it('第五级主题不可继续作为新节点的父级', () => {
    const rows = Array.from({ length: 5 }, (_, index) => ({ id: index + 1, parentId: index, dataDomainId: 29, name: `层级${index + 1}` }));
    expect(parentOptions(rows, 29).map((item) => item.value)).toEqual(['1', '2', '3', '4']);
  });

  it('父主题选项只保留当前板块并排除自己和所有后代，不损失大编号', () => {
    const id = '9007199254740993123';
    const rows = [
      { id, dataDomainId: '29', parentId: 0, name: '当前节点' },
      { id: '9007199254740993124', dataDomainId: '29', parentId: id, name: '子节点' },
      { id: '9007199254740993125', dataDomainId: '29', parentId: '9007199254740993124', name: '孙节点' },
      { id: '9007199254740993126', dataDomainId: 29, parentId: 0, name: '可选节点' },
      { id: 5, dataDomainId: 30, parentId: 0, name: '其他板块' },
    ];
    expect(parentOptions(rows, 29, id)).toEqual([{ label: '可选节点', value: '9007199254740993126' }]);
  });
});
