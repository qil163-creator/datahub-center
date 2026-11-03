<script lang="ts" setup>
import type { SafetyLevelApi } from '#/api/governance/level';
import type { SafetyPermissionApi } from '#/api/governance/permission';
import type { SystemRoleApi } from '#/api/system/role';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { CommonStatusEnum } from '@vben/constants';
import { IconifyIcon } from '@vben/icons';

import {
  Button,
  Collapse,
  CollapsePanel,
  Empty,
  message,
  Modal,
  Radio,
  RadioGroup,
  Select,
  Spin,
  Tag,
  TextArea,
} from 'antdv-next';

import {
  bindLegacyPermission,
  getRolePermissionConfigs,
} from '#/api/governance/permission';

import {
  fieldPermissionColor,
  fieldPermissionLabel,
  TABLE_PERMISSION_OPTIONS,
  tablePermissionColor,
  tablePermissionLabel,
} from '../data';
import WritePermissionEditor from './write-permission-editor.vue';

defineOptions({ name: 'SafetyPermissionConfigPanel' });

const props = defineProps<{
  configReady: boolean;
  currentBinding?: SafetyPermissionApi.TableNode & {
    databaseId: number | string;
  };
  currentTable?: string;
  hasUnsavedChanges: boolean;
  maskingRules: { id: number; methodCode?: string; ruleName?: string }[];
  roles: SystemRoleApi.Role[];
  securityLevels: SafetyLevelApi.Level[];
}>();

const emit = defineEmits<{
  (e: 'roleChange', roleId?: number | string): void;
  (e: 'bindingVerified' | 'change'): void;
}>();

const configForm = reactive({
  roleId: undefined as number | string | undefined,
  permissionType: 2,
  securityLevelId: undefined as number | undefined,
  writePolicy: null as null | SafetyPermissionApi.WritePolicy,
  writeChangeReason: '',
});

const currentRole = computed(() =>
  props.roles.find((item) => String(item.id) === String(configForm.roleId)),
);

const enabledLevels = computed(() =>
  props.securityLevels.filter(
    (item) => item.status === CommonStatusEnum.ENABLE,
  ),
);

const roleDialogOpen = ref(false);
const rolePermissionLoading = ref(false);
const rolePermissions = ref<SafetyPermissionApi.PermissionConfig[]>([]);
const activeCollapse = ref<string[]>([]);
let requestSeq = 0;
onBeforeUnmount(() => {
  ++requestSeq;
});
watch(
  () => configForm.roleId,
  () => {
    ++requestSeq;
    rolePermissions.value = [];
    rolePermissionLoading.value = false;
    bindingDialogOpen.value = false;
  },
);
const bindingDialogOpen = ref(false);
const bindingLoading = ref(false);
const bindingNote = ref('');
const legacyConfig = ref<SafetyPermissionApi.PermissionConfig>();
const bindingTarget = ref<
  SafetyPermissionApi.TableNode & { databaseId: number | string }
>();

function openLegacyBinding(item: SafetyPermissionApi.PermissionConfig) {
  if (props.hasUnsavedChanges) {
    message.warning('请先保存未完成的配置，再核验绑定历史策略');
    return;
  }
  if (
    !props.currentBinding ||
    !item.legacyUnbound ||
    String(item.databaseId) !== String(props.currentBinding.databaseId)
  ) {
    message.warning('请先在左侧选择同一数据源的精确物理表');
    return;
  }
  legacyConfig.value = item;
  bindingTarget.value = { ...props.currentBinding };
  bindingNote.value = '';
  bindingDialogOpen.value = true;
}

async function confirmLegacyBinding() {
  const item = legacyConfig.value;
  const target = bindingTarget.value;
  const roleId = item?.rolePermissionConfigs?.[0]?.roleId;
  if (!item || !target || roleId === undefined || !bindingNote.value.trim()) {
    message.warning('必须填写核验说明后才能绑定');
    return;
  }
  const seq = requestSeq;
  bindingLoading.value = true;
  try {
    await bindLegacyPermission({
      databaseId: item.databaseId,
      roleId,
      legacyTableName: item.tableName,
      schemaName: target.schemaName,
      tableName: target.tableName,
      tableReference: target.tableReference,
      note: bindingNote.value.trim(),
    });
    if (seq !== requestSeq) return;
    bindingDialogOpen.value = false;
    emit('bindingVerified');
    message.success('历史策略已核验绑定，原限制保持不变');
    await viewRolePermissions();
  } finally {
    bindingLoading.value = false;
  }
}

function handleRoleChange(roleId?: number | string) {
  emit('roleChange', roleId);
}

