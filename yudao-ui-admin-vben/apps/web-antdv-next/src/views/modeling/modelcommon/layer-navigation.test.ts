import { describe, expect, it } from 'vitest';

import { prepareLayerNavigation } from './layer-navigation';

describe('modeling layer navigation', () => {
  it('keeps large decimal IDs losslessly and makes only layers selectable', () => {
    const result = prepareLayerNavigation([
      { id: '9223372036854775806', nodeType: 'CATEGORY', parentId: 0 },
      {
        id: '9223372036854775807',
        nodeType: 'LAYER',
        parentId: '9223372036854775806',
      },
    ]);
    expect(result.map((item) => item.id)).toEqual([
      '9223372036854775806',
      '9223372036854775807',
    ]);
    expect(result.map((item) => item.selectable)).toEqual([false, true]);
    expect(result[1]?.parentId).toBe('9223372036854775806');
  });

  it('keeps ancestors when numeric and string IDs are mixed in a flat API list', () => {
    const result = prepareLayerNavigation(
      [
        { id: 1, layerName: '公共层', nodeType: 'CATEGORY' },
        { id: 2, layerCode: 'DWD', nodeType: 'LAYER', parentId: '1' },
        { id: 3, layerCode: 'ADS', nodeType: 'LAYER', parentId: 1 },
      ],
      ' dWd ',
    );
    expect(result.map((item) => item.id)).toEqual(['1', '2']);
  });

  it('does not mutate API records and returns no nodes for unmatched searches', () => {
    const items = [
      { id: 1, parentId: 0, layerName: '公共层', nodeType: 'LAYER' },
    ];
    prepareLayerNavigation(items);
    expect(items[0]).toEqual({
      id: 1,
      parentId: 0,
      layerName: '公共层',
      nodeType: 'LAYER',
    });
    expect(prepareLayerNavigation(items, 'not-found')).toEqual([]);
  });

  it('hides 贴源层 category and its subtree from model navigation', () => {
    const result = prepareLayerNavigation([
      { id: 10, layerName: '贴源层', nodeType: 'CATEGORY', parentId: 0 },
      {
        id: 11,
        layerName: '操作数据层 ODS',
        layerCode: 'ODS',
        nodeType: 'LAYER',
        parentId: 10,
      },
      { id: 20, layerName: '公共层', nodeType: 'CATEGORY', parentId: 0 },
      {
        id: 21,
        layerCode: 'DWD',
        layerName: '明细层',
        nodeType: 'LAYER',
        parentId: 20,
      },
    ]);
    expect(result.map((item) => item.id)).toEqual(['20', '21']);
  });

  it('hides top-level ODS / SOURCE layers without a parent category', () => {
    const result = prepareLayerNavigation([
      {
        id: 1,
        layerCode: 'ODS',
        layerName: '操作数据层',
        nodeType: 'LAYER',
        parentId: 0,
      },
      {
        id: 2,
        layerCode: 'SRC',
        defaultTableType: 'SOURCE',
        layerName: '贴源表层',
        nodeType: 'LAYER',
        parentId: 0,
      },
      {
        id: 3,
        layerCode: 'DWD',
        layerName: '明细层',
        nodeType: 'LAYER',
        parentId: 0,
      },
    ]);
    expect(result.map((item) => item.id)).toEqual(['3']);
  });
});