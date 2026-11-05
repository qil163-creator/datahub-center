<script lang="ts" setup>
import type { FoundationCategoryDataAssetApi } from '#/api/asset/categorydataasset';
import type { DataAssetApi } from '#/api/asset/dataasset';
import type { AssetRegistrationApi } from '#/api/asset/dataasset/registration';
import type { AssetTagApi } from '#/api/asset/tag';

import {
  computed,
  onActivated,
  onBeforeUnmount,
  onDeactivated,
  onMounted,
  reactive,
  ref,
  watch,
} from 'vue';
import { useRouter } from 'vue-router';

import { useAccess } from '@vben/access';
import { Page, useVbenModal } from '@vben/common-ui';
import { IconifyIcon } from '@vben/icons';
import { useAccessStore, useUserStore } from '@vben/stores';
import { formatDateTime } from '@vben/utils';

import {
  Alert,
  Button,
  Dropdown,
  Empty,
  Form,
  FormItem,
  Input,
  Menu,
  message,
  Modal,
  Select,
  SpaceCompact,
  Spin,
  Tag,
  Tree,
  TreeSelect,
} from 'antdv-next';

import { useVbenForm } from '#/adapter/form';
import {
  ASSET_STATUS_OPTIONS,
  ASSET_TYPE_OPTIONS,
  createCatalogDraft,
  deleteDataAsset,
  getDataAssetDetail,
  getDataAssetFacets,
  getDataAssetNavigationCategories,
  getDataAssetPage,
  refreshDataAsset,
  stopDataAssetSupply,
  unpublishDataAsset,
  updateDataAsset,
} from '#/api/asset/dataasset';
import {
  getAssetRegistrationContext,
  getAssetRegistrationOptions,
} from '#/api/asset/dataasset/registration';
import { bindAssetTags, getAssetTagList } from '#/api/asset/tag';
import { getDataDomainSimpleList } from '#/api/modeling/datadomain';
import ListPagination from '#/components/list-pagination/index.vue';
import TreeToolbar from '#/components/tree-toolbar/index.vue';
import {
  createLatestWins,
  parseResourceId,
  resourceIdKey,
} from '#/utils/resource-id';

import TagCreateForm from '../../tag/modules/form.vue';
import MarketPublishForm from '../modules/publish-form.vue';
import { formatCount, TABLE_KIND_META } from './data';

defineOptions({ name: 'AssetMarketMap' });
function mapAssetIcon() {
  return '/assets/qdata-map/asset.svg';
}
function mapSvg(name: string) {
  return `/assets/qdata-map/icon-${name}.svg`;
}

const router = useRouter();
const { hasAccessByCodes } = useAccess();
const access = useAccessStore();
const user = useUserStore();
const canQuery = computed(
  () =>
    !!access.accessToken &&
    !access.loginExpired &&
    !!parseResourceId(user.userInfo?.userId),
);
const identity = computed(() =>
  JSON.stringify([
    access.accessToken,
    access.refreshToken,
    access.loginExpired,
    access.accessCodes,
    user.userInfo?.userId,
    user.userRoles,
  ]),
);
const listGuard = createLatestWins();
const facetsGuard = createLatestWins();
const dataDomainsGuard = createLatestWins();
const tagsGuard = createLatestWins();
let active = false;
const listError = ref('');
const facetsError = ref('');
const dataDomainsError = ref('');

/** 左侧分组树：数据板块 / 数仓分层 */
type GroupKind = 'category' | 'dataDomain' | 'layer';
interface GroupNode {
  key: string;
  title: string;
  count?: number;
  kind?: GroupKind;
  value?: number | string;
  selectable?: boolean;
  children?: GroupNode[];
}

const facets = ref<DataAssetApi.Facets>({});
const categories = ref<FoundationCategoryDataAssetApi.CategoryDataAsset[]>([]);
const dataDomains = ref<Array<{ id: string; name: string }>>([]);
const groupKeyword = ref('');
const selectedGroupKey = ref<string>();
const expandedKeys = ref<string[]>([
  'group:data-domain',
  'group:layer',
]);

function buildCategoryTree(): GroupNode[] {
  const countMap = new Map(
    (facets.value.categories ?? []).map((item) => [item.key, item.count ?? 0]),
  );
  const nodeMap = new Map<string, GroupNode>();
  categories.value.forEach((item) => {
    const key = String(item.id);
    nodeMap.set(key, {
      key: `category:${key}`,
      title: item.categoryName ?? `类目#${item.id}`,
      count: countMap.get(key) ?? 0,
      kind: 'category',
      value: key,
      children: [],
    });
  });
  const roots: GroupNode[] = [];
  categories.value.forEach((item) => {
    const node = nodeMap.get(String(item.id))!;
    const parentKey =
      item.parentId && item.parentId !== 0 ? String(item.parentId) : undefined;
    const parent = parentKey ? nodeMap.get(parentKey) : undefined;
    if (parent) {
      parent.children!.push(node);
    } else {
      roots.push(node);
    }
  });
  const sum = (node: GroupNode): number => {
    const own = node.count ?? 0;
    const childrenTotal = (node.children ?? []).reduce(
      (acc, child) => acc + sum(child),
      0,
    );
    node.count = own + childrenTotal;
    return node.count;
  };
  roots.forEach((node) => sum(node));
  return roots;
}

const groupTree = computed<GroupNode[]>(() => {
  const tree: GroupNode[] = [
    {
      key: 'group:data-domain',
      title: '按数据板块',
      selectable: false,
      children: dataDomains.value.map((item) => ({
        key: `data-domain:${String(item.id)}`,
        title: item.name,
        kind: 'dataDomain',
        value: String(item.id),
      })),
    },
    {
      key: 'group:layer',
      title: '按数仓分层',
      selectable: false,
      children: [
        { key: 'source', title: '贴源层', codes: ['ODS'] },
        { key: 'public', title: '公共层', codes: ['DIM', 'DWD', 'DWS', 'DWM'] },
        { key: 'application', title: '应用层', codes: ['ADS', 'APP'] },
      ]
        .map((group) => ({
          key: `layer-group:${group.key}`,
          title: group.title,
          selectable: false,
          children: (facets.value.layers ?? [])
            .filter((item) => group.codes.includes(item.key))
            .map((item) => ({
              key: `layer:${item.key}`,
              title: item.label,
              count: item.count,
              kind: 'layer' as GroupKind,
              value: item.key,
            })),
        }))
        .filter((group) => group.children.length),
    },
  ];
  const keyword = groupKeyword.value.trim();
  if (!keyword) return tree;
  const filter = (nodes: GroupNode[]): GroupNode[] =>
    nodes
      .map((node) => ({ ...node, children: filter(node.children ?? []) }))
      .filter(
        (node) =>
          node.title.includes(keyword) || (node.children?.length ?? 0) > 0,
      );
  return filter(tree);
});

