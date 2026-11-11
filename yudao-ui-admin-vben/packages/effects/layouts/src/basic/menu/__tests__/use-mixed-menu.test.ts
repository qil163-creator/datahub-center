import type { MenuRecordRaw } from '@vben/types';

import { describe, expect, it } from 'vitest';

import { resolveRootMenu } from '../use-mixed-menu';

function menu(
  path: string,
  children: MenuRecordRaw[] = [],
  parents: string[] = [],
): MenuRecordRaw {
  return {
    children,
    name: path,
    parents: [...parents, path],
    path,
  };
}

const menus: MenuRecordRaw[] = [
  menu('/external'),
  menu('/basic', [menu('/basic/home', [], ['/basic'])]),
  menu('/model', [menu('/model/home', [], ['/model'])]),
];

describe('resolveRootMenu', () => {
  it('returns the root menu that owns the current path', () => {
    expect(resolveRootMenu(menus, '/model/home', '/basic')).toEqual({
      matched: true,
      rootMenu: menus[2],
    });
  });

  it('defaults a page outside the menus to the first root menu with children', () => {
    expect(resolveRootMenu(menus, '/home')).toEqual({
      matched: false,
      rootMenu: menus[1],
    });
  });

  it('keeps the selected root menu for a page outside the menus', () => {
    expect(resolveRootMenu(menus, '/home', '/model')).toEqual({
      matched: false,
      rootMenu: menus[2],
    });
  });

  it('falls back to the first root menu when the selected one no longer exists', () => {
    expect(resolveRootMenu(menus, '/home', '/removed')).toEqual({
      matched: false,
      rootMenu: menus[1],
    });
  });
});
