import { describe, expect, it } from 'vitest';
import { MalformedResponse, newestFirst, parseBlock, parseBlockPage, parseTip } from './chain';

const ROOT = '2b'.repeat(32);

describe('chain responses', () => {
  it('reads a tip and a block page', () => {
    expect(parseTip({ chainId: 'c', height: 10, stateRoot: ROOT })).toEqual({ height: 10, stateRoot: ROOT });
    const page = parseBlockPage({ tipHeight: 2, blocks: [{ height: 2, timestamp: 5, stateRoot: ROOT, messageCount: 1,
      certSignatures: 1 }] });
    expect(page.blocks[0].height).toBe(2);
  });

  it('refuses a malformed tip or block header', () => {
    expect(() => parseTip({ height: '10', stateRoot: ROOT })).toThrow(MalformedResponse);
    expect(() => parseTip({ height: 1, stateRoot: '<b>' })).toThrow(MalformedResponse);
    expect(() => parseBlockPage({ tipHeight: 1, blocks: 'x' })).toThrow(MalformedResponse);
    expect(() => parseBlock(null)).toThrow(MalformedResponse);
  });

  it('places a block\'s messages by the block, keeping malformed entries', () => {
    const block = parseBlock({ height: 7, chainId: 'c', prevHash: ROOT, timestamp: 1, messagesRoot: ROOT, stateRoot: ROOT,
      proposer: 'ab', certSignatures: 1, messages: [
        { messageId: 'cd'.repeat(32), topic: 't', sender: 'ab', senderSeq: 3, bodyHex: '80', height: 99, index: 5 },
        'not an object',
      ] });
    expect(block.messages[0]).toMatchObject({ height: 7, index: 0, size: 1, senderSeq: '3' });
    expect(block.messages[0].malformed).toBeUndefined();
    expect(block.messages[1]).toMatchObject({ height: 7, index: 1, messageId: '', bodyHex: '' });
    expect(block.messages[1].malformed).toBeTruthy();
  });

  it('orders newest first', () => {
    const list = [{ height: 1, index: 0 }, { height: 2, index: 0 }, { height: 2, index: 1 }].sort(newestFirst);
    expect(list).toEqual([{ height: 2, index: 1 }, { height: 2, index: 0 }, { height: 1, index: 0 }]);
  });
});
