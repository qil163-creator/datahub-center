<!-- add by 星语：参考 vben2 的方式，增加 TableAction 组件 -->
<script setup lang="ts">
import type { PropType } from 'vue';

import type { ActionItem, PopConfirm } from './typing';

import { computed, ref, unref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { $t } from '@vben/locales';
import { isBoolean, isFunction } from '@vben/utils';

import {
  Button,
  Dropdown,
  Menu,
  MenuItem,
  Popconfirm,
  Space,
  Tooltip,
} from 'antdv-next';

const props = defineProps({
  actions: {
    type: Array as PropType<ActionItem[]>,
    default() {
      return [];
    },
  },
  dropDownActions: {
    type: Array as PropType<ActionItem[]>,
    default() {
      return [];
    },
  },
  divider: {
    type: Boolean,
    default: true,
  },
});

const { hasAccessByCodes } = useAccess();
const dropdownOpen = ref(false);
const dropdownConfirmKey = ref<string>();

/** 检查是否显示 */
function isIfShow(action: ActionItem): boolean {
  const ifShow = action.ifShow;
  let isIfShow = true;
  if (isBoolean(ifShow)) {
    isIfShow = ifShow;
  }
  if (isFunction(ifShow)) {
    isIfShow = ifShow(action);
  }
  if (isIfShow) {
    isIfShow =
      hasAccessByCodes(action.auth || []) || (action.auth || []).length === 0;
  }
  return isIfShow;
}

/** 处理按钮 actions */
const getActions = computed(() => {
  const actions = props.actions || [];
  return actions.filter((action: ActionItem) => isIfShow(action));
});

/** 处理下拉菜单 actions */
const getDropdownList = computed(() => {
  const dropDownActions = props.dropDownActions || [];
  return dropDownActions.filter((action: ActionItem) => isIfShow(action));
});

/** Space 组件的 size */
const spaceSize = computed(() => {
  const actions = unref(getActions);
  return actions?.some((item: ActionItem) => item.type === 'link') ? 0 : 8;
});

function getDropdownPopupContainer() {
  return document.body;
}

/** 获取 PopConfirm 属性 */
function getPopConfirmProps(popConfirm: PopConfirm) {
  if (!popConfirm) return {};

  const attrs: Record<string, any> = {};

  // 复制基本属性，排除函数
  Object.keys(popConfirm).forEach((key) => {
    if (key !== 'confirm' && key !== 'cancel' && key !== 'icon') {
      attrs[key] = popConfirm[key as keyof PopConfirm];
    }
  });

  // 单独处理事件函数
  if (popConfirm.confirm && isFunction(popConfirm.confirm)) {
    attrs.onConfirm = popConfirm.confirm;
  }
  if (popConfirm.cancel && isFunction(popConfirm.cancel)) {
    attrs.onCancel = popConfirm.cancel;
  }

  return attrs;
}

/** 获取 Button 属性 */
function getButtonProps(action: ActionItem) {
  return {
    type: action.type || 'link',
    danger: action.danger || false,
    disabled: action.disabled,
    loading: action.loading,
    size: action.size,
  };
}

/** 获取 Tooltip 属性 */
function getTooltipProps(tooltip: any | string) {
  if (!tooltip) return {};
  return typeof tooltip === 'string' ? { title: tooltip } : { ...tooltip };
}

/** 处理菜单点击 */
function handleMenuClick(e: any) {
  const action = getDropdownList.value[e.key];
  if (action && action.onClick && isFunction(action.onClick)) {
    action.onClick();
  }
}

/** 生成稳定的 key */
function getActionKey(action: ActionItem, index: number) {
  return `${action.label || ''}-${action.type || ''}-${index}`;
}

function handleDropdownOpenChange(open: boolean) {
  // 确认期间保留菜单锚点，避免菜单点击或鼠标移出导致确认框失去定位。
  if (open || dropdownConfirmKey.value === undefined) {
    dropdownOpen.value = open;
  }
}

function handleDropdownConfirmOpenChange(open: boolean, key: string) {
  if (open) {
    dropdownConfirmKey.value = key;
    dropdownOpen.value = true;
  } else if (dropdownConfirmKey.value === key) {
    dropdownConfirmKey.value = undefined;
    dropdownOpen.value = false;
  }
}

/** 处理按钮点击 */
function handleButtonClick(action: ActionItem) {
  if (action.onClick && isFunction(action.onClick)) {
    action.onClick();
  }
}

watch(
  getDropdownList,
  (actions) => {
    if (
      dropdownConfirmKey.value !== undefined &&
      !actions.some(
        (action, index) =>
          action.popConfirm &&
          !action.disabled &&
          !action.popConfirm.disabled &&
          getActionKey(action, index) === dropdownConfirmKey.value,
      )
    ) {
      dropdownConfirmKey.value = undefined;
      dropdownOpen.value = false;
    }
  },
  { deep: true },
);
</script>

<template>
  <div class="table-actions">
    <Space :size="spaceSize">
      <template
        v-for="(action, index) in getActions"
        :key="getActionKey(action, index)"
      >
        <Popconfirm
          v-if="action.popConfirm"
          v-bind="getPopConfirmProps(action.popConfirm)"
        >
          <template v-if="action.popConfirm.icon" #icon>
            <IconifyIcon :icon="action.popConfirm.icon" />
          </template>
          <Tooltip v-bind="getTooltipProps(action.tooltip)">
            <Button v-bind="getButtonProps(action)">
              <template v-if="action.icon" #icon>
                <IconifyIcon :icon="action.icon" />
              </template>
              {{ action.label }}
            </Button>
          </Tooltip>
        </Popconfirm>
        <Tooltip v-else v-bind="getTooltipProps(action.tooltip)">
          <Button
            v-bind="getButtonProps(action)"
            @click="handleButtonClick(action)"
          >
            <template v-if="action.icon" #icon>
              <IconifyIcon :icon="action.icon" />
            </template>
            {{ action.label }}
          </Button>
        </Tooltip>
      </template>
    </Space>

    <Dropdown
      v-if="getDropdownList.length > 0"
      :open="dropdownOpen"
      :trigger="['hover']"
      placement="bottomRight"
      :get-popup-container="getDropdownPopupContainer"
      @open-change="handleDropdownOpenChange"
    >
      <slot name="more">
        <Button type="link">
          {{ $t('page.action.more') }}
          <template #icon>
            <IconifyIcon icon="lucide:ellipsis-vertical" />
          </template>
        </Button>
      </slot>
      <template #popupRender>
        <Menu class="table-action-more-menu">
          <MenuItem
            v-for="(action, index) in getDropdownList"
            :key="getActionKey(action, index)"
            :disabled="action.disabled"
            @click="
              action.popConfirm
                ? !action.popConfirm.disabled &&
                  handleDropdownConfirmOpenChange(
                    true,
                    getActionKey(action, index),
                  )
                : handleMenuClick({ key: index })
            "
          >
            <template v-if="action.popConfirm">
              <Popconfirm
                v-bind="getPopConfirmProps(action.popConfirm)"
                :open="dropdownConfirmKey === getActionKey(action, index)"
                :get-popup-container="getDropdownPopupContainer"
                @open-change="
                  (open) =>
                    handleDropdownConfirmOpenChange(
                      open,
                      getActionKey(action, index),
                    )
                "
              >
                <template v-if="action.popConfirm.icon" #icon>
                  <IconifyIcon :icon="action.popConfirm.icon" />
                </template>
                <div
                  class="table-action-more-item"
                  @click.stop
                  :class="
                    action.disabled === true
                      ? 'cursor-not-allowed text-gray-300'
                      : ''
                  "
                >
                  <IconifyIcon v-if="action.icon" :icon="action.icon" />
                  <span>{{ action.label }}</span>
                </div>
              </Popconfirm>
            </template>
            <template v-else>
              <div
                class="table-action-more-item"
                :class="
                  action.disabled === true
                    ? 'cursor-not-allowed text-gray-300'
                    : ''
                "
              >
                <IconifyIcon v-if="action.icon" :icon="action.icon" />
                <span>{{ action.label }}</span>
              </div>
            </template>
          </MenuItem>
        </Menu>
      </template>
    </Dropdown>
  </div>
</template>

<style lang="scss">
.table-actions {
  overflow: visible;

  .ant-btn-link {
    padding: 4px;
    margin-left: 0;
  }

  .ant-btn > .iconify + span,
  .ant-btn > span + .iconify {
    margin-inline-start: 4px;
  }

  .iconify {
    display: inline-flex;
    align-items: center;
    width: 1em;
    height: 1em;
    font-style: normal;
    line-height: 0;
    vertical-align: -0.125em;
    color: inherit;
    text-align: center;
    text-transform: none;
    text-rendering: optimizelegibility;
    -webkit-font-smoothing: antialiased;
    -moz-osx-font-smoothing: grayscale;
  }
}

.ant-popconfirm {
  .ant-popconfirm-buttons {
    .ant-btn {
      margin-inline-start: 4px !important;
    }
  }
}

.table-action-more-menu {
  min-width: 128px;
  padding: 6px 0;

  .ant-menu-item,
  .ant-dropdown-menu-item {
    display: flex;
    flex-direction: row;
    align-items: center;
    height: auto;
    padding: 6px 12px;
    line-height: 22px;
  }
}

.table-action-more-item {
  display: inline-flex;
  flex-direction: row;
  gap: 6px;
  align-items: center;
  white-space: nowrap;

  .iconify {
    display: inline-flex;
    flex-shrink: 0;
    width: 1em;
    height: 1em;
    line-height: 1;
  }
}
</style>
