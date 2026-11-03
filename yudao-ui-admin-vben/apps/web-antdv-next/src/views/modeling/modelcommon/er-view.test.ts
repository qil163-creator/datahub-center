import { describe, expect, it } from 'vitest';

import { formatErFieldType, prepareErLayers } from './er-view';

describe('eR view state', () => {
  const items = [
    {
      id: '9007199254740993',
      parentId: 0,
      nodeType: 'CATEGORY',
      layerName: '主题',
    },
    {
      id: '9007199254740995',
      parentId: '9007199254740993',
      nodeType: 'LAYER',
      layerCode: 'DWD',
      layerName: '明细',
    },
    {
      id: '9007199254740997',
      parentId: '9007199254740993',
      nodeType: 'LAYER',
      layerCode: 'DWS',
      layerName: '汇总',
    },
  ];

  it('loads selectable layers and preserves a large route ID', () => {
    const state = prepareErLayers(items, '9007199254740997');
    expect(state.layerId).toBe('9007199254740997');
    expect(state.options[0]?.selectable).toBe(false);
    expect(state.options[1]?.name).toBe('DWD 明细');
  });

  it('falls back when route points to a category or missing layer', () => {
    expect(prepareErLayers(items, items[0]?.id).layerId).toBe(items[1]?.id);
    expect(prepareErLayers(items, 'missing').layerId).toBe(items[1]?.id);
    expect(prepareErLayers([], undefined).layerId).toBeUndefined();
  });

  it('renders decimal precision and scale, including zero, without guessing legacy values', () => {
    expect(
      formatErFieldType({
        dataType: 'DECIMAL',
        dataLength: 30,
        dataPrecision: 18,
        dataScale: 0,
      }),
    ).toBe('DECIMAL(18,0)');
    expect(
      formatErFieldType({
        dataType: 'numeric',
        dataPrecision: 18,
        dataScale: 4,
      }),
    ).toBe('numeric(18,4)');
    expect(
      formatErFieldType({
        dataType: 'decimal',
        dataLength: 18,
        dataPrecision: 2,
      }),
    ).toBe('decimal');
    expect(formatErFieldType({ dataType: 'varchar', dataLength: 255 })).toBe(
      'varchar(255)',
    );
    expect(
      formatErFieldType({
        dataType: 'decimal(12,2)',
        dataPrecision: 12,
        dataScale: 2,
      }),
    ).toBe('decimal(12,2)');
  });
});
