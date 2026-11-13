<script lang="ts" setup>
import type { KeyRefQuery } from './data';

import type { VxeTableGridOptions } from '#/adapter/vxe-table';
import type { SafetySecurityApi } from '#/api/governance/security';

import { computed, ref } from 'vue';

import { useAccess } from '@vben/access';
import { confirm, Page, useVbenModal } from '@vben/common-ui';
import { CommonStatusEnum } from '@vben/constants';

import { Alert, message, Tag } from 'antdv-next';

import {
  ACTION_ICON,
  TableAction,
  useVbenVxeGrid,
} from '#/adapter/business-vxe-table';
import {
  getKeyCenterStatus,
  getKeyRefList,
  updateKeyRefStatus,
} from '#/api/governance/security';
import { $t } from '#/locales';

import {
  filterKeyRefs,
  KEY_REF_CREATE,
  KEY_REF_UPDATE,
  useGridColumns,
  useGridFormSchema,
} from './data';
import Form from './modules/form.vue';

defineOptions({ name: 'SafetyKeyRef' });

const { hasAccessByCodes } = useAccess();
const canUpdate = computed(() => hasAccessByCodes([KEY_REF_UPDATE]));

const status = ref<SafetySecurityApi.KeyCenterStatus>();

const [FormModal, formModalApi] = useVbenModal({
  connectedComponent: Form,
  destroyOnClose: true,
});

/** 刷新表格 */
function handleRefresh() {
  gridApi.query();
}

/** 新建密钥引用 */
function handleCreate() {
  formModalApi.open();
}

/** 启停密钥引用 */
async function handleStatusChange(
  newStatus: number,
  row: SafetySecurityApi.KeyRef,
): Promise<boolean | undefined> {
  const enable = newStatus === CommonStatusEnum.ENABLE;
  try {
    await confirm(
      enable
        ? `确定启用密钥引用【${row.keyCode}】吗？`
        : `确定停用密钥引用【${row.keyCode}】吗？停用后引用它的加密规则将返回不可逆掩码。`,
    );
  } catch {
    return false;
  }
  await updateKeyRefStatus({ id: row.id, status: newStatus });
  message.success(enable ? '已启用' : '已停用');
  return true;
}

const [Grid, gridApi] = useVbenVxeGrid<SafetySecurityApi.KeyRef>({
  formOptions: {
    schema: useGridFormSchema(),
  },
  gridOptions: {
    columns: useGridColumns(handleStatusChange, canUpdate.value),
    height: 'auto',
    keepSource: true,
    pagerConfig: {
      enabled: false,
    },
    proxyConfig: {
      ajax: {
        query: async (_params, formValues: KeyRefQuery) => {
          const [list, center] = await Promise.all([
            getKeyRefList(),
            getKeyCenterStatus(),
          ]);
          status.value = center;
          return filterKeyRefs(list ?? [], formValues ?? {});
        },
      },
    },
    rowConfig: {
      keyField: 'id',
      isHover: true,
    },
    toolbarConfig: {
      refresh: true,
      search: true,
    },
  } as VxeTableGridOptions<SafetySecurityApi.KeyRef>,
});
</script>

<template>
  <Page auto-content-height>
    <FormModal @success="handleRefresh" />
    <div class="flex h-full flex-col gap-2">
      <Alert
        v-if="status && !status.available"
        type="error"
        show-icon
        message="密钥中心未配置主密钥"
        description="请在部署环境配置 yudao.governance.key-center.master-keys 与 active-version（Base64，至少 32 字节，不得写入代码或数据库）。未配置时字段加密规则返回不可逆掩码。"
      />
      <Alert
        v-if="status && status.inlineSecretRuleCount > 0"
        type="warning"
        show-icon
        :message="`有 ${status.inlineSecretRuleCount} 条加密规则仍在规则中保存了密钥`"
        description="请在“脱敏规则库”中编辑这些规则，改用密钥引用后重新保存；历史密钥会随之从规则当前版本中移除。"
      />
      <Grid
        class="min-h-0 flex-1"
        table-title="密钥引用列表"
        table-title-help="密钥引用只登记名称和编码，数据密钥由主密钥派生，平台不保存、不展示任何密钥材料"
      >
        <template #toolbar-tools>
          <div class="flex items-center gap-2">
            <Tag v-if="status?.available" color="green">
              主密钥版本 {{ status.activeVersion }}
            </Tag>
            <TableAction
              :actions="[
                {
                  label: $t('common.create'),
                  type: 'primary',
                  icon: ACTION_ICON.ADD,
                  auth: [KEY_REF_CREATE],
                  disabled: !status?.available,
                  onClick: handleCreate,
                },
              ]"
            />
          </div>
        </template>
      </Grid>
    </div>
  </Page>
</template>
