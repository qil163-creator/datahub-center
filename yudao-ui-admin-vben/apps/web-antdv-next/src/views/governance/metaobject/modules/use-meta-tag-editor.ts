import type { GovernanceMetaTagApi } from '#/api/governance/metatag';

import { computed, ref } from 'vue';

import {
  bindMetaTags,
  getMetaTagBindingSnapshot,
  getMetaTagSimpleList,
} from '#/api/governance/metatag';
import { useObjectRequest } from '#/hooks/use-object-request';

/** 标签全量覆盖必须以成功读取的绑定快照和修订号为基础。 */
export function useMetaTagEditor(
  objectId: () => GovernanceMetaTagApi.Id | undefined,
) {
  const boundTags = ref<GovernanceMetaTagApi.Tag[]>([]);
  const selectedTagIds = ref<string[]>([]);
  const originalTagIds = ref<string[]>([]);
  const tagOptions = ref<{ label: string; value: string }[]>([]);
  const revision = ref<number>();
  const loading = ref(false);
  const saving = ref(false);
  const error = ref('');
  const request = useObjectRequest(objectId, reset);
  const ready = computed(
    () => revision.value !== undefined && !error.value && !loading.value,
  );
  const dirty = computed(() => {
    const original = new Set(originalTagIds.value);
    return (
      original.size !== selectedTagIds.value.length ||
      selectedTagIds.value.some((id) => !original.has(id))
    );
  });
  const clearsAll = computed(
    () => originalTagIds.value.length > 0 && selectedTagIds.value.length === 0,
  );

  function reset() {
    boundTags.value = [];
    selectedTagIds.value = [];
    originalTagIds.value = [];
    tagOptions.value = [];
    revision.value = undefined;
    loading.value = false;
    saving.value = false;
    error.value = '';
  }

  async function load() {
    if (saving.value) return;
    const id = objectId();
    if (id === undefined) return;
    const current = request.begin();
    loading.value = true;
    error.value = '';
    revision.value = undefined;
    try {
      const [binding, options] = await Promise.all([
        getMetaTagBindingSnapshot(2, id),
        getMetaTagSimpleList(),
      ]);
      if (!current()) return;
      boundTags.value = binding.tags;
      selectedTagIds.value = binding.tagIds.map(String);
      originalTagIds.value = [...selectedTagIds.value];
      const available = new Map(options.map((tag) => [String(tag.id), tag]));
      for (const tag of binding.tags) available.set(String(tag.id), tag);
      tagOptions.value = [...available.values()].map((tag) => ({
        label: tag.name ?? `标签#${tag.id}`,
        value: String(tag.id),
      }));
      revision.value = binding.revision;
    } catch {
      if (current())
        error.value = '标签读取失败，重新加载成功后才能编辑和保存。';
    } finally {
      if (current()) loading.value = false;
    }
  }

  async function save() {
    const id = objectId();
    if (id === undefined || !ready.value || saving.value || !dirty.value)
      return false;
    const current = request.begin();
    saving.value = true;
    try {
      await bindMetaTags({
        objectType: 2,
        objectId: id,
        revision: revision.value!,
        tagIds: [...selectedTagIds.value],
      });
      if (!current()) return false;
      saving.value = false;
      await load();
      return ready.value;
    } catch {
      if (current()) {
        revision.value = undefined;
        error.value = '标签保存未完成或版本已变化，请重新加载核对后再编辑。';
      }
      return false;
    } finally {
      if (current()) saving.value = false;
    }
  }

  return {
    boundTags,
    clearsAll,
    dirty,
    error,
    load,
    loading,
    ready,
    save,
    saving,
    selectedTagIds,
    tagOptions,
  };
}