const loading = ref(false);
const assets = ref<DataAssetApi.DataAsset[]>([]);
const pagination = reactive({ pageNo: 1, pageSize: 10, total: 0 });
const query = reactive<{
  assetType?: number;
  categoryId?: number | string;
  dataDomainId?: number | string;
  keyword?: string;
  layer?: string;
}>({});

const [FilterForm, filterFormApi] = useVbenForm({
  actionWrapperClass: '[&>*]:shrink-0 whitespace-nowrap',
  commonConfig: { componentProps: { class: 'w-full' }, labelWidth: 100 },
  layout: 'horizontal',
  wrapperClass: 'grid-cols-1 md:grid-cols-2 lg:grid-cols-3',
  showCollapseButton: false,
  submitOnEnter: true,
  submitButtonOptions: { content: '查询' },
  schema: [
    {
      fieldName: 'assetType',
      label: '资产类型',
      component: 'Select',
      componentProps: {
        allowClear: true,
        options: ASSET_TYPE_OPTIONS,
        placeholder: '请选择资产类型',
      },
    },
    {
      fieldName: 'keyword',
      label: '资产名称',
      component: 'Input',
      componentProps: { allowClear: true, placeholder: '请输入资产名称' },
    },
  ],
  handleValuesChange(values) {
    query.assetType = values.assetType;
    query.keyword = values.keyword;
  },
  handleSubmit: search,
  handleReset: reset,
});

const allTags = ref<AssetTagApi.Tag[]>([]);
const categoryTreeForSelect = computed(() => buildCategoryTree());

async function loadFacets() {
  if (!active || !canQuery.value) return;
  const ticket = facetsGuard.next();
  facetsError.value = '';
  try {
    const [facetData, categoryList] = await Promise.all([
      getDataAssetFacets(),
      getDataAssetNavigationCategories(),
    ]);
    if (!ticket.isCurrent()) return;
    facets.value = facetData;
    categories.value = categoryList;
  } catch {
    if (ticket.isCurrent()) {
      facetsError.value = '资产分组读取失败，请重新加载。';
    }
  }
}

async function loadDataDomains() {
  if (!active || !canQuery.value) return;
  const ticket = dataDomainsGuard.next();
  dataDomainsError.value = '';
  try {
    const domains = await getDataDomainSimpleList();
    if (ticket.isCurrent()) {
      dataDomains.value = domains.flatMap((item) => {
        const id = parseResourceId(item.id);
        return item.status === 0 && id
          ? [{ id, name: item.name?.trim() || `数据板块#${id}` }]
          : [];
      });
    }
  } catch {
    if (ticket.isCurrent()) dataDomainsError.value = '数据板块读取失败，请重新加载。';
  }
}

function collectExpandableKeys(nodes: GroupNode[]): string[] {
  const keys: string[] = [];
  for (const node of nodes) {
    if (node.children?.length) {
      keys.push(node.key, ...collectExpandableKeys(node.children));
    }
  }
  return keys;
}

const isGroupExpanded = computed(() =>
  collectExpandableKeys(groupTree.value).length > 0 &&
  collectExpandableKeys(groupTree.value).every((key) =>
    expandedKeys.value.includes(key),
  ),
);

function toggleExpandAll() {
  expandedKeys.value = isGroupExpanded.value
    ? []
    : collectExpandableKeys(groupTree.value);
}

const groupLoading = ref(false);
let groupRefreshId = 0;
async function refreshGroups() {
  const request = ++groupRefreshId;
  groupLoading.value = true;
  try {
    await Promise.all([loadFacets(), loadDataDomains()]);
  } finally {
    if (request === groupRefreshId) groupLoading.value = false;
  }
}
async function loadAssets() {
  if (!active || !canQuery.value) return;
  const ticket = listGuard.next();
  assets.value = [];
  pagination.total = 0;
  listError.value = '';
  loading.value = true;
  try {
    const result = await getDataAssetPage({
      pageNo: pagination.pageNo,
      pageSize: pagination.pageSize,
      ...query,
    });
    if (!ticket.isCurrent()) return;
    assets.value = result.list ?? [];
    pagination.total = result.total ?? 0;
  } catch {
    if (ticket.isCurrent()) listError.value = '资产读取失败，请重新查询。';
  } finally {
    if (ticket.isCurrent()) loading.value = false;
  }
}

function search() {
  pagination.pageNo = 1;
  loadAssets();
}

function reset() {
  if (filterFormApi.isMounted) void filterFormApi.reset();
  Object.assign(query, {
    assetType: undefined,
    categoryId: undefined,
    dataDomainId: undefined,
    keyword: undefined,
    layer: undefined,
  });
  selectedGroupKey.value = undefined;
  search();
}

function selectAll() {
  selectedGroupKey.value = undefined;
  query.categoryId = undefined;
  query.dataDomainId = undefined;
  query.layer = undefined;
  search();
}

function onGroupSelect(_keys: (number | string)[], info: { node: any }) {
  const node = info.node as GroupNode;
  if (_keys.length === 0) {
    selectAll();
    return;
  }
  if (!node?.kind) return;
  selectedGroupKey.value = node.key;
  query.categoryId = node.kind === 'category' ? node.value : undefined;
  query.dataDomainId = node.kind === 'dataDomain' ? node.value : undefined;
  query.layer = node.kind === 'layer' ? String(node.value) : undefined;
  search();
}

function openDetail(asset: DataAssetApi.DataAsset) {
  if (!active || !canQuery.value || !parseResourceId(asset.id)) return;
  router.push({ name: 'AssetMarketMapDetail', params: { id: asset.id } });
}

function openRegister(assetType?: number) {
  if (
    !active ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:data-asset:create'])
  )
    return;
  if (assetType)
    router.push({
      path: '/asset/assetmap',
      query: { assetType: String(assetType) },
    });
  else router.push({ path: '/asset/assetmap' });
}

const draftOpen = ref(false);
const draftSaving = ref(false);
const draftForm = reactive({ assetName: '', description: '' });

async function saveCatalogDraft() {
  if (!draftForm.assetName.trim()) {
    message.warning('请输入目录名称');
    return;
  }
  draftSaving.value = true;
  try {
    const id = await createCatalogDraft({
      assetName: draftForm.assetName.trim(),
      categoryId: query.categoryId,
      description: draftForm.description,
    });
    message.success('已创建未挂接技术资源的目录草稿，不能预览或开通取数');
    draftOpen.value = false;
    draftForm.assetName = '';
    draftForm.description = '';
    refreshAll();
    if (id !== undefined && id !== null) {
      router.push({ name: 'AssetMarketMapDetail', params: { id: String(id) } });
    }
  } finally {
    draftSaving.value = false;
  }
}

