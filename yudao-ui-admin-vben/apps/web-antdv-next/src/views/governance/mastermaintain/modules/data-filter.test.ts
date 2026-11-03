import type { App } from 'vue';

import type { GovernanceMasterDataApi } from '#/api/governance/masterdata';

import { createApp, h, nextTick, ref } from 'vue';

import { afterEach, describe, expect, it, vi } from 'vitest';

import { collectMasterDataConditions } from './data-filter-model';
import DataFilter from './data-filter.vue';

const longComment = '来源报送组织编号及相关的主数据登记业务说明（系统列）';
const columns: GovernanceMasterDataApi.Column[] = [
  { commentStr: '编号', fieldType: 5, name: 'id' },
  { commentStr: '姓名', fieldType: 2, name: 'name' },
  { commentStr: '精确金额', fieldType: 6, name: 'amount' },
  { commentStr: '近似比例', fieldType: 1, name: 'ratio' },
  { commentStr: '日期', fieldType: 3, name: 'date' },
  { commentStr: '是否有效', fieldType: 4, name: 'active' },
  { commentStr: '日期时间', fieldType: 9, name: 'timestamp' },
  { commentStr: '时间', fieldType: 10, name: 'time' },
  { commentStr: '未知类型', fieldType: 99, name: 'unknown' },
  { commentStr: longComment, fieldType: 2, name: 'md_origin_organization' },
];
let app: App | undefined;
let host: HTMLDivElement;
const applied = ref<GovernanceMasterDataApi.DataCondition[]>([]);
const onApply = vi.fn((conditions: GovernanceMasterDataApi.DataCondition[]) => {
  applied.value = conditions;
});

async function flush() {
  for (let index = 0; index < 12; index++) await Promise.resolve();
  await nextTick();
}
async function mount(
  conditions: GovernanceMasterDataApi.DataCondition[] = [],
  allowedOperators?: GovernanceMasterDataApi.DataFilterOperator[],
) {
  applied.value = conditions;
  onApply.mockClear();
  host = document.createElement('div');
  document.body.append(host);
  app = createApp({
    setup: () => () =>
      h(DataFilter, {
        allowedOperators,
        appliedConditions: applied.value,
        columns,
        onApply,
      }),
  });
  app.mount(host);
  await flush();
}
function clickButton(label: string) {
  const button = [...host.querySelectorAll<HTMLButtonElement>('button')].find(
    (item) => item.textContent?.trim() === label,
  );
  expect(button).toBeDefined();
  button!.click();
}
async function selectField(index: number, query: string, name: string) {
  const select = host.querySelectorAll<HTMLElement>(
    '.master-data-filter__field',
  )[index]!;
  select.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  await flush();
  const search = select.querySelector<HTMLInputElement>('input')!;
  search.value = query;
  search.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
  const option = [
    ...document.querySelectorAll<HTMLElement>('.ant-select-item-option'),
  ].find((item) => item.textContent?.includes(name));
  expect(option).toBeDefined();
  option!.click();
  await flush();
}
async function openOperator(index: number) {
  const select = host.querySelectorAll<HTMLElement>(
    '.master-data-filter__operator .ant-select',
  )[index]!;
  select.dispatchEvent(new MouseEvent('mousedown', { bubbles: true }));
  await flush();
  return [
    ...document.querySelectorAll<HTMLElement>(
      '.ant-select-dropdown:not(.ant-select-dropdown-hidden) .ant-select-item-option',
    ),
  ];
}
async function selectOperator(index: number, label: string) {
  const options = await openOperator(index);
  const option = options.find((item) => item.textContent?.trim() === label);
  expect(option).toBeDefined();
  option!.click();
  await flush();
}
async function fillValue(index: number, value: string) {
  const input = host.querySelectorAll<HTMLInputElement>(
    '.master-data-filter__value input, input.master-data-filter__value',
  )[index]!;
  input.value = value;
  input.dispatchEvent(new Event('input', { bubbles: true }));
  await flush();
  return input;
}

