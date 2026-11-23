import { effectScope, nextTick, ref } from 'vue';

import { afterEach, beforeEach, describe, expect, it, vi } from 'vitest';

import { useAvatarFallback } from './use-avatar-fallback';

/** 可手动触发加载失败的 Image 替身 */
class FakeImage {
  static instances: FakeImage[] = [];
  src = '';
  private errorListeners: Array<() => void> = [];

  constructor() {
    FakeImage.instances.push(this);
  }

  addEventListener(type: string, listener: () => void) {
    if (type === 'error') this.errorListeners.push(listener);
  }

  fail() {
    this.errorListeners.forEach((listener) => listener());
  }
}

const DEFAULT_AVATAR = '/avatar.svg';

describe('useAvatarFallback', () => {
  beforeEach(() => {
    FakeImage.instances = [];
    vi.stubGlobal('Image', FakeImage);
  });

  afterEach(() => {
    vi.unstubAllGlobals();
  });

  it('uses the default avatar when the user has none', () => {
    const scope = effectScope();
    const avatar = scope.run(() => useAvatarFallback('', DEFAULT_AVATAR))!;

    expect(avatar.value).toBe(DEFAULT_AVATAR);
    expect(FakeImage.instances).toHaveLength(0);
    scope.stop();
  });

  it('shows the user avatar first and falls back when it fails to load', () => {
    const scope = effectScope();
    const avatar = scope.run(() =>
      useAvatarFallback('https://broken.example/a.png', DEFAULT_AVATAR),
    )!;

    expect(avatar.value).toBe('https://broken.example/a.png');
    FakeImage.instances[0]!.fail();
    expect(avatar.value).toBe(DEFAULT_AVATAR);
    scope.stop();
  });

  it('ignores a stale failure after the avatar url changes', async () => {
    const src = ref('https://broken.example/old.png');
    const scope = effectScope();
    const avatar = scope.run(() => useAvatarFallback(src, DEFAULT_AVATAR))!;

    src.value = 'https://cdn.example/new.png';
    await nextTick();
    FakeImage.instances[0]!.fail();

    expect(avatar.value).toBe('https://cdn.example/new.png');
    scope.stop();
  });

  it('ignores failures that arrive after the scope is disposed', () => {
    const scope = effectScope();
    const avatar = scope.run(() =>
      useAvatarFallback('https://broken.example/a.png', DEFAULT_AVATAR),
    )!;

    scope.stop();
    FakeImage.instances[0]!.fail();

    expect(avatar.value).toBe('https://broken.example/a.png');
  });
});
