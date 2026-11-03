<script lang="ts" setup>
import type { SystemDeptApi } from '#/api/system/dept';

import { computed, onBeforeUnmount, onMounted, ref } from 'vue';

import { IconifyIcon } from '@vben/icons';
import { handleTree } from '@vben/utils';

import { Input, Spin, Tree } from 'antdv-next';

import { getSimpleDeptList } from '#/api/system/dept';
import TreeToolbar from '#/components/tree-toolbar/index.vue';

defineOptions({ name: 'DeptTreeSelect' });

const props = withDefaults(
  defineProps<{
    /** 部门名称超出侧栏时允许横向滚动 */
    horizontalScroll?: boolean;
    /** 是否显示全部展开/收起按钮，并让树在左侧栏内滚动 */
    showExpandToggle?: boolean;
  }>(),
  {
    horizontalScroll: false,
    showExpandToggle: false,
  },
);

const emit = defineEmits<{
  select: [dept?: SystemDeptApi.Dept];
}>();

const deptList = ref<SystemDeptApi.Dept[]>([]); // 部门列表
const deptTree = ref<any[]>([]); // 部门树
const expandedKeys = ref<number[]>([]); // 展开的节点
const selectedKeys = ref<number[]>([]); // 选中的节点
const loading = ref(false); // 加载状态
const searchValue = ref(''); // 搜索值
const isExpanded = ref(true);
let requestId = 0;
let initialized = false;

/** 收集可展开节点 ID（有子节点的节点） */
function collectExpandableIds(nodes: any[]): number[] {
  const ids: number[] = [];
  for (const node of nodes) {
    if (node.children?.length) {
      ids.push(node.id, ...collectExpandableIds(node.children));
    }
  }
  return ids;
}

function applyExpandState() {
  expandedKeys.value = isExpanded.value
    ? collectExpandableIds(deptTree.value)
    : [];
}

const allExpanded = computed(() => {
  const keys = collectExpandableIds(deptTree.value);
  return (
    keys.length > 0 && keys.every((key) => expandedKeys.value.includes(key))
  );
});

function toggleExpandAll() {
  isExpanded.value = !allExpanded.value;
  applyExpandState();
}

/** 处理搜索逻辑 */
function handleSearch(e: any) {
  const value = e.target.value;
  searchValue.value = value;
  const filteredList = value
    ? deptList.value.filter((item) =>
        item.name.toLowerCase().includes(value.toLowerCase()),
      )
    : deptList.value;
  deptTree.value = handleTree(filteredList);
  if (value) {
    isExpanded.value = true;
  }
  applyExpandState();
}

/** 选中部门：点击已选中的节点时取消选中 */
function handleSelect(selectedNodeKeys: any[], info: any) {
  const selectedKey = selectedNodeKeys[0];
  const dept = info.selected
    ? deptList.value.find((item) => String(item.id) === String(selectedKey))
    : undefined;
  emit('select', dept);
}

/** 重置选中状态（供外部重置按钮调用） */
function reset() {
  searchValue.value = '';
  selectedKeys.value = [];
  isExpanded.value = true;
  deptTree.value = handleTree(deptList.value);
  applyExpandState();
  emit('select', undefined);
}

defineExpose({ reset });

/** 刷新保留筛选和仍然存在的展开节点。 */
async function loadTree() {
  const request = ++requestId;
  loading.value = true;
  try {
    const data = await getSimpleDeptList();
    if (request !== requestId) return;
    deptList.value = data;
    const keyword = searchValue.value.toLowerCase();
    deptTree.value = handleTree(
      keyword
        ? data.filter((item) => item.name.toLowerCase().includes(keyword))
        : data,
    );
    if (initialized) {
      const available = new Set(collectExpandableIds(deptTree.value));
      expandedKeys.value = expandedKeys.value.filter((key) =>
        available.has(key),
      );
    } else {
      applyExpandState();
      initialized = true;
    }
  } finally {
    if (request === requestId) loading.value = false;
  }
}

onMounted(loadTree);
onBeforeUnmount(() => {
  ++requestId;
});
</script>

<template>
  <div
    :class="
      props.showExpandToggle
        ? 'flex h-full min-h-0 flex-col overflow-hidden'
        : undefined
    "
  >
    <div
      :class="
        props.showExpandToggle
          ? 'mb-2 flex shrink-0 items-center gap-2'
          : undefined
      "
    >
      <Input
        v-model:value="searchValue"
        allow-clear
        :class="props.showExpandToggle ? 'min-w-0 flex-1' : 'w-full'"
        placeholder="搜索部门"
        @change="handleSearch"
      >
        <template #prefix>
          <IconifyIcon class="size-4" icon="lucide:search" />
        </template>
      </Input>
      <TreeToolbar
        v-if="props.showExpandToggle"
        :expanded="allExpanded"
        :loading="loading"
        refresh-label="刷新部门树"
        @toggle-expand="toggleExpandAll"
        @refresh="loadTree"
      />
    </div>
    <div
      :class="
        props.showExpandToggle
          ? `subtle-tree-scrollbar min-h-0 flex-1 overflow-y-auto ${props.horizontalScroll ? 'overflow-x-auto' : 'overflow-x-hidden'}`
          : undefined
      "
    >
      <Spin :spinning="loading" :classes="{ root: 'w-full' }">
        <Tree
          v-if="deptTree.length > 0"
          v-model:expanded-keys="expandedKeys"
          v-model:selected-keys="selectedKeys"
          class="pt-2"
          :class="{ 'dept-tree-horizontal': props.horizontalScroll }"
          :field-names="{ title: 'name', key: 'id', children: 'children' }"
          :tree-data="deptTree"
          @select="handleSelect"
        />
        <div v-else-if="!loading" class="py-4 text-center text-gray-500">
          暂无数据
        </div>
      </Spin>
    </div>
  </div>
</template>

<style scoped>
.dept-tree-horizontal {
  width: max-content;
  min-width: 100%;
}

.dept-tree-horizontal :deep(.ant-tree-treenode) {
  white-space: nowrap;
}
</style>