function statusMeta(status?: number) {
  return (
    ASSET_STATUS_OPTIONS.find((item) => item.value === status) ??
    ASSET_STATUS_OPTIONS[0]!
  );
}

function tableKind(asset: DataAssetApi.DataAsset) {
  return (
    TABLE_KIND_META[(asset.layer ?? '').toUpperCase()] ?? {
      label: asset.tableType || '数据表',
      color: 'blue',
    }
  );
}

async function refreshAll() {
  await Promise.all([loadFacets(), loadDataDomains(), loadAssets()]);
}

const [MarketPublishModal, marketPublishApi] = useVbenModal({
  connectedComponent: MarketPublishForm,
  destroyOnClose: true,
});

function togglePublish(asset: DataAssetApi.DataAsset) {
  if (
    !active ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:data-asset:publish'])
  )
    return;
  const subject = identity.value;
  const publishing = asset.status !== 1;
  if (publishing) {
    marketPublishApi.setData({ asset }).open();
    return;
  }
  Modal.confirm({
    title: '撤销发布',
    content: `确认撤销发布“${asset.assetName}”吗？`,
    onOk: async () => {
      if (
        !active ||
        !canQuery.value ||
        identity.value !== subject ||
        !hasAccessByCodes(['asset:data-asset:publish'])
      )
        return;
      await unpublishDataAsset(asset.id!);
      if (!active || identity.value !== subject) return;
      message.success('撤销发布成功');
      refreshAll();
    },
  });
}

const stopAccess = useAccessStore();
let stopEpoch = 0;
const stopTarget = ref<DataAssetApi.DataAsset>();
const stopReason = ref('');
const stopping = ref(false);
function openStop(asset: DataAssetApi.DataAsset) {
  if (
    !active ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:data-asset:stop-supply']) ||
    !parseResourceId(asset.id)
  )
    return;
  stopEpoch++;
  stopping.value = false;
  stopTarget.value = asset;
  stopReason.value = '';
}
async function stopSupply() {
  if (
    !active ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:data-asset:stop-supply']) ||
    !stopTarget.value?.id ||
    stopping.value
  )
    return;
  if (!stopReason.value.trim()) {
    message.warning('请填写停止供数原因');
    return;
  }
  const current = stopEpoch;
  stopping.value = true;
  try {
    await stopDataAssetSupply({
      assetId: stopTarget.value.id,
      reason: stopReason.value,
    });
    if (current !== stopEpoch) return;
    stopTarget.value = undefined;
    message.success('停供意图已登记，撤权将持续对账至确认');
    await refreshAll();
  } finally {
    if (current === stopEpoch) stopping.value = false;
  }
}

watch(
  () => [stopAccess.accessToken, stopAccess.refreshToken],
  () => {
    stopEpoch++;
    stopTarget.value = undefined;
    stopReason.value = '';
  },
);

async function refreshAsset(asset: DataAssetApi.DataAsset) {
  if (
    !active ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:data-asset:update']) ||
    !parseResourceId(asset.id)
  )
    return;
  const subject = identity.value;
  await refreshDataAsset(asset.id!);
  if (!active || identity.value !== subject) return;
  message.success('已按最新元数据定版刷新资产快照');
  void refreshAll();
}
function removeAsset(asset: DataAssetApi.DataAsset) {
  if (
    !active ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:data-asset:delete']) ||
    !parseResourceId(asset.id)
  )
    return;
  const subject = identity.value;
  Modal.confirm({
    title: '删除资产',
    content: `确认删除资产“${asset.assetName}”吗？`,
    okButtonProps: { danger: true },
    onOk: async () => {
      if (
        !active ||
        !canQuery.value ||
        identity.value !== subject ||
        !hasAccessByCodes(['asset:data-asset:delete'])
      )
        return;
      const deleted = await deleteDataAsset(asset.id!);
      if (!active || identity.value !== subject) return;
      if (deleted) message.success('删除成功');
      else message.info('解绑意图已保存，待后台确认后请刷新并再次删除');
      void refreshAll();
    },
  });
}

