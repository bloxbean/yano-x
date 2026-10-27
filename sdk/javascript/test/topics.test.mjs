import test from 'node:test';
import assert from 'node:assert/strict';
import { readFileSync } from 'node:fs';
import { createPrivateKey, createPublicKey } from 'node:crypto';
import { performance } from 'node:perf_hooks';
import { blake2b256, createSessionKey, decodeWire, DelegatedSession, delegationHash,
  delegationSigningBytes, encodeCbor, encodeWire, hex, issueDelegation, isValidEntryName, nonce,
  refusalCode, signerFromKey, signRegistry, signWrite, submitFrame, topicRequest, TopicError, unhex,
  verifyDelegation, verifyFrameSignatures, verifySignature, writeStatement } from '../topics.mjs';
import { decodeCbor } from '../cbor.mjs';

const fixture = JSON.parse(readFileSync(new URL('./vectors.json', import.meta.url)));
const edge = JSON.parse(readFileSync(new URL('./ed25519-edge-vectors.json', import.meta.url)));
const { chainId, generationId } = fixture;
const vector = name => fixture.vectors.find(value => value.name === name);
const bytes = name => unhex(vector(name).hex);

async function signer(seedByte) {
  const der = Buffer.concat([Buffer.from('302e020100300506032b657004220420', 'hex'), Buffer.alloc(32, seedByte)]);
  const privateKey = await crypto.subtle.importKey('pkcs8', der, 'Ed25519', false, ['sign']);
  const key = createPrivateKey({ key: der, format: 'der', type: 'pkcs8' });
  const publicKey = createPublicKey(key).export({ format: 'jwk' }).x;
  return signerFromKey({ privateKey, publicKey: Buffer.from(publicKey, 'base64url').toString('hex') });
}

for (const value of fixture.vectors) {
  test(`frozen core vector: ${value.name}`, () => {
    if (value.invalid) assert.throws(() => decodeWire(value.type, unhex(value.hex)), undefined, value.invalid);
    else {
      const decoded = decodeWire(value.type, unhex(value.hex));
      assert.equal(hex(encodeWire(value.type, decoded)), value.hex);
      if (value.digest) assert.equal(hex(blake2b256(unhex(value.hex))), value.digest);
    }
  });
}

test('wire corpus is complete and all source vectors remain present', () => {
  assert.equal(fixture.vectors.filter(value => !value.invalid).length, 33);
  assert.equal(fixture.vectors.filter(value => value.invalid).length, 23);
});

test('delegation bytes, hash and issuer signature reproduce the independent vector', async () => {
  const expected = decodeWire('delegation', bytes('delegation'));
  const actual = await issueDelegation({ chainId, generationId, delegate: hex(expected[1]), scope: expected[2],
    notBefore: expected[3], notAfter: expected[4], issuer: await signer(3) });
  assert.equal(hex(actual), vector('delegation').hex);
  assert.equal(hex(delegationSigningBytes(chainId, generationId, actual)), vector('delegation').signedBytes);
  assert.equal(delegationHash(chainId, generationId, actual), vector('delegation').delegationHash);
  assert(await verifyDelegation(chainId, generationId, actual));
  assert(!await verifyDelegation(chainId, 'ff'.repeat(32), actual));
});

for (const name of ['frame-delegated', 'frame-direct-2-of-2']) {
  test(`construct and sign ${name} byte for byte, irrespective of caller signer order`, async () => {
    const [version, payload, [writeNonce, notAfter, , delegation]] = decodeWire('frame', bytes(name));
    assert.equal(version, 1);
    const request = { chainId, generationId, topic: vector(name).topic, payload,
      nonce: hex(writeNonce), notAfter, delegation };
    assert.equal(hex(writeStatement(request)), vector(name).statementHex);
    assert.equal(hex(blake2b256(writeStatement(request))), vector(name).statementHash);
    const signers = name === 'frame-delegated' ? [await signer(4)] : [await signer(5), await signer(3)];
    const actual = await signWrite(request, signers);
    assert.equal(hex(actual), vector(name).hex);
    assert(await verifyFrameSignatures(request, actual));
    assert(!await verifyFrameSignatures({ ...request, topic: `${request.topic}2` }, actual));
    assert(!await verifyFrameSignatures({ ...request, chainId: 'another-chain' }, actual));
    assert(!await verifyFrameSignatures({ ...request, generationId: 'ff'.repeat(32) }, actual));
  });
}