function handleReset() {
  configForm.roleId = undefined;
  configForm.permissionType = 2;
  configForm.securityLevelId = undefined;
  configForm.writePolicy = null;
  configForm.writeChangeReason = '';
  emit('roleChange', undefined);
}

async function viewRolePermissions() {
  if (!configForm.roleId) {
    return;
  }
  const seq = ++requestSeq;
  roleDialogOpen.value = true;
  rolePermissionLoading.value = true;
  rolePermissions.value = [];
  try {
    const result = await getRolePermissionConfigs(configForm.roleId);
    if (seq !== requestSeq) return;
    rolePermissions.value = result ?? [];
  } finally {
    if (seq === requestSeq) rolePermissionLoading.value = false;
  }
}

function maskingRuleName(id?: number) {
  if (!id) {
    return '无';
  }
  return (
    props.maskingRules.find((item) => item.id === id)?.ruleName ?? `规则 ${id}`
  );
}

function levelName(id?: number) {
  if (!id) {
    return '';
  }
  return props.securityLevels.find((item) => item.id === id)?.safetyName ?? '';
}

function getCurrentRoleTablePermissionConfig() {
  if (!configForm.roleId) {
    return null;
  }
  return {
    roleId: configForm.roleId,
    roleName: currentRole.value?.name || '',
    tablePermission: {
      permissionType: configForm.permissionType,
      securityLevelId: configForm.securityLevelId,
      writePolicy: configForm.writePolicy,
      writeChangeReason: configForm.writeChangeReason,
    },
  };
}

function loadRoleConfig(
  roleId: number | string,
  config?: SafetyPermissionApi.RolePermissionConfig,
) {
  configForm.roleId = roleId;
  configForm.permissionType = config?.tablePermission?.permissionType ?? 2;
  configForm.securityLevelId = config?.tablePermission?.securityLevelId;
  configForm.writePolicy = config?.tablePermission?.writePolicy ?? null;
  configForm.writeChangeReason =
    config?.tablePermission?.writeChangeReason ?? '';
}

function resetToDefault(roleId: number | string) {
  configForm.roleId = roleId;
  configForm.permissionType = 2;
  configForm.securityLevelId = undefined;
  configForm.writePolicy = null;
  configForm.writeChangeReason = '';
}

defineExpose({
  getCurrentRoleTablePermissionConfig,
  loadRoleConfig,
  resetToDefault,
});
</script>

