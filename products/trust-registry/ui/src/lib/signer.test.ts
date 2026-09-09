import { describe, expect, it } from 'vitest';
import {
  actionCommitment,
  asciiKey,
  encodeAction,
  encodeCommand,
  hexToBytes,
  parseSeed,
  put,
  signingPreimage,
  toHex,
  unsignedStatement
} from './signer';

/**
 * The same vectors `BrowserSigningVectorTest` pins on the Java side, so the browser encoder and
 * the stock contracts cannot drift apart without a test failing on one side or the other.
 */
const PUBLIC_KEY = 'd04ab232742bb4ab3a1368bd4615e4e6d0224ab71a016baf8520a332c9778737';
const ACTION = '84010081870066737461747573486c6973742d312f35448301010300404081840003'
  + '6c6973737565722d777269746501';
const COMMITMENT = '77fba8f56dd2960fae14bb60ec375a4a7ff8b15033efc6764fbae16c6d81e74e';
const STATEMENT = '8f015820'
  + '2222222222222222222222222222222222222222222222222222222222222222'
  + '7474727573742d72656769737472792d636861696e'
  + '5820' + '3333333333333333333333333333333333333333333333333333333333333333'
  + '5820' + COMMITMENT
  + '8100'
  + '6c6973737565722d7772697465' + '01'
  + '686973737565722d61' + '01'
  + '6b6973737565722d612d6b31'
  + '5820' + PUBLIC_KEY
  + '07' + '186b' + '00';
const SIGNATURE = '575570d7c3877ec43f03b98f0ea9e1bb89deb6170c038d9010c4472234b14efc'
  + '091c7f6d28f6bc77f60ad2bc283858905e486a33a866db4d485e517487ff8802';

// status/list-1/5 set to bit 1 with reason 3, the value the profile codec produces.
const MUTATION = put('status', asciiKey('list-1/5'), hexToBytes('83010103'));
const POLICY = 'issuer-write';

describe('the governed command encoder', () => {
  it('produces the action bytes the contracts produce', () => {
    expect(toHex(encodeAction(false, [MUTATION], POLICY))).toBe(ACTION);
  });

  it('commits the action with the domain-separated blake2b hash', () => {
    expect(toHex(actionCommitment(false, [MUTATION], POLICY))).toBe(COMMITMENT);
  });

  it('produces the actor authorization statement', () => {
    const statement = unsignedStatement({
      authorizationId: hexToBytes('22'.repeat(32)),
      chainId: 'trust-registry-chain',
      genesisId: hexToBytes('33'.repeat(32)),
      actionCommitment: hexToBytes(COMMITMENT),
      coveredMutationIndexes: [0],
      policyId: POLICY,
      policyRevision: 1n,
      actorId: 'issuer-a',
      actorRevision: 1n,
      keyId: 'issuer-a-k1',
      publicKey: hexToBytes(PUBLIC_KEY),
      issuedHeight: 7n,
      deadlineHeight: 107n
    });
    expect(toHex(statement)).toBe(STATEMENT);
  });

  it('prefixes the signing preimage with the domain and the statement length', () => {
    const preimage = signingPreimage(hexToBytes('0102'));
    const domain = asciiKey('yano:authenticated-map:actor-authorization:v1\0');
    expect(preimage.length).toBe(domain.length + 4 + 2);
    expect(toHex(preimage.slice(0, domain.length))).toBe(toHex(domain));
    expect(toHex(preimage.slice(domain.length))).toBe('000000020102');
  });

  it('wraps the action and the authorization into the command the node accepts', () => {
    const authorization = hexToBytes('83' + '0158c9' + STATEMENT + '5840' + SIGNATURE);
    const command = toHex(encodeCommand(hexToBytes(ACTION), authorization));
    expect(command.startsWith('830158' + (ACTION.length / 2).toString(16) + ACTION)).toBe(true);
    expect(command).toContain(STATEMENT);
    expect(command.endsWith(SIGNATURE)).toBe(true);
  });

  it('sorts and checks the covered mutation indexes', () => {
    const fields = {
      authorizationId: hexToBytes('22'.repeat(32)),
      chainId: 'c',
      genesisId: hexToBytes('33'.repeat(32)),
      actionCommitment: hexToBytes(COMMITMENT),
      policyId: POLICY,
      policyRevision: 1n,
      actorId: 'a',
      actorRevision: 1n,
      keyId: 'k',
      publicKey: hexToBytes(PUBLIC_KEY),
      issuedHeight: 1n,
      deadlineHeight: 2n
    };
    expect(toHex(unsignedStatement({ ...fields, coveredMutationIndexes: [1, 0] })))
      .toBe(toHex(unsignedStatement({ ...fields, coveredMutationIndexes: [0, 1] })));
    expect(() => unsignedStatement({ ...fields, coveredMutationIndexes: [0, 0] }))
      .toThrow('distinct');
  });
});

describe('parseSeed', () => {
  it('accepts hex text and raw bytes and refuses anything else', () => {
    expect(toHex(parseSeed(' 11'.repeat(32) + '\n'))).toBe('11'.repeat(32));
    expect(toHex(parseSeed(new Uint8Array(32).fill(2)))).toBe('02'.repeat(32));
    expect(() => parseSeed('abcd')).toThrow('32 bytes');
    expect(() => parseSeed(new Uint8Array(31))).toThrow('32 bytes');
  });
});