test('all six registry operations reproduce their exact statement and signatures', async () => {
  const available = await Promise.all([1, 2, 3, 4, 5].map(signer));
  for (const value of fixture.vectors.filter(v => v.type === 'registry-command' && !v.invalid)) {
    const [, statement, signatures] = decodeWire('registry-command', unhex(value.hex));
    assert.equal(hex(encodeWire('command-statement', statement)), value.statementHex);
    const signers = signatures.map(([key]) => available.find(signer => signer.publicKey === hex(key))).reverse();
    assert.equal(hex(await signRegistry(statement, signers)), value.hex, value.name);
  }
});

test('WebCrypto verification matches core/libsodium on the adversarial Ed25519 corpus', async () => {
  for (const value of edge.vectors) {
    assert.equal(await verifySignature(unhex(value.key), unhex(value.signature), unhex(value.message)),
      value.valid, value.signature);
  }
});

test('signing rejects forged signatures, duplicate keys, wrong session keys, scope and expired windows', async () => {
  const [, payload, [writeNonce, notAfter, , delegation]] = decodeWire('frame', bytes('frame-delegated'));
  const request = { chainId, generationId, topic: 'acme/orders/new', payload, nonce: hex(writeNonce),
    notAfter, delegation };
  const session = await signer(4);
  await assert.rejects(signWrite(request, [await signer(5)]), /session key/);
  await assert.rejects(signWrite(request, [session, session]), /duplicated/);
  await assert.rejects(signWrite(request, [{ publicKey: session.publicKey, sign: () => new Uint8Array(64) }]),
    /invalid/);
  await assert.rejects(signWrite({ ...request, topic: 'acme/orders-other' }, [session]), /does not cover/);
  await assert.rejects(signWrite({ ...request, notAfter: delegation[4] + 1 }, [session]), /does not cover/);
  await assert.rejects(signWrite({ ...request, notAfter: delegation[3] - 1 }, [session]), /does not cover/);
});

test('CBOR rejects oversized, deep and unsafe numeric inputs before allocation', () => {
  assert.throws(() => encodeCbor(Number.MAX_SAFE_INTEGER + 1), /integer/);
  assert.throws(() => encodeCbor((1n << 63n)), /integer/);
  assert.equal(decodeCbor(encodeCbor((1n << 63n) - 1n)), (1n << 63n) - 1n);
  assert.throws(() => encodeCbor('\ud800'), /Unicode/);
  assert.throws(() => encodeCbor(new Uint8Array(1048576)), /1 MiB/);
  assert.throws(() => decodeCbor(unhex('9b7fffffffffffffff')), /length/);
  let nested = 0;
  for (let i = 0; i < 18; i++) nested = [nested];
  assert.throws(() => encodeCbor(nested), /structural/);
  assert.equal(decodeCbor(encodeCbor('\ufeffabc')), '\ufeffabc');
});

test('names and nonces use exact namespace boundaries and secure randomness', () => {
  assert(isValidEntryName('games/neon-rush/'));
  assert(!isValidEntryName('../'));
  assert(!isValidEntryName('games/../'));
  assert(!isValidEntryName('\ud800'));
  assert(!isValidEntryName('~governance'));
  const a = nonce(); const b = nonce();
  assert.match(a, /^[0-9a-f]{32}$/); assert.notEqual(a, b);
});