<template>
  <div class="flex h-full min-h-0 flex-col overflow-auto">
    <div class="mb-4 rounded-md bg-muted/40 p-3 text-sm">
      <div class="mb-1 flex gap-2">
        <span class="w-16 shrink-0 text-muted-foreground">当前表</span>
        <span class="truncate">{{ currentTable || '未选择' }}</span>
      </div>
      <div class="flex gap-2">
        <span class="w-16 shrink-0 text-muted-foreground">当前角色</span>
        <span class="truncate">{{ currentRole?.name || '未选择' }}</span>
      </div>
    </div>

    <div class="mb-4">
      <div class="mb-2 text-sm font-medium">选择角色</div>
      <div class="flex gap-2">
        <Select
          v-model:value="configForm.roleId"
          allow-clear
          class="min-w-0 flex-1"
          placeholder="请选择角色"
          show-search
          option-filter-prop="label"
          :options="
            roles.map((item) => ({
              label: item.name,
              value: item.id,
            }))
          "
          @change="handleRoleChange"
        />
        <Button
          :disabled="!configForm.roleId"
          type="primary"
          @click="viewRolePermissions"
        >
          <template #icon><IconifyIcon icon="lucide:shield-check" /></template>
          查看权限
        </Button>
      </div>
    </div>

    <div class="mb-4">
      <div class="mb-3 text-sm font-medium">表权限配置</div>
      <div class="mb-3">
        <div class="mb-2 text-sm text-muted-foreground">安全等级</div>
        <Select
          v-model:value="configForm.securityLevelId"
          allow-clear
          class="w-full"
          :disabled="!configReady"
          placeholder="选择安全等级"
          @change="emit('change')"
          :options="
            enabledLevels.map((item) => ({
              label: item.safetyName,
              value: item.id,
            }))
          "
        />
      </div>
      <div>
        <div class="mb-2 text-sm text-muted-foreground">权限类型</div>
        <RadioGroup
          v-model:value="configForm.permissionType"
          class="flex flex-col gap-2"
          :disabled="!configReady"
          @change="emit('change')"
        >
          <Radio
            v-for="item in TABLE_PERMISSION_OPTIONS"
            :key="item.value"
            :value="item.value"
          >
            {{ item.label }}
          </Radio>
        </RadioGroup>
      </div>
    </div>

    <WritePermissionEditor
      v-if="configReady"
      v-model="configForm.writePolicy"
      v-model:reason="configForm.writeChangeReason"
      :binding="currentBinding"
      :role-id="configForm.roleId"
      @update:model-value="emit('change')"
      @update:reason="emit('change')"
    />

    <Button class="mt-auto" @click="handleReset">重置</Button>

    <Modal
      v-model:open="roleDialogOpen"
      :footer="null"
      title="角色权限配置信息"
      width="840px"
    >
      <div class="mb-4 text-sm">
        <div>角色名称：{{ currentRole?.name || '未知角色' }}</div>
        <div>角色编号：{{ configForm.roleId }}</div>
      </div>
      <Spin :spinning="rolePermissionLoading">
        <Empty
          v-if="rolePermissions.length === 0"
          description="该角色暂无权限配置"
        />
        <Collapse v-else v-model:active-key="activeCollapse">
          <CollapsePanel
            v-for="(item, index) in rolePermissions"
            :key="String(index)"
          >
            <template #header>
              <div class="flex items-center gap-2">
                <IconifyIcon
                  class="size-4 text-blue-500"
                  icon="carbon:data-table"
                />
                <span>{{ item.tableReference || item.tableName }}</span>
                <Tag v-if="item.legacyUnbound" color="error">
                  历史未绑定，消费已阻断
                </Tag>
                <Tag
                  :color="
                    tablePermissionColor(
                      item.rolePermissionConfigs?.[0]?.tablePermission
                        ?.permissionType,
                    )
                  "
                >
                  {{
                    tablePermissionLabel(
                      item.rolePermissionConfigs?.[0]?.tablePermission
                        ?.permissionType,
                    )
                  }}
                </Tag>
                <Tag
                  v-if="
                    levelName(
                      item.rolePermissionConfigs?.[0]?.tablePermission
                        ?.securityLevelId,
                    )
                  "
                >
                  {{
                    levelName(
                      item.rolePermissionConfigs?.[0]?.tablePermission
                        ?.securityLevelId,
                    )
                  }}
                </Tag>
              </div>
            </template>
            <div v-if="item.legacyUnbound" class="mb-3 space-y-2">
              <div class="text-sm">
                数据源 {{ item.databaseId }}：必须人工确认物理
                schema/表和字段，不能自动猜测或覆盖已有策略。
              </div>
              <Button
                v-access:code="'governance:safety-permission:update'"
                @click="openLegacyBinding(item)"
              >
                <template #icon><IconifyIcon icon="lucide:link" /></template>
                核验并绑定到当前精确表
              </Button>
            </div>
            <div
              v-if="item.rolePermissionConfigs?.[0]?.fieldPermissions?.length"
              class="space-y-2"
            >
              <div
                v-for="field in item.rolePermissionConfigs[0].fieldPermissions"
                :key="field.fieldName"
                class="flex items-center justify-between rounded border px-3 py-2 text-sm"
              >
                <span class="font-medium">{{ field.fieldName }}</span>
                <div class="flex items-center gap-2">
                  <Tag :color="fieldPermissionColor(field.permissionType)">
                    {{ fieldPermissionLabel(field.permissionType) }}
                  </Tag>
                  <span class="text-muted-foreground">
                    {{ maskingRuleName(field.maskingRuleId ?? undefined) }}
                  </span>
                </div>
              </div>
            </div>
            <div v-else class="text-sm text-muted-foreground">
              该表暂无字段级权限配置
            </div>
          </CollapsePanel>
        </Collapse>
      </Spin>
    </Modal>
    <Modal
      v-model:open="bindingDialogOpen"
      title="确认历史策略的精确物理绑定"
      :confirm-loading="bindingLoading"
      ok-text="确认核验并绑定"
      @ok="confirmLegacyBinding"
    >
      <div class="mb-3 text-sm">
        历史组：数据源 {{ legacyConfig?.databaseId }} /
        {{ legacyConfig?.tableName }}
      </div>
      <div class="mb-3 text-sm">目标：{{ bindingTarget?.tableReference }}</div>
      <div class="mb-3 text-sm text-red-600">
        请确认实际数据含义、物理 schema
        和全部字段。原表/字段限制、行条件、脱敏规则及状态均保留；冲突和字段不匹配将拒绝绑定。
      </div>
      <TextArea
        v-model:value="bindingNote"
        :maxlength="500"
        :rows="3"
        placeholder="填写核验依据（必填）"
      />
    </Modal>
  </div>
</template>