afterEach(async () => {
  app?.unmount();
  app = undefined;
  await flush();
  host?.remove();
});

describe('主数据按需筛选（真实 Antdv Next 控件）', () => {
  it('默认不铺开字段，添加后可按物理字段名搜索长系统字段', async () => {
    await mount();
    expect(host.querySelector('.ant-select')).toBeNull();
    expect(host.textContent).toContain('暂无筛选条件');
    clickButton('+ 添加条件');
    await flush();
    expect(host.querySelectorAll('.ant-select')).toHaveLength(2);
    const placeholder = host.querySelector<HTMLElement>(
      '.ant-select-placeholder',
    );
    expect(placeholder).not.toBeNull();
    expect(placeholder!.textContent).toBe('选择字段（名称 / 注释）');
    expect(getComputedStyle(placeholder!).visibility).not.toBe('hidden');
    expect(getComputedStyle(placeholder!).display).not.toBe('none');
    await selectField(0, 'md_origin', 'md_origin_organization');
    expect(
      host.querySelector('.master-data-filter__field .truncate')?.textContent,
    ).toBe(`${longComment} · md_origin_organization`);
    expect(
      host.querySelector('.master-data-filter__operator')?.textContent,
    ).toBe('包含');
    await fillValue(0, '报送组织');
    expect(onApply).not.toHaveBeenCalled();
    clickButton('应用筛选');
    await flush();
    expect(onApply).toHaveBeenCalledExactlyOnceWith([
      {
        name: 'md_origin_organization',
        operator: 'CONTAINS',
        value: '报送组织',
      },
    ]);
    expect(host.textContent).toContain('已应用 1 项');
    expect(host.textContent).not.toContain('条件待应用');
  });

  it('不完整及空白条件阻止查询，禁用和删除条件后可应用', async () => {
    await mount();
    clickButton('+ 添加条件');
    await flush();
    clickButton('应用筛选');
    await flush();
    expect(onApply).not.toHaveBeenCalled();
    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    await selectField(0, '编号', 'id');
    await fillValue(0, ' \t ');
    clickButton('应用筛选');
    await flush();
    expect(onApply).not.toHaveBeenCalled();
    const checkbox = host.querySelector<HTMLInputElement>(
      'input[type="checkbox"]',
    )!;
    checkbox.click();
    await flush();
    clickButton('应用筛选');
    await flush();
    expect(onApply).toHaveBeenLastCalledWith([]);
    expect(host.querySelector('[role="alert"]')).toBeNull();
    checkbox.click();
    await flush();
    clickButton('删除');
    await flush();
    expect(host.querySelector('.ant-select')).toBeNull();
    clickButton('应用筛选');
    await flush();
    expect(onApply).toHaveBeenCalledTimes(2);
  });

  it('草稿只在应用或回车后查询，大整数与精确小数始终按原字符串提交', async () => {
    await mount([
      { name: 'id', value: '9007199254740993' },
      { name: 'amount', value: '123456789012345678.123456' },
    ]);
    expect(
      [...host.querySelectorAll('.master-data-filter__operator')].map(
        (element) => element.textContent,
      ),
    ).toEqual(['等于（=）', '等于（=）']);
    expect(host.textContent).not.toContain('条件待应用');
    const largeId = '900719925474099312345';
    const decimal = ' 123456789012345678.123456789012345678 ';
    await fillValue(0, largeId);
    const input = await fillValue(1, decimal);
    expect(onApply).not.toHaveBeenCalled();
    expect(applied.value).toEqual([
      { name: 'id', value: '9007199254740993' },
      { name: 'amount', value: '123456789012345678.123456' },
    ]);
    expect(host.textContent).toContain('条件待应用');
    input.dispatchEvent(
      new KeyboardEvent('keydown', {
        bubbles: true,
        key: 'Enter',
        keyCode: 13,
      }),
    );
    await flush();
    expect(onApply).toHaveBeenCalledExactlyOnceWith([
      { name: 'id', operator: 'EQ', value: largeId },
      { name: 'amount', operator: 'EQ', value: decimal },
    ]);
  });

  it('重置同时清除草稿及已应用条件；失效字段不会静默丢掉', async () => {
    await mount([{ name: 'name', value: '张' }]);
    clickButton('+ 添加条件');
    await flush();
    clickButton('重置');
    await flush();
    expect(host.querySelector('.ant-select')).toBeNull();
    expect(onApply).toHaveBeenCalledExactlyOnceWith([]);
    expect(
      collectMasterDataConditions(
        [
          {
            enabled: true,
            key: 1,
            name: 'deleted_field',
            operator: 'EQ',
            value: '限制条件',
          },
        ],
        columns,
      ),
    ).toEqual({ invalidKeys: [1], valid: false });
  });

  it.each([
    [
      'name',
      [
        '等于（=）',
        '不等于（≠）',
        '包含',
        '不包含',
        '为空（NULL）',
        '不为空（NULL）',
        '空字符串',
        '非空字符串',
      ],
    ],
    [
      'id',
      [
        '等于（=）',
        '不等于（≠）',
        '大于（>）',
        '大于等于（≥）',
        '小于（<）',
        '小于等于（≤）',
        '为空（NULL）',
        '不为空（NULL）',
      ],
    ],
    [
      'amount',
      [
        '等于（=）',
        '不等于（≠）',
        '大于（>）',
        '大于等于（≥）',
        '小于（<）',
        '小于等于（≤）',
        '为空（NULL）',
        '不为空（NULL）',
      ],
    ],
    [
      'ratio',
      [
        '等于（=）',
        '不等于（≠）',
        '大于（>）',
        '大于等于（≥）',
        '小于（<）',
        '小于等于（≤）',
        '为空（NULL）',
        '不为空（NULL）',
      ],
    ],
    [
      'date',
      [
        '等于（=）',
        '不等于（≠）',
        '大于（>）',
        '大于等于（≥）',
        '小于（<）',
        '小于等于（≤）',
        '为空（NULL）',
        '不为空（NULL）',
      ],
    ],
    [
      'timestamp',
      [
        '等于（=）',
        '不等于（≠）',
        '大于（>）',
        '大于等于（≥）',
        '小于（<）',
        '小于等于（≤）',
        '为空（NULL）',
        '不为空（NULL）',
      ],
    ],
    [
      'time',
      [
        '等于（=）',
        '不等于（≠）',
        '大于（>）',
        '大于等于（≥）',
        '小于（<）',
        '小于等于（≤）',
        '为空（NULL）',
        '不为空（NULL）',
      ],
    ],
    ['active', ['等于（=）', '不等于（≠）', '为空（NULL）', '不为空（NULL）']],
    ['unknown', ['为空（NULL）', '不为空（NULL）']],
  ])('%s 字段的真实操作符菜单仅展示适用选项', async (name, labels) => {
    await mount([{ name, operator: 'IS_NULL' }]);
    const options = await openOperator(0);
    expect(options.map((option) => option.textContent?.trim())).toEqual(labels);
  });

  it('选择操作符后保留字面百分号；NULL 和空字符串操作不提交旧值', async () => {
    const literal = '100%_完成';
    await mount([{ name: 'name', value: literal }]);
    await selectOperator(0, '不包含');
    expect(onApply).not.toHaveBeenCalled();
    expect(host.textContent).toContain('条件待应用');
    clickButton('应用筛选');
    await flush();
    expect(onApply).toHaveBeenLastCalledWith([
      { name: 'name', operator: 'NOT_CONTAINS', value: literal },
    ]);
    for (const [label, operator] of [
      ['为空（NULL）', 'IS_NULL'],
      ['不为空（NULL）', 'IS_NOT_NULL'],
      ['空字符串', 'IS_EMPTY'],
      ['非空字符串', 'IS_NOT_EMPTY'],
    ] as const) {
      await selectOperator(0, label);
      expect(
        host.querySelector(
          '.master-data-filter__value input, input.master-data-filter__value',
        ),
      ).toBeNull();
      expect(host.textContent).toContain('无需填写值');
      clickButton('应用筛选');
      await flush();
      expect(onApply).toHaveBeenLastCalledWith([{ name: 'name', operator }]);
      expect(host.textContent).not.toContain('条件待应用');
    }
    await selectOperator(0, '等于（=）');
    const input = host.querySelector<HTMLInputElement>(
      '.master-data-filter__value input, input.master-data-filter__value',
    )!;
    expect(input.value).toBe('');
    onApply.mockClear();
    clickButton('应用筛选');
    await flush();
    expect(onApply).not.toHaveBeenCalled();
    expect(host.querySelector('[role="alert"]')).not.toBeNull();
  });

  it('切换字段保留适用操作符，不适用时恢复默认；未知类型默认 NULL', async () => {
    await mount([{ name: 'name', operator: 'NOT_CONTAINS', value: '42' }]);
    await selectField(0, 'amount', 'amount');
    expect(
      host.querySelector('.master-data-filter__operator')?.textContent,
    ).toBe('等于（=）');
    await selectOperator(0, '大于（>）');
    await selectField(0, '日期时间', 'timestamp');
    expect(
      host.querySelector('.master-data-filter__operator')?.textContent,
    ).toBe('大于（>）');
    await selectField(0, '是否有效', 'active');
    expect(
      host.querySelector('.master-data-filter__operator')?.textContent,
    ).toBe('等于（=）');
    await selectField(0, '未知类型', 'unknown');
    expect(
      host.querySelector('.master-data-filter__operator')?.textContent,
    ).toBe('为空（NULL）');
    clickButton('应用筛选');
    await flush();
    expect(onApply).toHaveBeenCalledExactlyOnceWith([
      { name: 'unknown', operator: 'IS_NULL' },
    ]);
  });

  it('类型变化导致原操作符失效时阻止应用，并保留已应用快照', async () => {
    await mount([
      { name: 'id', operator: 'CONTAINS', value: '9007199254740993' },
    ]);
    clickButton('应用筛选');
    await flush();
    expect(onApply).not.toHaveBeenCalled();
    expect(host.querySelector('[role="alert"]')).not.toBeNull();
    expect(applied.value).toEqual([
      { name: 'id', operator: 'CONTAINS', value: '9007199254740993' },
    ]);
  });

  it('字段类型与数据源能力共同决定菜单和初始默认操作符', async () => {
    await mount(
      [{ name: 'name', value: '字面文本' }],
      ['EQ', 'NE', 'IS_NULL', 'IS_NOT_NULL'],
    );
    expect(
      host.querySelector('.master-data-filter__operator')?.textContent,
    ).toBe('等于（=）');
    expect(host.textContent).not.toContain('条件待应用');
    const options = await openOperator(0);
    expect(options.map((option) => option.textContent?.trim())).toEqual([
      '等于（=）',
      '不等于（≠）',
      '为空（NULL）',
      '不为空（NULL）',
    ]);
  });

  it('数据源无适用能力时提示原因并阻止查询，不静默删除限制', async () => {
    await mount(
      [{ name: 'name', operator: 'CONTAINS', value: '限制条件' }],
      [],
    );
    expect(host.textContent).toContain('当前数据源暂未支持所选字段的筛选方式');
    expect(
      host.querySelector('.master-data-filter__operator .ant-select')
        ?.classList,
    ).toContain('ant-select-disabled');
    clickButton('应用筛选');
    await flush();
    expect(onApply).not.toHaveBeenCalled();
    expect(applied.value).toEqual([
      { name: 'name', operator: 'CONTAINS', value: '限制条件' },
    ]);
  });
});
