import { describe, expect, it } from 'vitest';
import { explorerLink } from './display';

describe('explorerLink', () => {
  const template = 'https://explorer.example/tx/{tx}';
  const id = 'ab'.repeat(32);

  it('links a 32-byte hex transaction id', () => {
    expect(explorerLink(template, id)).toBe(`https://explorer.example/tx/${id}`);
  });

  it('refuses anything else the node might send', () => {
    expect(explorerLink(template, '../../evil?x=1')).toBeNull();
    expect(explorerLink(template, `${id}#frag`)).toBeNull();
    expect(explorerLink(template, null)).toBeNull();
    expect(explorerLink('', id)).toBeNull();
  });
});