const editOpen = ref(false);
const editSaving = ref(false);
const editLoading = ref(false);
const editError = ref('');
const editGuard = createLatestWins();
const editForm = reactive<Partial<DataAssetApi.DataAsset>>({});
const registrationOptions = ref<DataAssetApi.Facets>({});
const editContext = ref<AssetRegistrationApi.Context>();
const editDomainOptions = computed(() =>
  (editContext.value?.dataDomains ?? []).map((item) => ({
    label: item.name,
    value: resourceIdKey(item.id),
  })),
);
const subjectOptions = computed(() =>
  editForm.registrationConfig?.dataDomainId
    ? (editContext.value?.subjectDomains ?? []).map((item) => ({
        value: resourceIdKey(item.id),
        label: item.name,
      }))
    : (registrationOptions.value.subjectDomains ?? []).map((item) => ({
        value: item.key,
        label: item.label,
      })),
);
async function openEdit(asset: DataAssetApi.DataAsset) {
  const id = parseResourceId(asset.id);
  if (
    !active ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:data-asset:update']) ||
    !id
  )
    return;
  const ticket = editGuard.next();
  for (const key of Object.keys(editForm))
    Reflect.deleteProperty(editForm, key);
  editOpen.value = true;
  editLoading.value = true;
  editError.value = '';
  try {
    const [value, options] = await Promise.all([
      getDataAssetDetail(id),
      getAssetRegistrationOptions(),
    ]);
    if (!ticket.isCurrent()) return;
    if (String(value.asset?.id) !== id) throw new Error('ASSET_IDENTITY');
    Object.assign(editForm, value.asset, {
      expectedRevision: value.asset?.catalogRevision,
      categoryId:
        value.asset?.categoryId === undefined
          ? undefined
          : String(value.asset.categoryId),
      subjectDomainId:
        value.asset?.subjectDomainId === undefined
          ? undefined
          : String(value.asset.subjectDomainId),
    });
    editForm.registrationConfig = { ...value.asset?.registrationConfig };
    if (editForm.registrationConfig.dataDomainId)
      editForm.registrationConfig.dataDomainId = resourceIdKey(
        editForm.registrationConfig.dataDomainId,
      );
    const context = await getAssetRegistrationContext({
      dataDomainId: editForm.registrationConfig?.dataDomainId,
      tableType: editForm.registrationConfig?.tableType,
      subjectDomainId: editForm.registrationConfig?.dataDomainId
        ? editForm.subjectDomainId
        : undefined,
    });
    if (!ticket.isCurrent()) return;
    editContext.value = context;
    registrationOptions.value = options;
  } catch {
    if (ticket.isCurrent())
      editError.value = '资产详情或可选归属读取失败，请关闭后重试。';
  } finally {
    if (ticket.isCurrent()) editLoading.value = false;
  }
}
async function changeEditDomain(value: unknown) {
  if (
    editLoading.value ||
    editSaving.value ||
    !editOpen.value ||
    !hasAccessByCodes(['asset:data-asset:update'])
  )
    return;
  const ticket = editGuard.next();
  editForm.registrationConfig = {
    ...editForm.registrationConfig,
    dataDomainId: parseResourceId(value),
    dataDomainName: undefined,
    entityId: undefined,
    granularityEntityIds: undefined,
    businessEntity: undefined,
    statGranularity: undefined,
  };
  editForm.subjectDomainId = undefined;
  editLoading.value = true;
  editError.value = '';
  try {
    const context = await getAssetRegistrationContext({
      dataDomainId: editForm.registrationConfig.dataDomainId,
      tableType: editForm.registrationConfig.tableType,
    });
    if (ticket.isCurrent()) editContext.value = context;
  } catch {
    if (ticket.isCurrent())
      editError.value = '业务板块的主题域读取失败，请关闭后重试。';
  } finally {
    if (ticket.isCurrent()) editLoading.value = false;
  }
}
function changeEditSubject(value: unknown) {
  if (!editOpen.value || editSaving.value || editLoading.value) return;
  editForm.subjectDomainId = parseResourceId(value);
  if (editForm.registrationConfig?.dataDomainId)
    editForm.registrationConfig = {
      ...editForm.registrationConfig,
      entityId: undefined,
      granularityEntityIds: undefined,
      businessEntity: undefined,
      statGranularity: undefined,
    };
}
async function saveEdit() {
  if (
    !editOpen.value ||
    !active ||
    !canQuery.value ||
    editLoading.value ||
    editSaving.value ||
    editError.value ||
    !hasAccessByCodes(['asset:data-asset:update']) ||
    !parseResourceId(editForm.id)
  )
    return;
  if (!editForm.assetName?.trim()) {
    message.warning('请输入资产中文名称');
    return;
  }
  const ticket = editGuard.next();
  editSaving.value = true;
  try {
    await updateDataAsset({
      ...editForm,
      assetName: editForm.assetName.trim(),
    });
    if (!ticket.isCurrent()) return;
    message.success('保存成功');
    editOpen.value = false;
    void refreshAll();
  } finally {
    if (ticket.isCurrent()) editSaving.value = false;
  }
}

/** 打标 */
const tagOpen = ref(false);
const tagSaving = ref(false);
const tagLoading = ref(false);
const tagError = ref('');
const tagGuard = createLatestWins();
const [TagCreateModal, tagCreateApi] = useVbenModal({
  connectedComponent: TagCreateForm,
  destroyOnClose: true,
});
const taggingAsset = ref<DataAssetApi.DataAsset>();
const selectedTagIds = ref<DataAssetApi.Id[]>([]);

const tagOptions = computed(() =>
  allTags.value
    .filter((tag) => tag.status === 0)
    .map((tag) => ({
      label: tag.categoryName ? `${tag.name}（${tag.categoryName}）` : tag.name,
      value: String(tag.id),
    })),
);

async function openTags(asset: DataAssetApi.DataAsset) {
  const id = parseResourceId(asset.id);
  if (
    !active ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:data-asset:tag']) ||
    !id
  )
    return;
  const ticket = tagGuard.next();
  taggingAsset.value = undefined;
  selectedTagIds.value = [];
  tagOpen.value = true;
  tagLoading.value = true;
  tagError.value = '';
  try {
    const [detail, tags] = await Promise.all([
      getDataAssetDetail(id),
      getAssetTagList(),
    ]);
    if (!ticket.isCurrent()) return;
    if (String(detail.asset?.id) !== id) throw new Error('ASSET_IDENTITY');
    const revision = detail.asset?.catalogRevision;
    if (revision !== 0 && revision !== '0' && !parseResourceId(revision))
      throw new Error('ASSET_REVISION');
    taggingAsset.value = detail.asset;
    allTags.value = tags;
    selectedTagIds.value = (detail.asset?.tags ?? [])
      .map((tag) => String(tag.id))
      .filter((id) => !!parseResourceId(id));
  } catch {
    if (ticket.isCurrent()) tagError.value = '资产标签读取失败，请关闭后重试。';
  } finally {
    if (ticket.isCurrent()) tagLoading.value = false;
  }
}
async function saveTags() {
  if (
    !tagOpen.value ||
    !active ||
    !canQuery.value ||
    !hasAccessByCodes(['asset:data-asset:tag']) ||
    tagLoading.value ||
    tagSaving.value ||
    tagError.value ||
    !parseResourceId(taggingAsset.value?.id)
  )
    return;
  const ticket = tagGuard.next();
  tagSaving.value = true;
  try {
    await bindAssetTags(
      taggingAsset.value!.id!,
      selectedTagIds.value,
      taggingAsset.value!.catalogRevision!,
    );
    if (!ticket.isCurrent()) return;
    message.success('标签已更新');
    tagOpen.value = false;
    void loadAssets();
  } catch {
    if (ticket.isCurrent())
      tagError.value = '标签保存失败，资产或关联可能已更新。请关闭后刷新重试。';
  } finally {
    if (ticket.isCurrent()) tagSaving.value = false;
  }
}
function goTagManage() {
  if (
    tagOpen.value &&
    active &&
    canQuery.value &&
    hasAccessByCodes(['asset:tag:create'])
  )
    tagCreateApi.setData({}).open();
}
async function tagCreated(id: AssetTagApi.Tag['id']) {
  if (!active || !canQuery.value || !tagOpen.value || !parseResourceId(id))
    return;
  const ticket = tagsGuard.next();
  const context = tagGuard.next();
  try {
    const tags = await getAssetTagList();
    if (!ticket.isCurrent() || !context.isCurrent()) return;
    allTags.value = tags;
    if (
      tags.some((tag) => String(tag.id) === String(id) && tag.status === 0) &&
      !selectedTagIds.value.some((value) => String(value) === String(id))
    )
      selectedTagIds.value.push(String(id));
  } catch {
    if (ticket.isCurrent())
      tagError.value = '标签已创建，选项刷新失败，请关闭后重新打开。';
  }
}
watch(
  editOpen,
  (value) => {
    if (!value) {
      editGuard.invalidate();
      editLoading.value = false;
      editSaving.value = false;
    }
  },
  { flush: 'sync' },
);
watch(
  tagOpen,
  (value) => {
    if (!value) {
      tagGuard.invalidate();
      tagLoading.value = false;
      tagSaving.value = false;
      tagCreateApi.setState({ isOpen: false });
    }
  },
  { flush: 'sync' },
);