test('session storage, concurrent requests, refresh and generation binding keep the private key local', async () => {
  let now = 1800000000000;
  const issuer = await signer(3);
  const store = new Map();
  const calls = [];
  const options = { chainId, generationId, scope: 'acme/orders/', issuers: [issuer.publicKey], now: () => now,
    maxDelegationMs: 3600000, refreshBeforeMs: 60000, store,
    requestDelegation: async request => {
      calls.push(request);
      assert.deepEqual(Object.keys(request).sort(), ['chainId', 'delegate', 'generationId', 'scope']);
      return issueDelegation({ ...request, notBefore: now, notAfter: now + 3600000, issuer });
    } };
  const session = new DelegatedSession(options);
  const [a, b] = await Promise.all([session.ensureDelegation(), session.ensureDelegation()]);
  assert.equal(calls.length, 1); assert.deepEqual(a, b);
  const saved = store.get(session.storageKey);
  assert.equal(saved.key.privateKey.extractable, false);
  await assert.rejects(crypto.subtle.exportKey('pkcs8', saved.key.privateKey));
  const resumed = new DelegatedSession(options);
  await resumed.ensureDelegation(); assert.equal(calls.length, 1);
  const frame = await resumed.frame('acme/orders/new', Uint8Array.of(1, 2));
  assert(await verifyFrameSignatures({ chainId, generationId, topic: 'acme/orders/new' }, frame));
  now += 3540001;
  await resumed.ensureDelegation(); assert.equal(calls.length, 2);
  assert.equal(calls[0].delegate, calls[1].delegate);
  const reset = new DelegatedSession({ ...options, generationId: 'ff'.repeat(32) });
  await reset.ensureDelegation(); assert.equal(calls.length, 3);
  assert.notEqual(calls[1].delegate, calls[2].delegate);
  await resumed.clear(); assert(!store.has(resumed.storageKey));
});

test('a session rejects credentials for a different issuer, generation, key, scope or expiry', async () => {
  const issuer = await signer(3);
  const other = await signer(5);
  const now = 1800000000000;
  for (const change of [
    { issuer: other }, { generationId: 'ff'.repeat(32) }, { scope: 'other/' },
    { delegate: other.publicKey }, { notBefore: now + 1 }, { notAfter: now },
    { notAfter: now + 3600001 },
  ]) {
    const session = new DelegatedSession({ chainId, generationId, scope: 'acme/', issuers: [issuer.publicKey],
      now: () => now, requestDelegation: request => issueDelegation({ ...request, notBefore: now,
        notAfter: now + 3600000, issuer, ...change }) });
    await assert.rejects(session.ensureDelegation(), /invalid credential/);
  }
});

test('signing snapshots caller buffers before asynchronous external signing', async () => {
  const direct = decodeWire('frame', bytes('frame-direct-2-of-2'));
  const request = { chainId, generationId, topic: 'acme/orders/new', payload: direct[1],
    nonce: hex(direct[2][0]), notAfter: direct[2][1] };
  const s = await signer(5);
  const promise = signWrite(request, [{ publicKey: s.publicKey,
    sign: async bytes => { request.payload.fill(0); request.topic = 'changed'; return s.sign(bytes); } }]);
  assert(await verifyFrameSignatures({ chainId, generationId, topic: 'acme/orders/new' }, await promise));
});

test('transport uses the core route and exposes bounded refusal codes without leaking response details', async () => {
  const fetch = async (url, options) => {
    assert.equal(url.href, `http://localhost:7070/api/v1/app-chain/chains/${chainId}/messages`);
    assert.equal(options.redirect, 'error');
    assert.equal(JSON.parse(options.body).bodyHex, vector('frame-delegated').hex);
    return new Response(JSON.stringify({ code: 'TOPIC_PROOF_REPLAYED', detail: 'secret-canary' }), { status: 400 });
  };
  await assert.rejects(submitFrame({ baseUrl: 'http://localhost:7070/api/v1', chainId,
    topic: 'acme/orders/new', frame: bytes('frame-delegated') }, { fetch }), error => {
    assert(error instanceof TopicError); assert.equal(error.status, 400);
    assert.equal(error.code, 'TOPIC_PROOF_REPLAYED'); assert(!error.message.includes('secret-canary')); return true;
  });
  assert.equal(refusalCode({ code: '<script>' }), null);
  await assert.rejects(topicRequest('https://example.test', {}, {
    fetch: async () => new Response(' '.repeat(65537)),
  }), /64 KiB/);
});

