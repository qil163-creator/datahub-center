<script setup lang="ts">
import type { SafetyPermissionApi } from '#/api/governance/permission';

import { computed, onBeforeUnmount, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Input,
  message,
  Select,
  Switch,
  TextArea,
} from 'antdv-next';

import {
  getPermissionColumnList,
  preflightWritePermission,
} from '#/api/governance/permission';

import { parseWriteParameters } from '../data';

const props = defineProps<{
  binding?: SafetyPermissionApi.TableNode & { databaseId: number | string };
  roleId?: number | string;
}>();
const policy = defineModel<null | SafetyPermissionApi.WritePolicy>({
  default: null,
});
const reason = defineModel<string>('reason', { default: '' });
const access = useAccessStore();
const columns = ref<SafetyPermissionApi.Column[]>([]);
const loading = ref(false);
const checking = ref(false);
const sql = ref('');
const parameters = ref('[]');
const result = ref<SafetyPermissionApi.WritePreflightResult>();
let selection = 0;
let request = 0;
const options = computed(() =>
  columns.value.map((column) => ({
    label: column.fieldName,
    value: column.fieldName,
  })),
);
const scopes = computed(() => Object.entries(policy.value?.rowScope ?? {}));

watch(
  () => [
    props.binding?.databaseId,
    props.binding?.tableReference,
    props.roleId,
    access.accessToken,
    access.refreshToken,
  ],
  async () => {
    const current = ++selection;
    ++request;
    result.value = undefined;
    checking.value = false;
    columns.value = [];
    const binding = props.binding;
    loading.value = !!binding;
    try {
      if (!binding) return;
      const value = await getPermissionColumnList(binding.databaseId, binding);
      if (current === selection) columns.value = value;
    } finally {
      if (current === selection) loading.value = false;
    }
  },
  { immediate: true },
);
onBeforeUnmount(() => {
  ++selection;
  ++request;
});

watch(
  [sql, parameters, policy],
  () => {
    ++request;
    result.value = undefined;
    checking.value = false;
  },
  { deep: true },
);

function setEnabled(enabled: boolean) {
  policy.value = enabled
    ? {
        operations: [],
        writeColumns: [],
        filterColumns: [],
        rowScope: {},
        unrestrictedRows: false,
      }
    : null;
}
function change<K extends keyof SafetyPermissionApi.WritePolicy>(
  key: K,
  value: SafetyPermissionApi.WritePolicy[K],
) {
  if (policy.value) policy.value = { ...policy.value, [key]: value };
}
function addScope() {
  const key = columns.value.find(
    (column) => !(column.fieldName in (policy.value?.rowScope ?? {})),
  )?.fieldName;
  if (key) change('rowScope', { ...policy.value?.rowScope, [key]: '' });
}
function renameScope(oldKey: string, newKey: string) {
  const value = policy.value?.rowScope[oldKey] ?? null;
  const next = Object.fromEntries(
    Object.entries(policy.value?.rowScope ?? {}).filter(
      ([key]) => key !== oldKey,
    ),
  );
  change('rowScope', { ...next, [newKey]: value });
}
function removeScope(key: string) {
  change(
    'rowScope',
    Object.fromEntries(
      Object.entries(policy.value?.rowScope ?? {}).filter(
        ([name]) => name !== key,
      ),
    ),
  );
}
function scopeValue(key: string, value: null | string) {
  change('rowScope', { ...policy.value?.rowScope, [key]: value });
}
function unrestricted(value: boolean) {
  if (policy.value)
    policy.value = { ...policy.value, unrestrictedRows: value, rowScope: {} };
}
async function preflight() {
  if (!props.binding || !props.roleId || checking.value) return;
  let values: (null | string)[];
  try {
    values = parseWriteParameters(parameters.value);
  } catch {
    message.warning('参数必须为字符串或 null 的 JSON 数组；精确小数请加双引号');
    return;
  }
  const current = ++request;
  checking.value = true;
  result.value = undefined;
  try {
    const response = await preflightWritePermission({
      databaseId: props.binding.databaseId,
      roleId: props.roleId,
      sql: sql.value,
      parameterValues: values,
    });
    if (current === request) result.value = response;
  } finally {
    if (current === request) checking.value = false;
  }
}
</script>

