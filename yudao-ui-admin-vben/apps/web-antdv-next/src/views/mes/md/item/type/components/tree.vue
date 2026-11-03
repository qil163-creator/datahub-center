<script lang="ts" setup>
import type { MesMdItemTypeApi } from '#/api/mes/md/item/type';

import { computed, onBeforeUnmount, onMounted, ref, watch } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { handleTree, traverseTreeValues } from '@vben/utils';

import { Input, Spin, Tree } from 'antdv-next';

import { getItemTypeSimpleList } from '#/api/mes/md/item/type';
import TreeToolbar from '#/components/tree-toolbar/index.vue';

defineOptions({ name: 'MdItemTypeTree' });

withDefaults(
  defineProps<{
    filterPlaceholder?: string;
  }>(),
  {
    filterPlaceholder: '搜索分类',
  },
);

const emit = defineEmits<{
  nodeClick: [itemType: MesMdItemTypeApi.ItemType | undefined];
}>();

const loading = ref(false); // 分类树加载中
const filterText = ref(''); // 分类搜索关键字
const currentNodeId = ref<number>(); // 当前选中分类编号
const selectedKeys = ref<number[]>([]); // 当前选中节点 key 列表
const itemTypeList = ref<MesMdItemTypeApi.ItemType[]>([]); // 物料分类列表
const itemTypeTree = ref<any[]>([]); // 物料分类树

const expandedKeys = ref<number[]>([]);
const branchKeys = computed(() =>
  traverseTreeValues(itemTypeTree.value, (node) =>
    node.children?.length ? (node.id as number) : undefined,
  ).filter((key): key is number => key !== undefined),
);
const allExpanded = computed(
  () =>
    branchKeys.value.length > 0 &&
    branchKeys.value.every((key) => expandedKeys.value.includes(key)),
);
let requestId = 0;
let initialized = false;
function toggleExpand() {
  expandedKeys.value = allExpanded.value ? [] : [...branchKeys.value];
}

/** 加载分类树 */
async function loadTree() {
  const request = ++requestId;
  loading.value = true;
  try {
    const data = await getItemTypeSimpleList();
    if (request !== requestId) return;
    itemTypeList.value = data;
    handleSearch(filterText.value);
    expandedKeys.value = initialized
      ? expandedKeys.value.filter((key) => branchKeys.value.includes(key))
      : [...branchKeys.value];
    initialized = true;
  } finally {
    if (request === requestId) loading.value = false;
  }
}

onBeforeUnmount(() => {
  ++requestId;
});

/** 处理搜索逻辑 */
function handleSearch(value: string) {
  filterText.value = value;
  const filteredList = value
    ? itemTypeList.value.filter((item) => item.name?.includes(value))
    : itemTypeList.value;
  itemTypeTree.value = handleTree(filteredList, 'id', 'parentId');
}

/** 处理节点点击：支持点击同一节点取消选中 */
function handleSelect(selectedNodeKeys: any[], info: any) {
  const selectedKey = selectedNodeKeys[0] ?? info.node?.id ?? info.node?.key;
  const row = itemTypeList.value.find(
    (item) => String(item.id) === String(selectedKey),
  );
  if (!row) {
    currentNodeId.value = undefined;
    selectedKeys.value = [];
    emit('nodeClick', undefined);
    return;
  }
  if (currentNodeId.value === row.id) {
    currentNodeId.value = undefined;
    selectedKeys.value = [];
    emit('nodeClick', undefined);
    return;
  }
  currentNodeId.value = row.id;
  selectedKeys.value = row.id === undefined ? [] : [row.id];
  emit('nodeClick', row);
}

/** 清空选中状态 */
function clearCurrent() {
  currentNodeId.value = undefined;
  selectedKeys.value = [];
}

/** 重置整个树状态 */
function reset() {
  clearCurrent();
  filterText.value = '';
  itemTypeTree.value = handleTree(itemTypeList.value, 'id', 'parentId');
}

/** 设置当前选中分类 */
function setCurrent(itemTypeId: number) {
  currentNodeId.value = itemTypeId;
  selectedKeys.value = [itemTypeId];
}

watch(filterText, (value) => {
  handleSearch(value);
  if (value) expandedKeys.value = [...branchKeys.value];
});

defineExpose({ clearCurrent, loadTree, reset, setCurrent });

/** 初始化 */
onMounted(() => {
  loadTree();
});
</script>

<template>
  <div class="h-full">
    <div class="mb-2 flex items-center gap-2">
      <Input
        v-model:value="filterText"
        :placeholder="filterPlaceholder"
        allow-clear
        class="min-w-0 flex-1"
      >
        <template #prefix>
          <IconifyIcon class="size-4" icon="lucide:search" />
        </template>
      </Input>
      <TreeToolbar
        :expanded="allExpanded"
        :loading="loading"
        @toggle-expand="toggleExpand"
        @refresh="loadTree"
      />
    </div>
    <Spin :spinning="loading" :classes="{ root: 'w-full' }">
      <Tree
        v-if="itemTypeTree.length > 0"
        v-model:expanded-keys="expandedKeys"
        :field-names="{ title: 'name', key: 'id', children: 'children' }"
        :selected-keys="selectedKeys"
        :tree-data="itemTypeTree"
        class="pt-2"
        @select="handleSelect"
      />
      <div v-else-if="!loading" class="py-4 text-center text-gray-500">
        暂无数据
      </div>
    </Spin>
  </div>
</template>