interface MoreAction {
  key: string;
  label: string;
  icon: string;
  danger?: boolean;
  onClick: () => void;
}

function moreActions(asset: DataAssetApi.DataAsset): MoreAction[] {
  const actions: MoreAction[] = [];
  if (hasAccessByCodes(['asset:data-asset:stop-supply']) && asset.status !== 2)
    actions.push({
      key: 'stop-supply',
      label: '停止供数',
      icon: 'lucide:circle-stop',
      danger: true,
      onClick: () => openStop(asset),
    });
  if (hasAccessByCodes(['asset:data-asset:update'])) {
    actions.push(
      {
        key: 'edit',
        label: '修改',
        icon: 'lucide:pencil',
        onClick: () => openEdit(asset),
      },
      {
        key: 'refresh',
        label: '刷新元数据',
        icon: 'lucide:refresh-cw',
        onClick: () => refreshAsset(asset),
      },
    );
  }
  if (hasAccessByCodes(['asset:data-asset:tag'])) {
    actions.push({
      key: 'tag',
      label: '打标',
      icon: 'lucide:tags',
      onClick: () => openTags(asset),
    });
  }
  if (hasAccessByCodes(['asset:data-asset:delete'])) {
    actions.push({
      key: 'delete',
      label: '删除',
      icon: 'lucide:trash-2',
      danger: true,
      onClick: () => removeAsset(asset),
    });
  }
  return actions;
}

function invalidatePage() {
  groupRefreshId++;
  groupLoading.value = false;
  listGuard.invalidate();
  facetsGuard.invalidate();
  dataDomainsGuard.invalidate();
  tagsGuard.invalidate();
  assets.value = [];
  facets.value = {};
  categories.value = [];
  dataDomains.value = [];
  allTags.value = [];
  pagination.total = 0;
  loading.value = false;
  listError.value = '';
  facetsError.value = '';
  dataDomainsError.value = '';
  draftOpen.value = false;
  editOpen.value = false;
  editGuard.invalidate();
  editLoading.value = false;
  editSaving.value = false;
  editError.value = '';
  registrationOptions.value = {};
  tagOpen.value = false;
  tagGuard.invalidate();
  tagLoading.value = false;
  tagSaving.value = false;
  tagError.value = '';
  tagCreateApi.setState({ isOpen: false });
  stopEpoch++;
  stopping.value = false;
  stopTarget.value = undefined;
  stopReason.value = '';
  marketPublishApi.setState({ isOpen: false });
}
async function loadTags() {
  if (!active || !canQuery.value || !hasAccessByCodes(['asset:data-asset:tag']))
    return;
  const ticket = tagsGuard.next();
  try {
    const tags = await getAssetTagList();
    if (ticket.isCurrent()) allTags.value = tags;
  } catch {
    if (ticket.isCurrent()) allTags.value = [];
  }
}
function activate() {
  active = true;
  void loadTags();
  void refreshAll();
}
watch(
  identity,
  () => {
    invalidatePage();
    selectedGroupKey.value = undefined;
    query.categoryId = undefined;
    query.dataDomainId = undefined;
    query.layer = undefined;
    void loadTags();
    void refreshAll();
  },
  { flush: 'sync' },
);
onMounted(activate);
onActivated(() => {
  if (!active) activate();
});
onDeactivated(() => {
  active = false;
  invalidatePage();
});
onBeforeUnmount(() => {
  active = false;
  invalidatePage();
});

function syncFilterValues() {
  if (filterFormApi.isMounted)
    void filterFormApi.setValues({
      assetType: query.assetType,
      keyword: query.keyword,
    });
}
</script>