<template>
  <div class="mb-4 space-y-3 rounded border p-3">
    <div class="flex items-center justify-between text-sm font-medium">
      <span>独立 SQL 写授权</span>
      <Switch
        :checked="!!policy"
        :disabled="!roleId || !binding"
        @change="setEnabled"
      />
    </div>
    <Alert
      type="info"
      show-icon
      message="PostgreSQL 单表写入"
      description="读取权限不授予写入能力。每个角色必须独立覆盖操作、写列、过滤列和行范围；历史读写枚举也需要显式配置。更新不能修改范围列。"
    />
    <template v-if="policy">
      <div class="text-sm">允许操作</div>
      <Select
        class="w-full"
        mode="multiple"
        :value="policy.operations"
        :options="
          ['INSERT', 'UPDATE', 'DELETE'].map((value) => ({
            label: value,
            value,
          }))
        "
        @change="change('operations', $event)"
      />
      <div class="text-sm">可写列（INSERT / UPDATE）</div>
      <Select
        class="w-full"
        mode="multiple"
        :loading="loading"
        :value="policy.writeColumns"
        :options="options"
        @change="change('writeColumns', $event)"
      />
      <div class="text-sm">可用于 WHERE 的列</div>
      <Select
        class="w-full"
        mode="multiple"
        :loading="loading"
        :value="policy.filterColumns"
        :options="options"
        @change="change('filterColumns', $event)"
      />
      <div class="flex items-center justify-between text-sm">
        <span>显式允许全部行</span><Switch :checked="policy.unrestrictedRows" @change="unrestricted" />
      </div>
      <template v-if="!policy.unrestrictedRows">
        <div class="text-xs text-muted-foreground">
          范围列同时满足等值条件，例如 district_code =
          370102。值以文本填写，不填 SQL；范围值暂不支持单引号和反斜杠。
        </div>
        <div
          v-for="[key, value] in scopes"
          :key="key"
          class="space-y-1 rounded border p-2"
        >
          <Select
            class="w-full"
            :value="key"
            :options="
              options.map((item) => ({
                ...item,
                disabled: item.value !== key && item.value in policy!.rowScope,
              }))
            "
            @change="renameScope(key, $event)"
          />
          <Input
            :value="value ?? ''"
            :disabled="value === null"
            placeholder="等值文本，例如 370102"
            @update:value="scopeValue(key, $event)"
          />
          <div class="flex items-center justify-between text-xs">
            <span>只允许 NULL
              <Switch
                size="small"
                :checked="value === null"
                @change="scopeValue(key, $event ? null : '')"
/></span><Button size="small" danger @click="removeScope(key)"><template #icon><IconifyIcon icon="lucide:trash-2" /></template>移除</Button>
          </div>
        </div>
        <Button
          size="small"
          :disabled="loading || !columns.length"
          @click="addScope"
          >
<template #icon><IconifyIcon icon="lucide:plus" /></template>
增加范围条件
</Button>
      </template>
    </template>
    <TextArea
      v-model:value="reason"
      :rows="2"
      :maxlength="500"
      placeholder="新增、修改或撤销写授权的依据（5 到 500 字）"
    />
    <div class="border-t pt-3 text-sm font-medium">已保存策略预检</div>
    <div class="text-xs text-muted-foreground">
      先保存配置。预检不执行 SQL；实际调用时会重新校验当前策略。仅支持
      VALUES、简单 SET/WHERE，禁止函数、子查询、JOIN、RETURNING。
    </div>
    <TextArea
      v-model:value="sql"
      :rows="3"
      placeholder="UPDATE public.permit_case SET status = ? WHERE id = ?"
    />
    <TextArea
      v-model:value="parameters"
      :rows="2"
      placeholder="[&quot;已受理&quot;, &quot;10001&quot;]"
    />
    <Button
      v-access:code="'governance:safety-permission:write-preflight'"
      :loading="checking"
      :disabled="!binding || !roleId || !sql.trim()"
      @click="preflight"
      >
<template #icon><IconifyIcon icon="lucide:shield-check" /></template>
预检写授权
</Button>
    <template v-if="result">
      <Alert
        type="success"
        show-icon
        :message="`${result.operation} 授权通过 · ${result.schemaName}.${result.tableName}`"
      />
      <TextArea :value="result.sql" readonly :rows="5" />
      <div class="break-all text-xs text-muted-foreground">
        绑定参数 {{ result.parameterCount }} 个 · 数据源修订
        {{ result.configurationRevision }}
      </div>
    </template>
  </div>
</template>
