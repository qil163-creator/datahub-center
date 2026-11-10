<script setup lang="ts">
import type { DataforgeEtlApi } from '#/api/dataforge/etl';
import type {
  EtlEnvironment,
  EtlEnvironmentBinding,
} from '#/api/dataforge/etl/environment';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { useAccess } from '@vben/access';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore } from '@vben/stores';

import {
  Alert,
  Button,
  Checkbox,
  Drawer,
  Input,
  Modal,
  Popconfirm,
  Select,
  Table,
  Tag,
} from 'antdv-next';

import { getDatabaseSimpleList } from '#/api/dataforge/database';
import {
  deleteEtlEnvironment,
  deleteEtlEnvironmentBinding,
  getEtlEnvironmentBindings,
  getEtlEnvironments,
  saveEtlEnvironment,
  saveEtlEnvironmentBinding,
} from '#/api/dataforge/etl/environment';

const props = defineProps<{ disabled?: boolean }>();
const value = defineModel<DataforgeEtlApi.Id>('environmentId');
const access = useAccessStore();
const { hasAccessByCodes } = useAccess();
const canSave = computed(
  () =>
    !props.disabled &&
    hasAccessByCodes(['dataforge:etl:save']) &&
    hasAccessByCodes(['dataforge:etl:query']),
);
const environments = ref<EtlEnvironment[]>([]);
const bindings = ref<EtlEnvironmentBinding[]>([]);
const databases = ref<{ label: string; value: string }[]>([]);
const loading = ref(false);
const bindingLoading = ref(false);
const error = ref('');
const visible = ref(false);
const selected = ref<DataforgeEtlApi.Id>();
const editing = ref<'binding' | 'environment'>();
const form = ref<Record<string, any>>({});
const formError = ref('');
const busy = ref(false);
let bindingSequence = 0;
let databaseSequence = 0;
let mutationSequence = 0;
let sequence = 0;
const options = computed(() =>
  environments.value.map((item) => ({
    value: String(item.id),
    label: `${item.name} (${item.code})${item.enabled ? '' : ' · 已停用'}`,
    disabled: !item.enabled,
  })),
);
const envColumns = [
  { title: '环境', dataIndex: 'name', key: 'name' },
  { title: '标识', dataIndex: 'code', key: 'code' },
  { title: '状态', key: 'status' },
  { title: '操作', key: 'actions', width: 330, fixed: 'right' as const },
];
const bindingColumns = [
  { title: '逻辑连接', dataIndex: 'logicalKey', key: 'logicalKey' },
  { title: '数据源 ID', dataIndex: 'databaseId', key: 'databaseId' },
  { title: 'Schema', dataIndex: 'schemaName', key: 'schemaName' },
  { title: '操作', key: 'actions', width: 230, fixed: 'right' as const },
];
async function load() {
  const current = ++sequence;
  loading.value = true;
  error.value = '';
  try {
    const result = await getEtlEnvironments();
    if (current === sequence) environments.value = result;
  } catch {
    // 请求客户端已显示错误。
  } finally {
    if (current === sequence) loading.value = false;
  }
}
async function loadBindings(id: DataforgeEtlApi.Id) {
  const current = ++bindingSequence;
  selected.value = id;
  bindings.value = [];
  bindingLoading.value = true;
  try {
    const result = await getEtlEnvironmentBindings(id);
    if (current === bindingSequence) bindings.value = result;
  } catch {
    // 请求客户端已显示错误。
  } finally {
    if (current === bindingSequence) bindingLoading.value = false;
  }
}
async function open() {
  visible.value = true;
  await load();
}
function closeEdit() {
  ++databaseSequence;
  ++mutationSequence;
  editing.value = undefined;
  form.value = {};
  formError.value = '';
  busy.value = false;
}
function close() {
  ++bindingSequence;
  ++databaseSequence;
  visible.value = false;
  selected.value = undefined;
  bindings.value = [];
  databases.value = [];
  bindingLoading.value = false;
  closeEdit();
}
async function edit(
  kind: 'binding' | 'environment',
  row?: EtlEnvironment | EtlEnvironmentBinding,
) {
  if (!canSave.value || busy.value) return;
  closeEdit();
  editing.value = kind;
  const defaults =
    kind === 'environment'
      ? { code: '', name: '', enabled: true }
      : { environmentId: selected.value, logicalKey: '', schemaName: '' };
  form.value = row ? { ...row } : defaults;
  if (kind !== 'binding') return;
  const current = ++databaseSequence;
  try {
    const result = await getDatabaseSimpleList();
    if (current === databaseSequence)
      databases.value = result.map((item) => ({
        value: String(item.id),
        label: item.name ?? String(item.id),
      }));
  } catch {
    // 请求客户端已显示错误。
  }
}
async function save() {
  if (!canSave.value || busy.value || !editing.value) return;
  const data = { ...form.value };
  if (
    editing.value === 'environment'
      ? !/^[a-z][a-z0-9_-]{0,63}$/.test(data.code ?? '') || !data.name?.trim()
      : !/^[A-Za-z][A-Za-z0-9_.-]{0,63}$/.test(data.logicalKey ?? '') ||
        !data.databaseId ||
        !data.schemaName?.trim()
  ) {
    formError.value =
      '请完整填写名称与有效标识；环境标识以小写字母开头，逻辑连接以字母开头。';
    return;
  }
  const current = ++mutationSequence;
  const kind = editing.value;
  busy.value = true;
  formError.value = '';
  try {
    await (kind === 'environment'
      ? saveEtlEnvironment(data as Parameters<typeof saveEtlEnvironment>[0])
      : saveEtlEnvironmentBinding(
          data as Parameters<typeof saveEtlEnvironmentBinding>[0],
        ));
    if (current !== mutationSequence) return;
    closeEdit();
    await load();
    if (selected.value !== undefined) await loadBindings(selected.value);
  } catch {
    if (current === mutationSequence)
      formError.value =
        '保存未确认或版本已变化，请关闭配置并刷新列表核对；重新编辑会读取最新修订号。';
  } finally {
    if (current === mutationSequence) busy.value = false;
  }
}
async function remove(
  kind: 'binding' | 'environment',
  row: EtlEnvironment | EtlEnvironmentBinding,
) {
  if (!canSave.value || busy.value) return;
  const current = ++mutationSequence;
  busy.value = true;
  error.value = '';
  try {
    await (kind === 'environment'
      ? deleteEtlEnvironment(row.id, row.revision)
      : deleteEtlEnvironmentBinding(row.id, row.revision));
    if (current !== mutationSequence) return;
    if (kind === 'environment' && String(selected.value) === String(row.id)) {
      selected.value = undefined;
      bindings.value = [];
      ++bindingSequence;
    }
    await load();
    if (selected.value !== undefined) await loadBindings(selected.value);
  } catch {
    if (current === mutationSequence)
      error.value = '删除未确认、对象被引用或修订号已变化，请刷新核对。';
  } finally {
    if (current === mutationSequence) busy.value = false;
  }
}
watch(
  () => [access.accessToken, access.refreshToken],
  () => {
    ++sequence;
    environments.value = [];
    loading.value = false;
    close();
    void load();
  },
);
onMounted(load);
onBeforeUnmount(() => {
  ++sequence;
  close();
});
</script>
<template>
  <div class="form-item">
    <label class="form-item__label">运行环境（逻辑连接节点必选）</label>
    <div class="flex gap-2">
      <Select
        :value="value == null ? undefined : String(value)"
        :options="options"
        :loading="loading"
        :disabled="disabled"
        class="min-w-0 flex-1"
        allow-clear
        placeholder="未绑定环境时使用节点直接数据源"
        @change="(id) => (value = id == null ? undefined : String(id))"
      />
      <Button @click="open">
        <template #icon><IconifyIcon icon="lucide:settings" /></template
        >管理环境
      </Button>
    </div>
    <Alert v-if="error" type="error" :message="error" />
  </div>
  <Drawer
    :open="visible"
    title="ETL 环境连接"
    size="960"
    :push="false"
    @close="close"
  >
    <Alert
      class="mb-3"
      type="info"
      show-icon
      message="平台共享环境与逻辑连接。发布冻结连接和 Schema；修改绑定后旧发布运行会被阻止，需重新发布。这里不保存连接密码，发布和运行时会重新检查连接配置与环境绑定。"
    />
    <div class="mb-3 flex gap-2">
      <Button :loading="loading" @click="load">
        <template #icon><IconifyIcon icon="lucide:refresh-cw" /></template
        >刷新环境 </Button
      ><Button v-if="canSave" :disabled="busy" @click="edit('environment')">
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        新增环境
      </Button>
    </div>
    <Alert v-if="error" class="mb-3" type="error" :message="error" />
    <Table
      :scroll="{ x: 'max-content' }"
      :columns="envColumns"
      :data-source="environments"
      :row-key="(row: EtlEnvironment) => String(row.id)"
      :pagination="false"
      :loading="loading"
    >
      <template #bodyCell="{ column, record }">
        <Tag v-if="column.key === 'status'">
          {{ record.enabled ? '启用' : '停用' }}
        </Tag>
        <template v-else-if="column.key === 'actions'">
          <Button
            size="small"
            :disabled="busy"
            @click="loadBindings(record.id)"
          >
            <template #icon><IconifyIcon icon="lucide:link" /></template>
            连接绑定 </Button
          ><Button
            v-if="canSave"
            size="small"
            :disabled="busy"
            @click="edit('environment', record)"
          >
            <template #icon><IconifyIcon icon="lucide:edit" /></template>
            编辑环境 </Button
          ><Popconfirm
            v-if="canSave"
            title="删除此环境？被流程引用的环境不能删除。"
            @confirm="remove('environment', record)"
          >
            <Button size="small" danger :disabled="busy">
              <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
              删除环境
            </Button>
          </Popconfirm>
        </template>
      </template>
    </Table>
    <section v-if="selected !== undefined" class="mt-5">
      <p class="mb-2">
        环境
        {{
          environments.find((item) => String(item.id) === String(selected))
            ?.name ?? selected
        }}
        的连接绑定
      </p>
      <Button
        v-if="canSave"
        class="mb-3"
        :disabled="busy"
        @click="edit('binding')"
      >
        <template #icon><IconifyIcon icon="lucide:plus" /></template>
        新增连接
      </Button>
      <Table
        :scroll="{ x: 'max-content' }"
        :columns="bindingColumns"
        :data-source="bindings"
        :row-key="(row: EtlEnvironmentBinding) => String(row.id)"
        :pagination="false"
        :loading="bindingLoading"
      >
        <template #bodyCell="{ column, record }">
          <template v-if="column.key === 'actions' && canSave">
            <Button
              size="small"
              :disabled="busy"
              @click="edit('binding', record)"
            >
              <template #icon><IconifyIcon icon="lucide:edit" /></template>
              编辑连接 </Button
            ><Popconfirm
              title="删除绑定会阻止引用此修订的旧发布运行，确认删除？"
              @confirm="remove('binding', record)"
            >
              <Button size="small" danger :disabled="busy">
                <template #icon><IconifyIcon icon="lucide:trash-2" /></template>
                删除连接
              </Button>
            </Popconfirm>
          </template>
        </template>
      </Table>
    </section>
  </Drawer>
  <Modal
    :open="!!editing"
    :title="editing === 'environment' ? '环境配置' : '环境连接绑定'"
    :confirm-loading="busy"
    :closable="!busy"
    :mask-closable="false"
    :cancel-button-props="{ disabled: busy }"
    @ok="save"
    @cancel="closeEdit"
  >
    <div class="flex flex-col gap-3" :inert="busy">
      <template v-if="editing === 'environment'">
        <Input
          v-model:value="form.code"
          :maxlength="64"
          placeholder="环境标识，如 dev / prod"
        /><Input
          v-model:value="form.name"
          :maxlength="100"
          placeholder="环境名称"
        /><Checkbox v-model:checked="form.enabled">启用环境</Checkbox>
      </template>
      <template v-else>
        <Input
          v-model:value="form.logicalKey"
          :maxlength="64"
          placeholder="逻辑连接，如 warehouse"
        /><Select
          :value="form.databaseId == null ? undefined : String(form.databaseId)"
          :options="databases"
          show-search
          option-filter-prop="label"
          placeholder="物理数据源"
          @change="(id) => (form.databaseId = id)"
        /><Input
          v-model:value="form.schemaName"
          :maxlength="128"
          placeholder="物理 Schema（必填）"
        />
      </template>
      <Alert v-if="formError" type="error" :message="formError" />
    </div>
  </Modal>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