<template>
  <Page
    auto-content-height
    content-class="flex min-h-0 flex-col overflow-hidden"
    data-testid="asset-market-map"
  >
    <Alert
      v-if="!canQuery"
      class="mb-3"
      message="登录身份已失效，请重新登录后查看资产地图。"
      type="warning"
      show-icon
    />
    <Alert
      v-if="facetsError"
      class="mb-3"
      :message="facetsError"
      type="warning"
      show-icon
    >
      <template #action>
        <Button size="small" @click="loadFacets">重新加载</Button>
      </template>
    </Alert>
    <Alert
      v-if="dataDomainsError"
      class="mb-3"
      :message="dataDomainsError"
      type="warning"
      show-icon
    >
      <template #action>
        <Button size="small" @click="loadDataDomains">重新加载</Button>
      </template>
    </Alert>
    <Alert
      v-if="listError"
      class="mb-3"
      :message="listError"
      type="error"
      show-icon
    />
    <div v-if="canQuery" class="asset-layout">
      <aside class="asset-aside">
        <div class="asset-aside__search flex items-center gap-2">
          <Input
            v-model:value="groupKeyword"
            class="min-w-0 flex-1"
            allow-clear
            placeholder="请输入数据板块/数仓分层"
          >
            <template #prefix><IconifyIcon icon="lucide:search" /></template>
          </Input>
          <TreeToolbar
            :expanded="isGroupExpanded"
            :loading="groupLoading"
            @toggle-expand="toggleExpandAll"
            @refresh="refreshGroups"
          />
        </div>
        <div class="asset-aside__tree subtle-tree-scrollbar">
          <Tree
            v-model:expanded-keys="expandedKeys"
            :selected-keys="selectedGroupKey ? [selectedGroupKey] : []"
            :tree-data="groupTree"
            block-node
            @select="onGroupSelect"
          >
            <template #titleRender="node">
              <div class="asset-aside__node">
                <img
                  :src="`/assets/qdata-market/icon-${
                    node.children?.length ? 'folder' : 'document'
                  }.png`"
                  alt=""
                  aria-hidden="true"
                /><span class="truncate">{{ node.title }}</span>
              </div>
            </template>
          </Tree>
        </div>
        <div class="asset-aside__footer">
          <Button size="small" type="text" @click="selectAll">全部资产</Button>
        </div>
      </aside>
      <main class="asset-main">
        <FilterForm
          @vue:mounted="syncFilterValues"
          class="rounded-lg bg-background p-4"
        >
          <template #expand-after>
            <SpaceCompact v-access:code="'asset:data-asset:create'">
              <Button type="primary" @click="openRegister()">
                <template #icon>
                  <IconifyIcon class="size-4" icon="lucide:plus" />
                </template>
                注册
              </Button>
              <Dropdown :trigger="['click']">
                <Button type="primary" aria-label="选择注册资产类型">
                  <template #icon>
                    <IconifyIcon class="size-4" icon="lucide:chevron-down" />
                  </template>
                </Button>
                <template #popupRender>
                  <Menu>
                    <Menu.Item key="table" @click="openRegister(1)">
                      数据库表
                    </Menu.Item>
                    <Menu.Item key="api" @click="openRegister(2)">
                      外部API
                    </Menu.Item>
                    <Menu.Item key="file" @click="openRegister(3)">
                      非结构化数据
                    </Menu.Item>
                  </Menu>
                </template>
              </Dropdown>
            </SpaceCompact>
          </template>
        </FilterForm>
        <div class="asset-list-wrap">
          <Spin :spinning="loading" class="asset-list-spin">
            <div v-if="assets.length" class="asset-list">
              <article
                v-for="asset in assets"
                :key="String(asset.id)"
                class="asset-card"
              >
                <header class="asset-card__header">
                  <button
                    type="button"
                    class="asset-card__title"
                    @click="openDetail(asset)"
                  >
                    <img :src="mapAssetIcon()" alt="" aria-hidden="true" />{{
                      asset.assetName
                    }}
</button><span class="asset-card__type">
                    <img
                      :src="
                        mapSvg(
                          asset.assetType === 1
                            ? 'da-database'
                            : asset.assetType === 2
                              ? 'da-api'
                              : 'da-document',
                        )
                      "
                      alt=""
                      aria-hidden="true"
                    />{{
                      ASSET_TYPE_OPTIONS.find(
                        (option) => option.value === asset.assetType,
                      )?.label || '未知类型'
                    }} </span><Tag
                    class="asset-card__status"
                    :class="{
                      'asset-card__status--published': asset.status === 1,
                    }"
                  >
                    {{ statusMeta(asset.status).label }}
</Tag><Tag v-if="asset.metadataDrifted" color="volcano">
                    元数据变更
                  </Tag>
                  <div v-if="asset.assetType === 1" class="asset-card__metrics">
                    <span><img
                        :src="mapSvg('row-count')"
                        alt=""
                        aria-hidden="true"
                      />{{ formatCount(asset.rowCount) }}行</span><i></i><span><img
                        :src="mapSvg('column-count')"
                        alt=""
                        aria-hidden="true"
                      />{{ asset.fieldCount ?? '-' }}列</span><i></i><span><img
                        :src="mapSvg('map-score')"
                        alt=""
                        aria-hidden="true"
                      />{{
                        asset.mapDisplayScore ?? asset.healthScore ?? '-'
                      }}分</span>
                  </div>
                </header>
                <div class="asset-card__facts">
                  <span><label>{{
                      asset.assetType === 3
                        ? '文件名称: '
                        : asset.assetType === 2
                          ? 'API名称: '
                          : '表名称: '
                    }}</label>{{
                      asset.fileName ||
                      asset.tableName ||
                      asset.assetName ||
                      '-'
                    }}</span><span v-if="asset.assetType === 1"><label>表类型: </label><span class="asset-card__kind"><img
                        :src="mapSvg('table-kind')"
                        alt=""
                        aria-hidden="true"
                      />{{ tableKind(asset).label }}</span></span><template v-if="asset.assetType === 1">
                    <span><label>归属信息: </label><img
                        :src="mapSvg('affiliation')"
                        alt=""
                        aria-hidden="true"
                      />{{
                        asset.registrationConfig?.dataDomainName ||
                        asset.categoryName ||
                        '-'
                      }}
                      / {{ asset.subjectDomainName || '-' }}</span>
</template><template v-else>
                    <span><label>业务板块: </label>{{
                        asset.registrationConfig?.dataDomainName ||
                        asset.categoryName ||
                        '-'
                      }}</span><span><label>主题域: </label>{{ asset.subjectDomainName || '-' }}</span>
</template><span><label>{{
                      asset.assetType === 1 ? '数据时效: ' : '创建时间: '
                    }}</label>{{
                      asset.assetType === 1
                        ? asset.updateCycle || '-'
                        : formatDateTime(asset.createTime).slice(0, 16) || '-'
                    }}</span>
                </div>
                <p class="asset-card__description">
                  <label>资产描述: </label>{{ asset.description || '-' }}
                </p>
                <footer class="asset-card__footer">
                  <div class="asset-card__tags">
                    <label>数据标签: </label><Tag
                      v-for="tag in asset.tags"
                      :key="String(tag.id)"
                      class="asset-card__tag"
                    >
                      {{ tag.name }}
</Tag><span v-if="!asset.tags?.length">-</span>
                  </div>
                  <div class="asset-card__actions">
                    <Button
                      type="link"
                      class="asset-card__detail"
                      @click="openDetail(asset)"
                    >
                      <template #icon>
                        <img
                          :src="mapSvg('detail')"
                          alt=""
                          aria-hidden="true"
                        />
</template>详情
</Button><Button
                      v-access:code="'asset:data-asset:publish'"
                      type="link"
                      :class="
                        asset.status === 1
                          ? 'asset-card__unpublish'
                          : 'asset-card__publish'
                      "
                      :danger="asset.status === 1"
                      :disabled="asset.retirementRequested"
                      @click="togglePublish(asset)"
                    >
                      <template #icon>
                        <img
                          :src="
                            mapSvg(asset.status === 1 ? 'unpublish' : 'publish')
                          "
                          alt=""
                          aria-hidden="true"
                        />
</template>{{
                        asset.status === 1 ? '撤销发布' : '发布至集市'
                      }}
</Button><Dropdown
                      v-if="moreActions(asset).length"
                      :trigger="['click']"
                    >
                      <Button type="link" class="asset-card__more">
                        <img
                          :src="mapSvg('more')"
                          alt=""
                          aria-hidden="true"
                        />更多
</Button><template #popupRender>
                        <Menu>
                          <Menu.Item
                            v-for="action in moreActions(asset)"
                            :key="action.key"
                            :danger="action.danger"
                            @click="action.onClick()"
                          >
                            <span class="inline-flex items-center gap-2"><IconifyIcon :icon="action.icon" />{{
                                action.label
                              }}</span>
                          </Menu.Item>
                        </Menu>
                      </template>
                    </Dropdown>
                  </div>
                </footer>
                <p v-if="asset.retirementRequested" class="asset-card__notice">
                  退役中，请确认目录解绑回执后再次删除。
                </p>
                <p v-if="asset.publicationNotice" class="asset-card__notice">
                  {{ asset.publicationNotice }}
                </p>
              </article>
            </div>
            <Empty
              v-else
              class="asset-empty"
              description="暂无符合条件的数据资产"
            >
              <Button
                v-access:code="'asset:data-asset:create'"
                type="primary"
                @click="openRegister()"
              >
                注册资产
              </Button>
            </Empty>
          </Spin>
        </div>
        <ListPagination
          v-if="pagination.total > 0"
          v-model:current="pagination.pageNo"
          v-model:page-size="pagination.pageSize"
          :total="pagination.total"
          class="asset-pagination"
          @change="loadAssets"
        />
      </main>
    </div>
    <Modal
      v-model:open="draftOpen"
      :confirm-loading="draftSaving"
      title="创建未挂接技术资源的目录草稿"
      width="800px"
      @ok="saveCatalogDraft"
    >
      <Form
        :label-col="{ style: { width: '130px' } }"
        :wrapper-col="{ style: { flex: '1 1 0' } }"
      >
        <FormItem label="目录名称" required>
          <Input v-model:value="draftForm.assetName" :maxlength="256" />
        </FormItem>
        <FormItem label="说明">
          <Input.TextArea
            v-model:value="draftForm.description"
            :maxlength="2000"
            :rows="3"
          />
        </FormItem>
      </Form>
    </Modal>

    <Modal
      v-model:open="editOpen"
      :confirm-loading="editSaving"
      :ok-button-props="{ disabled: editLoading || !!editError }"
      title="修改数据资产"
      width="1200px"
      :style="{ top: '60px', maxWidth: 'calc(100vw - 32px)' }"
      @ok="saveEdit"
      @cancel="editGuard.invalidate()"
    >
      <Alert v-if="editError" :message="editError" type="error" show-icon />
      <Spin :spinning="editLoading">
        <Form
          :label-col="{ style: { width: '110px' } }"
          :wrapper-col="{ style: { flex: '1', minWidth: '0' } }"
          :colon="false"
          class="asset-edit-form"
        >
          <FormItem label="资产类型">
            <Input
              :value="
                ASSET_TYPE_OPTIONS.find(
                  (option) => option.value === editForm.assetType,
                )?.label || '-'
              "
              disabled
            />
          </FormItem>
          <FormItem v-if="editForm.assetType === 1" label="表类型">
            <Input :value="tableKind(editForm).label" disabled />
          </FormItem>
          <FormItem label="资产中文名称" required>
            <Input
              v-model:value="editForm.assetName"
              :maxlength="256"
              placeholder="请输入资产中文名称"
            />
          </FormItem>
          <FormItem label="资产英文名称">
            <Input :value="editForm.tableName || '-'" disabled />
          </FormItem>
          <FormItem label="数据连接名称">
            <Input :value="editForm.datasourceName || '-'" disabled />
          </FormItem>
          <FormItem label="数据连接类型">
            <Input :value="editForm.datasourceType || '-'" disabled />
          </FormItem>
          <FormItem label="业务板块">
            <Select
              :value="editForm.registrationConfig?.dataDomainId"
              :options="editDomainOptions"
              allow-clear
              placeholder="请选择数据板块"
              :disabled="editSaving || editLoading"
              @change="changeEditDomain"
            />
          </FormItem>
          <FormItem label="资产分类">
            <TreeSelect
              v-model:value="editForm.categoryId"
              :field-names="{
                children: 'children',
                label: 'title',
                value: 'value',
              }"
              :tree-data="categoryTreeForSelect"
              allow-clear
              placeholder="请选择资产分类"
              tree-default-expand-all
              show-search
              tree-node-filter-prop="title"
            />
          </FormItem>
          <FormItem label="主题域">
            <Select
              :value="editForm.subjectDomainId"
              @change="changeEditSubject"
              :options="subjectOptions"
              allow-clear
              placeholder="请选择主题域"
            />
          </FormItem>
          <FormItem label="描述" class="asset-edit-form__description">
            <Input.TextArea
              v-model:value="editForm.description"
              :maxlength="2000"
              :rows="4"
              show-count
              placeholder="请输入描述"
            />
          </FormItem>
        </Form>
      </Spin>
    </Modal>

    <Modal
      v-model:open="tagOpen"
      :confirm-loading="tagSaving"
      title="新增标签"
      width="600px"
      :style="{ top: '60px' }"
      @ok="saveTags"
    >
      <Alert v-if="tagError" :message="tagError" type="error" show-icon /><Spin
        :spinning="tagLoading"
      >
        <div class="flex items-start gap-2 py-2">
          <label class="shrink-0 pt-1">标签</label>
          <Select
            v-model:value="selectedTagIds"
            :options="tagOptions"
            class="min-w-0 flex-1"
            mode="multiple"
            option-filter-prop="label"
            placeholder="请选择标签"
            show-search
          />
          <Button v-access:code="'asset:tag:create'" @click="goTagManage">
            <template #icon><IconifyIcon icon="lucide:plus" /></template>
            新增标签
          </Button>
        </div>
      </Spin>
    </Modal>

    <TagCreateModal @success="tagCreated" />
    <MarketPublishModal @success="refreshAll" />
  </Page>
  <Modal
    :open="!!stopTarget"
    title="停止全部供数"
    :confirm-loading="stopping"
    @cancel="stopTarget = undefined"
    @ok="stopSupply"
  >
    <p>
      停止新增申请并撤销既有授权。远端撤权可能仍在处理中，请以撤权和交付回执为准。
    </p>
    <Input.TextArea
      v-model:value="stopReason"
      :maxlength="500"
      :rows="4"
      placeholder="填写停止供数原因"
    />
  </Modal>
</template>

<style scoped>
.asset-layout {
  display: flex;
  flex: 1;
  gap: 16px;
  min-width: 0;
  min-height: 0;
  font-size: 14px;
  color: hsl(var(--foreground));
}

.asset-aside {
  display: flex;
  flex: 0 0 280px;
  flex-direction: column;
  min-width: 0;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.asset-aside__search {
  padding: 10px;
}

.asset-aside__tree {
  flex: 1;
  min-height: 0;
  padding: 0 12px;
  overflow: auto;
}

.asset-aside__tree :deep(.ant-tree-treenode) {
  padding: 0;
}

.asset-aside__tree :deep(.ant-tree-node-content-wrapper) {
  flex: 1;
  min-width: 0;
}

.asset-aside__tree :deep(.ant-tree-switcher) {
  width: 16px;
  height: 26px;
  line-height: 26px;
  color: hsl(var(--muted-foreground));
}

.asset-aside__node {
  display: flex;
  gap: 6px;
  align-items: center;
  min-width: 0;
  height: 26px;
  font-size: 14px;
}

.asset-aside__node > img {
  flex: none;
  width: 18px;
  height: 18px;
  object-fit: contain;
}

.asset-aside__footer {
  display: flex;
  justify-content: space-between;
  padding: 4px;
}

.asset-main {
  display: flex;
  flex: 1;
  flex-direction: column;
  gap: 16px;
  min-width: 0;
  min-height: 0;
}

.asset-list-wrap {
  flex: 1;
  min-height: 0;
  overflow: auto;
}

.asset-list-spin {
  display: block;
  min-height: 100%;
}

.asset-card {
  min-height: 160px;
  padding: 18px 18px 14px;
  margin-bottom: 16px;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.asset-card:last-child {
  margin-bottom: 0;
}

.asset-card__header {
  display: flex;
  gap: 8px;
  align-items: center;
  min-height: 34px;
  padding-bottom: 10px;
  margin-bottom: 12px;
  border-bottom: 1px solid hsl(var(--muted));
}

.asset-card__title {
  display: inline-flex;
  gap: 10px;
  align-items: center;
  padding: 0;
  font-size: 14px;
  font-weight: 500;
  color: hsl(var(--primary));
  text-align: left;
  cursor: pointer;
  background: none;
  border: 0;
}

.asset-card__title > img {
  width: 22px;
  height: 20px;
  object-fit: contain;
}

.asset-card__type {
  display: inline-flex;
  flex: none;
  gap: 4px;
  align-items: center;
  padding: 0 7px;
  margin: 0;
  line-height: 22px;
  color: hsl(var(--primary));
  background: #f0f8ff;
  border: 1px solid #bce1ff;
  border-radius: calc(var(--radius) - 2px);
}

.asset-card__type > img {
  width: 14px;
  height: 14px;
  object-fit: contain;
}

.asset-card__status {
  flex: none;
  padding: 0 7px;
  margin: 0;
  line-height: 24px;
  color: hsl(var(--primary-foreground));
  background: #e99a22;
  border: 0;
  border-radius: calc(var(--radius) - 2px);
}

.asset-card__status--published {
  background: #4da483;
}

.asset-card__metrics {
  display: flex;
  flex: none;
  gap: 8px;
  align-items: center;
  margin-left: auto;
  color: #606c87;
}

.asset-card__metrics > span {
  display: inline-flex;
  gap: 4px;
  align-items: center;
}

.asset-card__metrics img {
  width: 14px;
  height: 14px;
  object-fit: contain;
}

.asset-card__metrics > i {
  width: 1px;
  height: 12px;
  background: hsl(var(--border));
}

.asset-card__facts {
  display: grid;
  grid-template-columns: 1.2fr 0.75fr 1.65fr 1.2fr;
  gap: 14px;
  align-items: center;
  font-size: 14px;
  line-height: 24px;
}

.asset-card__facts > span {
  display: flex;
  gap: 4px;
  align-items: center;
  min-width: 0;
  overflow-wrap: anywhere;
}

.asset-card__facts > span > img {
  width: 16px;
  height: 16px;
}

.asset-card label {
  flex: none;
  color: hsl(var(--muted-foreground));
}

.asset-card__kind {
  display: inline-flex;
  gap: 4px;
  align-items: center;
  padding: 0 6px;
  margin: 0;
  line-height: 20px;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
  border: 0;
  border-radius: calc(var(--radius) - 2px);
}

.asset-card__kind img {
  width: 12px;
  height: 12px;
}

.asset-card__description {
  margin: 4px 0;
  font-size: 14px;
  line-height: 24px;
}

.asset-card__footer {
  display: flex;
  gap: 10px;
  align-items: flex-end;
  min-height: 24px;
  font-size: 14px;
}

.asset-card__tags {
  display: flex;
  flex: 1;
  flex-wrap: wrap;
  gap: 6px;
  align-items: center;
  min-width: 0;
}

.asset-card__tag {
  padding: 0 7px;
  margin: 0;
  font-size: 14px;
  line-height: 24px;
  color: hsl(var(--primary));
  background: hsl(var(--primary) / 10%);
  border: 0;
  border-radius: calc(var(--radius) - 2px);
}

.asset-card__actions {
  display: flex;
  flex: none;
  gap: 10px;
  align-items: center;
}

.asset-card__actions img {
  width: 14px;
  height: 14px;
  object-fit: contain;
}

.asset-card__actions :deep(svg) {
  width: 12px;
  height: 12px;
}

.asset-card__notice {
  margin: 8px 0 0;
  color: #e6a23c;
}

.asset-empty {
  padding: 100px 0;
}

.asset-pagination {
  display: flex;
  flex: none;
  justify-content: flex-end;
  padding: 14px;
  background: hsl(var(--background));
  border-radius: var(--radius);
}

.asset-edit-form {
  display: grid;
  grid-template-columns: minmax(0, 1fr) minmax(0, 1fr);
  gap: 16px 20px;
  padding: 20px 40px;
}

.asset-edit-form :deep(.ant-form-item) {
  margin-bottom: 0;
}

.asset-edit-form__description {
  grid-column: 1 / -1;
}

@media (width <= 900px) {
  .asset-edit-form {
    padding: 16px 0;
  }
}

@media (width <= 640px) {
  .asset-edit-form {
    grid-template-columns: minmax(0, 1fr);
  }
}

@media (width <= 1400px) {
  .asset-card__header {
    flex-wrap: wrap;
  }

  .asset-card__facts {
    grid-template-columns: 1fr 1fr;
  }
}

@media (width <= 900px) {
  .asset-aside {
    flex-basis: 220px;
  }

  .asset-card__metrics {
    flex-basis: 100%;
    margin-left: 34px;
  }

  .asset-card__footer {
    flex-wrap: wrap;
  }

  .asset-card__actions {
    flex-basis: 100%;
    justify-content: flex-end;
  }
}

@media (width <= 640px) {
  .asset-layout {
    flex-direction: column;
    overflow: auto;
  }

  .asset-aside {
    flex: none;
    max-height: 280px;
  }

  .asset-aside__tree {
    min-height: 140px;
  }

  .asset-main {
    flex: none;
  }

  .asset-list-wrap {
    overflow: visible;
  }

  .asset-card {
    padding: 14px;
  }

  .asset-card__facts {
    grid-template-columns: minmax(0, 1fr);
  }

  .asset-card__title {
    flex: 1;
    font-size: 14px;
  }
}
</style>