test('BLAKE2b-256 has the RFC-derived empty hash and hashes the maximum body in linear time', () => {
  assert.equal(hex(blake2b256(new Uint8Array())),
    '0e5751c026e543b2e8ab2eb06099daa1d1e5df47778f7787faab45cdf12fe3a8');
  // Generous bound catches accidental quadratic copying without depending on workstation speed.
  const start = performance.now();
  assert.equal(blake2b256(new Uint8Array(1048576)).length, 32);
  assert(performance.now() - start < 5000);
});

test('submissions bind the returned chain, topic and canonical message id', async () => {
  const request = { baseUrl: 'https://example.test/api/v1', chainId, topic: 'acme/orders/new',
    frame: bytes('frame-delegated') };
  const valid = { chainId, topic: request.topic, messageId: 'ab'.repeat(32) };
  const response = value => ({ fetch: async () => new Response(JSON.stringify(value), { status: 202 }) });
  assert.deepEqual(await submitFrame(request, response(valid)), valid);
  for (const invalid of [null, [], { ...valid, chainId: 'different' }, { ...valid, topic: 'different' },
    { ...valid, messageId: 'AB'.repeat(32) }, { ...valid, messageId: 'ab' }, { ...valid, extra: true }]) {
    await assert.rejects(submitFrame(request, response(invalid)), /does not match/);
  }
});

test('requests have a deadline and preserve caller cancellation', async () => {
  const fetch = async (url, { signal }) => {
    signal.throwIfAborted();
    return new Promise((resolve, reject) => signal.addEventListener('abort', () => reject(signal.reason)));
  };
  await assert.rejects(topicRequest('https://example.test', {}, { fetch, timeoutMs: 1 }),
    error => error.name === 'TimeoutError');
  const controller = new AbortController(); controller.abort(new Error('caller cancellation'));
  await assert.rejects(topicRequest('https://example.test', {}, { fetch, signal: controller.signal }),
    /caller cancellation/);
  await assert.rejects(topicRequest('https://example.test', {}, { timeoutMs: 0 }), /timeout/);
});

test('session frame snapshots payload while a delegation request is in flight', async () => {
  const issuer = await signer(3);
  const now = 1800000000000;
  const payload = Uint8Array.of(1, 2, 3);
  const session = new DelegatedSession({ chainId, generationId, scope: 'acme/', issuers: [issuer.publicKey],
    now: () => now, requestDelegation: request => {
      payload.fill(9);
      return issueDelegation({ ...request, notBefore: now, notAfter: now + 3600000, issuer });
    } });
  const frame = await session.frame('acme/orders/new', payload);
  assert.deepEqual(decodeWire('frame', frame)[1], Uint8Array.of(1, 2, 3));
});

test('external signer output buffers are frozen before asynchronous verification', async () => {
  const delegated = decodeWire('frame', bytes('frame-delegated'));
  const output = new Uint8Array(64);
  const originalImport = crypto.subtle.importKey.bind(crypto.subtle);
  crypto.subtle.importKey = async (...args) => {
    const key = await originalImport(...args);
    output.fill(0); // mutation after signing returned, while verification is awaiting its imported key
    return key;
  };
  try {
    const signerA = await signer(3);
    const external = { publicKey: signerA.publicKey, sign: async message => {
      output.set(await signerA.sign(message)); return output;
    } };
    const expected = decodeWire('delegation', bytes('delegation'));
    const credential = await issueDelegation({ chainId, generationId, delegate: hex(expected[1]),
      scope: expected[2], notBefore: expected[3], notAfter: expected[4], issuer: external });
    assert.equal(hex(credential), vector('delegation').hex);
    const direct = await signWrite({ chainId, generationId, topic: 'acme/orders/new', payload: delegated[1],
      nonce: hex(delegated[2][0]), notAfter: delegated[2][1] }, [external]);
    assert(await verifyFrameSignatures({ chainId, generationId, topic: 'acme/orders/new' }, direct));
  } finally { crypto.subtle.importKey = originalImport; }
});
