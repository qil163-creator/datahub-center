<script lang="ts" setup>
import type { SafetyWhitelistWorkbenchApi as Api } from '#/api/governance/whitelist/workbench';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import {
  Alert,
  Button,
  Form,
  FormItem,
  Input,
  Modal,
  RadioGroup,
  Spin,
  Tag,
  TreeSelect,
} from 'antdv-next';

import { RangePicker } from '#/adapter/component/date-picker';
import {
  getWhitelistWorkbench,
  getWhitelistWorkbenchOptions,
  saveWhitelistWorkbench,
} from '#/api/governance/whitelist/workbench';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';
import { categoryTime } from '#/views/governance/category/reference-data';
import { useCategorySession } from '#/views/governance/category/use-session';

import {
  newWhitelist,
  whitelistCategoryTree,
  whitelistDisplayId,
  whitelistError,
  whitelistPeriod,
  whitelistUserNames,
  whitelistVersion,
} from '../reference-data';
import UserPicker from './workbench-users.vue';

const props = defineProps<{ id?: Api.Id; readonly?: boolean }>();
const emit = defineEmits<{ close: []; success: [] }>();
const { identity, can } = useCategorySession();
const editing = computed(() => !!parseResourceId(props.id));
const allowed = computed(() => {
  if (props.readonly) return can('query', 'whitelist');
  return can(editing.value ? 'update' : 'create', 'whitelist');
});
const form = reactive<Api.Save>(newWhitelist());
const options = ref<Api.Options>({ directories: [], categories: [] });
const categoryTree = computed(() => whitelistCategoryTree(options.value));
const row = ref<Api.Row>();
const users = ref<Api.User[]>([]);
const dates = ref<[string, string]>();
const picker = ref(false);
const loading = ref(false);
const saving = ref(false);
const ready = ref(false);
const error = ref('');
const guard = createLatestWins();
async function load() {
  guard.invalidate();
  ready.value = false;
  row.value = undefined;
  users.value = [];
  dates.value = undefined;
  picker.value = false;
  error.value = '';
  Object.assign(form, newWhitelist(), { id: undefined, revision: undefined });
  if (!allowed.value) return;
  const ticket = guard.next();
  loading.value = true;
  try {
    const [context, data] = await Promise.all([
      getWhitelistWorkbenchOptions(),
      editing.value
        ? getWhitelistWorkbench(props.id!)
        : Promise.resolve(undefined),
    ]);
    if (!ticket.isCurrent()) return;
    if (
      data &&
      (resourceIdKey(data.id) !== resourceIdKey(props.id) ||
        !whitelistVersion(data))
    )
      throw new Error('WHITELIST_IDENTITY');
    options.value = context;
    row.value = data;
    if (data) {
      Object.assign(form, data, {
        categoryId: resourceIdKey(data.categoryId),
        userIds: [...data.userIds],
      });
      users.value = data.users.map((item) => ({ ...item }));
      dates.value = [data.startTime, data.endTime];
    }
    ready.value = true;
  } catch {
    if (ticket.isCurrent())
      error.value = '白名单或分类读取失败，请关闭后刷新重试。';
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
async function save() {
  if (!ready.value || saving.value || props.readonly || !allowed.value) return;
  const payload: Api.Save = {
    id: row.value?.id,
    revision: row.value?.revision,
    name: form.name.trim(),
    categoryId: row.value?.categoryId ?? form.categoryId,
    effectiveCategory: '1',
    userIds: [...form.userIds],
    startTime: dates.value?.[0] || '',
    endTime: dates.value?.[1] || '',
    status: form.status,
    description: form.description,
  };
  const invalid = whitelistError(payload);
  if (invalid) {
    error.value = invalid;
    return;
  }
  if (editing.value && !whitelistVersion(row.value)) {
    error.value = '请刷新白名单状态后重试。';
    return;
  }
  const category = options.value.categories.find(
    (item) => resourceIdKey(item.id) === resourceIdKey(payload.categoryId),
  );
  if (!category || (!editing.value && category.whitelistConfigured)) {
    error.value = '请选择尚未配置白名单的有效数据分类。';
    return;
  }
  const ticket = guard.next();
  saving.value = true;
  error.value = '';
  try {
    await saveWhitelistWorkbench(payload);
    if (!ticket.isCurrent()) return;
    emit('success');
    emit('close');
  } catch {
    if (ticket.isCurrent())
      error.value = '保存失败，白名单或账号可能已变化，请刷新后重试。';
  } finally {
    if (ticket.isCurrent()) saving.value = false;
  }
}
function selectUsers(value: Api.User[]) {
  users.value = value;
  form.userIds = value.map((item) => item.id);
}
watch(
  () => [props.id, props.readonly],
  () => {
    void load();
  },
  { immediate: true },
);
watch(
  identity,
  () => {
    guard.invalidate();
    ready.value = false;
    picker.value = false;
    emit('close');
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => guard.invalidate());
</script>
<template>
  <Modal
    :open="true"
    :title="
      readonly
        ? '脱敏白名单详情'
        : editing
          ? '修改脱敏白名单'
          : '新增脱敏白名单'
    "
    :width="800"
    :centered="false"
    :style="{ top: '6vh' }"
    :mask-closable="false"
    :closable="!saving"
    :keyboard="!saving"
    class="category-reference-dialog whitelist-reference-dialog"
    @cancel="emit('close')"
  >
    <Alert v-if="error && (!/(?:加载|读取|获取).*失败/.test(error) || /保存|冲突|未确认/.test(error))" :message="error" type="error" /><Spin
      :spinning="loading"
    >
      <Form
        class="category-form"
        :class="{ 'category-detail-form': readonly }"
        :colon="false"
        :disabled="saving || !ready || !allowed"
        :label-col="{ style: { width: '110px' } }"
        :wrapper-col="{ style: { flex: 1, minWidth: 0 } }"
      >
        <FormItem v-if="readonly && row" label="编号">
          <Input :value="whitelistDisplayId(row)" disabled />
</FormItem><FormItem label="白名单名称" :required="!readonly">
          <Input
            v-model:value="form.name"
            :disabled="readonly"
            :maxlength="50"
            placeholder="请输入白名单名称"
          />
</FormItem><FormItem label="数据分类" :required="!readonly">
          <Input
            v-if="editing || readonly"
            :value="row?.categoryName || '-'"
            disabled
          /><TreeSelect
            v-else
            v-model:value="form.categoryId"
            :tree-data="categoryTree"
            tree-default-expand-all
            show-search
            tree-node-filter-prop="title"
            allow-clear
            placeholder="请选择数据分类"
          />
</FormItem><FormItem label="生效分类" :required="!readonly">
          <Input v-if="readonly" value="用户" disabled /><RadioGroup
            v-else
            v-model:value="form.effectiveCategory"
            :options="[
              { label: '用户', value: '1' },
              { label: '角色', value: '2', disabled: true },
              { label: '部门', value: '3', disabled: true },
            ]"
          />
</FormItem><FormItem
          label="生效账号"
          :required="!readonly"
          :class="{ 'category-full-row': readonly }"
        >
          <div class="whitelist-account-row">
            <Input
              :value="users.length ? whitelistUserNames(users) : undefined"
              :title="whitelistUserNames(users)"
              placeholder="请选择生效账号"
              disabled
            /><Button
              v-if="!readonly"
              type="primary"
              :disabled="!can('query', 'whitelist')"
              @click="picker = true"
            >
              选择账号
            </Button>
          </div>
</FormItem><FormItem label="生效时段" :required="!readonly">
          <Input
            v-if="readonly && row"
            :value="whitelistPeriod(row)"
            disabled
          /><RangePicker
            v-else
            v-model:value="dates"
            value-format="YYYY-MM-DD"
            :placeholder="['开始时间', '结束时间']"
            separator="-"
          />
</FormItem><FormItem label="状态" :required="!readonly">
          <Tag
            v-if="readonly"
            :class="
              form.status === 0 ? 'category-enabled' : 'category-disabled'
            "
          >
            {{ form.status === 0 ? '启用' : '禁用' }}
</Tag><RadioGroup
            v-else
            v-model:value="form.status"
            :options="[
              { label: '禁用', value: 1 },
              { label: '启用', value: 0 },
            ]"
          />
</FormItem><FormItem label="描述" class="category-full-row">
          <Input.TextArea
            v-model:value="form.description"
            :disabled="readonly"
            :rows="3"
            :maxlength="256"
            :show-count="!readonly"
            placeholder="请输入描述"
          />
</FormItem><template v-if="readonly && row">
          <FormItem label="创建人">
            <Input :value="row.creatorName || '-'" disabled />
</FormItem><FormItem label="创建时间">
            <Input :value="categoryTime(row.createTime)" disabled />
</FormItem><FormItem label="更新人">
            <Input :value="row.updaterName || '-'" disabled />
</FormItem><FormItem label="更新时间">
            <Input :value="categoryTime(row.updateTime)" disabled />
          </FormItem>
        </template>
      </Form>
</Spin><template #footer>
      <Button :disabled="saving" @click="emit('close')">
        {{ readonly ? '关闭' : '取消' }}
</Button><Button
        v-if="!readonly"
        type="primary"
        :loading="saving"
        :disabled="loading || !ready || !allowed"
        @click="save"
      >
        确定
      </Button>
    </template>
</Modal><UserPicker
    v-if="picker"
    :selected="users"
    @close="picker = false"
    @select="selectUsers"
  />
</template>
