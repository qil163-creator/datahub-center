<script lang="ts" setup>
import type { SafetyWhitelistWorkbenchApi as Api } from '#/api/governance/whitelist/workbench';

import { computed, onBeforeUnmount, reactive, ref, watch } from 'vue';

import { Alert, Button, Modal, Table } from 'antdv-next';

import { getWhitelistWorkbenchUsers } from '#/api/governance/whitelist/workbench';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';
import { useCategorySession } from '#/views/governance/category/use-session';
import NativeSearch from '#/views/governance/common/native-search.vue';
import ReferencePagination from '#/views/governance/standardcommon/standard-pagination.vue';

const props = defineProps<{ selected: Api.User[] }>();
const emit = defineEmits<{ close: []; select: [users: Api.User[]] }>();
const { identity, can } = useCategorySession();
const allowed = computed(() => can('query', 'whitelist'));
const rows = ref<Api.User[]>([]);
const chosen = ref(
  new Map(props.selected.map((item) => [resourceIdKey(item.id), item])),
);
const query = reactive<Api.UserPageReq & { pageNo: number; pageSize: number }>({
  pageNo: 1,
  pageSize: 10,
});
const filter = reactive<{ mobile?: string; username?: string }>({});
const total = ref(0);
const loading = ref(false);
const error = ref('');
const guard = createLatestWins();
const emptyImage = '/assets/qdata-map/empty-search.png';
let active = true;
const columns = [
  {
    key: 'id',
    dataIndex: 'id',
    title: '编号',
    width: 80,
    align: 'center' as const,
  },
  {
    key: 'username',
    dataIndex: 'username',
    title: '登录账号',
    align: 'center' as const,
  },
  {
    key: 'nickname',
    dataIndex: 'nickname',
    title: '用户姓名',
    align: 'center' as const,
  },
  {
    key: 'deptName',
    dataIndex: 'deptName',
    title: '部门',
    width: 180,
    align: 'center' as const,
  },
  {
    key: 'mobile',
    dataIndex: 'mobile',
    title: '手机号码',
    width: 180,
    align: 'center' as const,
  },
];
const selection = computed(() => ({
  columnWidth: 70,
  selectedRowKeys: [...chosen.value.keys()],
  preserveSelectedRowKeys: true,
  onSelect(row: Api.User, selected: boolean) {
    const id = resourceIdKey(row.id);
    if (!parseResourceId(id)) return;
    if (selected) chosen.value.set(id, row);
    else chosen.value.delete(id);
  },
  onSelectAll(
    selected: boolean,
    _selectedRows: Api.User[],
    changed: Api.User[],
  ) {
    for (const row of changed) {
      const id = resourceIdKey(row.id);
      if (!parseResourceId(id)) continue;
      if (selected) chosen.value.set(id, row);
      else chosen.value.delete(id);
    }
  },
}));
async function load() {
  if (!active || !allowed.value) return;
  const ticket = guard.next();
  loading.value = true;
  error.value = '';
  try {
    const data = await getWhitelistWorkbenchUsers({ ...query });
    if (!ticket.isCurrent()) return;
    rows.value = data.list;
    total.value = data.total;
  } catch {
    if (ticket.isCurrent()) {
      rows.value = [];
      total.value = 0;
      error.value = '账号读取失败，请重试。';
    }
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}
function search() {
  Object.assign(query, {
    username: filter.username?.trim() || undefined,
    mobile: filter.mobile?.trim() || undefined,
    pageNo: 1,
  });
  void load();
}
function reset() {
  filter.username = undefined;
  filter.mobile = undefined;
  search();
}
function confirm() {
  if (!active || !allowed.value || loading.value) return;
  if (chosen.value.size > 100) {
    error.value = '最多选择100个账号。';
    return;
  }
  emit('select', [...chosen.value.values()]);
  emit('close');
}
watch(
  identity,
  () => {
    active = false;
    guard.invalidate();
    rows.value = [];
    chosen.value.clear();
    emit('close');
  },
  { flush: 'sync' },
);
onBeforeUnmount(() => {
  active = false;
  guard.invalidate();
});
void load();
</script>
<template>
  <Modal
    :open="true"
    title="选择用户"
    :width="1000"
    :centered="false"
    :style="{ top: '6vh' }"
    :mask-closable="false"
    class="category-reference-dialog whitelist-user-dialog"
    @cancel="emit('close')"
  >
    <div class="qdata-category whitelist-user-content">
      <section class="category-filters">
        <NativeSearch
          :schema="[
            {
              fieldName: 'username',
              label: '登录账号',
              component: 'Input',
              componentProps: {
                allowClear: true,
                placeholder: '请输入登录账号',
              },
            },
            {
              fieldName: 'mobile',
              label: '手机号码',
              component: 'Input',
              componentProps: {
                allowClear: true,
                placeholder: '请输入手机号码',
              },
            },
          ]"
          @values="Object.assign(filter, $event)"
          @submit="search"
          @reset="reset"
        />
      </section>
      <Alert v-if="error && (!/(?:加载|读取|获取).*失败/.test(error) || /保存|冲突|未确认/.test(error))" :message="error" type="error" /><Table
        class="category-table"
        :columns="columns"
        :data-source="rows"
        :row-key="(row: Api.User) => resourceIdKey(row.id)"
        :row-selection="selection"
        :loading="loading"
        :pagination="false"
        :scroll="{ y: 380 }"
      >
        <template #bodyCell="{ column, record }">
          <span
            v-if="column.key === 'id'"
            class="whitelist-user-id"
            :title="resourceIdKey(record.id)"
            >{{ resourceIdKey(record.id) }}</span
          ><span v-else-if="column.key === 'username'">{{
            record.username || '-'
          }}</span
          ><span v-else-if="column.key === 'nickname'">{{
            record.nickname || '-'
          }}</span
          ><span v-else-if="column.key === 'deptName'">{{
            record.deptName || '-'
          }}</span
          ><span v-else-if="column.key === 'mobile'">{{
            record.mobile || '-'
          }}</span> </template
        ><template #emptyText>
          <div class="safety-empty">
            <img :src="emptyImage" alt="" />
            <p>暂无记录</p>
          </div>
        </template>
      </Table>
      <footer v-if="total > 0" class="category-pagination">
        <ReferencePagination
          v-model:page="query.pageNo"
          v-model:page-size="query.pageSize"
          :total="total"
          @change="load"
        />
      </footer>
    </div>
    <template #footer>
      <Button @click="emit('close')">取消</Button
      ><Button type="primary" :disabled="!allowed || loading" @click="confirm">
        确定
      </Button>
    </template>
  </Modal>
</template>

<style scoped>
:deep(.ant-table-thead > tr > th),
:deep(.ant-table-tbody > tr > td) {
  font-size: 12px;
}
</style>
