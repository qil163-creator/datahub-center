import type { VNode } from 'vue';

import { h } from 'vue';

import { IconifyIcon } from '@vben/icons';

import { Tooltip } from 'antdv-next';

/**
 * 被动态脱敏的结果列（后端 DatabasePreviewRespDTO.MaskedColumn）。
 * 原值不会出现在结果行中，这里只用于在表头标注。
 */
export interface MaskedColumnInfo {
  /** 结果行中的列键 */
  name: string;
  /** RULE 命中分类规则；UNCLASSIFIED 敏感未分类；UNTRACEABLE 来源无法追溯；UNVERIFIED 策略无法核验 */
  reason?: string;
  /** 命中的分类名称 */
  categoryNames?: string[];
}

const REASON_TEXT: Record<string, string> = {
  RULE: '命中分类脱敏规则',
  UNCLASSIFIED: '字段已标记敏感但未分类，已整体打码',
  UNTRACEABLE: '无法追溯来源字段且涉及敏感表，已整体打码',
  UNVERIFIED: '脱敏策略暂时无法核验，已整体打码',
};

/** 按列键建立索引；忽略空项。 */
export function indexMaskedColumns(
  list?: MaskedColumnInfo[] | null,
): Map<string, MaskedColumnInfo> {
  const index = new Map<string, MaskedColumnInfo>();
  for (const item of list ?? []) {
    if (item?.name) index.set(item.name, item);
  }
  return index;
}

/** 表头提示文字。 */
export function maskedColumnHint(info: MaskedColumnInfo): string {
  const categories = (info.categoryNames ?? []).filter(Boolean);
  const reason =
    (info.reason && REASON_TEXT[info.reason]) || '已按数据安全策略脱敏';
  return categories.length > 0
    ? `已脱敏（${categories.join('、')}）：${reason}`
    : `已脱敏：${reason}`;
}

/**
 * 表头：被脱敏的列加锁形标记和提示；未脱敏的列原样返回文字。
 * 适用于 antdv-next Table 的 column.title 与 vxe-grid 的 header 插槽。
 */
export function renderMaskedColumnTitle(
  title: string,
  info?: MaskedColumnInfo,
): string | VNode {
  if (!info) return title;
  return h(
    Tooltip,
    { title: maskedColumnHint(info) },
    {
      default: () =>
        h(
          'span',
          {
            class: 'inline-flex items-center gap-1',
            'data-masked-column': info.name,
          },
          [
            h(IconifyIcon, {
              class: 'text-warning shrink-0',
              icon: 'carbon:locked',
              'aria-label': '已脱敏',
            }),
            h('span', title),
          ],
        ),
    },
  );
}
