import { describe, expect, it, vi } from 'vitest';
import { runIntent, transfer, type FlowContext } from './flows';
import type { ClientConfig } from './lib/config';
import { fromHex, toHex } from './lib/hex';
import { bodyFromJson, credentialFromAddress, encodeEnvelope, intentId, sessionMessage, type Action } from './lib/intents';
import { generateSessionKey, type StoredSession } from './lib/session';
import type { ConnectedWallet } from './lib/wallet';
import { ACCOUNT, ADA, INFO } from './test/fixtures';

/** A node whose encoder agrees with this client, unless told otherwise. */
function fakeApi(overrides: { encode?: 'wrong'; prepare?: 'wrong' } = {}) {
  const calls = { submit: vi.fn(async () => ({ messageId: 'm' })) };
  const api = {
    chainId: INFO.chainId,
    encodeIntent: async (request: { action: Action; accountId: string; authorizer: never; nonce: string;
      notAfterMs: number; body: Record<string, unknown> }) => {
      const envelope = encodeEnvelope({ domain: INFO.intentDomain, ...request, body: bodyFromJson(request.action, request.body) });
      if (overrides.encode === 'wrong') envelope[envelope.length - 1] ^= 1;
      return { envelopeHex: toHex(envelope), intentId: intentId(envelope) };
    },
    prepareMessage: async (request: { envelopeHex: string; signatureHex: string }) => {
      const body = sessionMessage(fromHex(request.envelopeHex), fromHex(request.signatureHex));
      if (overrides.prepare === 'wrong') body[0] ^= 1;
      return { topic: INFO.intentTopic, bodyHex: toHex(body), intentId: intentId(fromHex(request.envelopeHex)) };
    },
    submit: calls.submit,
  };
  return { api, calls };
}

function context(api: unknown, review = vi.fn(async () => {})): FlowContext {
  const credential = credentialFromAddress(fromHex(ACCOUNT.addressHex));
  const wallet = { key: 'test', name: 'Test', api: { signData: vi.fn() }, quirks: [],
    signingAddress: fromHex(ACCOUNT.addressHex), changeAddress: fromHex(ACCOUNT.addressHex) } as unknown as ConnectedWallet;
  return { config: { maxDepositFeeLovelace: '2000000' } as ClientConfig, api: api as FlowContext['api'], info: INFO,
    wallet, credential, account: credential, view: undefined, review };
}

async function session(): Promise<StoredSession> {
  const key = await generateSessionKey();
  return { id: 's', chainId: INFO.chainId, accountId: ACCOUNT.accountId, grantId: '77'.repeat(32),
    publicKeyHex: toHex(key.publicKey), privateKey: key.privateKey, notAfterMs: Date.now() + 60_000, createdAtMs: 0 };
}

const body = { asset: { policyId: '', assetName: '' }, toAccount: 'ab'.repeat(28), amount: '1000000' };

describe('the intent pipeline', () => {
  it('refuses to show or sign an intent the node encodes differently', async () => {
    const { api, calls } = fakeApi({ encode: 'wrong' });
    const ctx = context(api);
    await expect(transfer(ctx, { vaultId: ADA.vaultId, to: 'ab'.repeat(28), amount: '1' }))
      .rejects.toThrow(/encodes this intent differently/);
    expect(ctx.review).not.toHaveBeenCalled();
    expect(ctx.wallet.api.signData).not.toHaveBeenCalled();
    expect(calls.submit).not.toHaveBeenCalled();
  });

  it('pays with a session key without a review or a wallet prompt', async () => {
    const { api, calls } = fakeApi();
    const ctx = context(api);
    const submitted = await runIntent(ctx, 'TRANSFER', body, [], await session());
    expect(ctx.review).not.toHaveBeenCalled();
    expect(ctx.wallet.api.signData).not.toHaveBeenCalled();
    expect(calls.submit).toHaveBeenCalledWith(INFO.intentTopic, expect.stringMatching(/^[0-9a-f]+$/));
    expect(submitted.intentId).toMatch(/^[0-9a-f]{64}$/);
  });

  it('refuses to submit a prepared message that is not the signed intent', async () => {
    const { api, calls } = fakeApi({ prepare: 'wrong' });
    await expect(runIntent(context(api), 'TRANSFER', body, [], await session())).rejects.toThrow(/differs/);
    expect(calls.submit).not.toHaveBeenCalled();
  });

  it('stops at the review when the user cancels, before the wallet signs', async () => {
    const { api, calls } = fakeApi();
    const ctx = context(api, vi.fn(async () => { throw new Error('cancelled'); }));
    await expect(transfer(ctx, { vaultId: ADA.vaultId, to: 'ab'.repeat(28), amount: '2.5' })).rejects.toThrow('cancelled');
    const request = (ctx.review as ReturnType<typeof vi.fn>).mock.calls[0][0];
    expect(request.fields).toContainEqual(['Amount (base units)', '2500000']);
    expect(request.fields).toContainEqual(['Genesis id', INFO.intentDomain.genesisId]);
    expect(ctx.wallet.api.signData).not.toHaveBeenCalled();
    expect(calls.submit).not.toHaveBeenCalled();
  });

  it('refuses to act for an account another credential owns', async () => {
    const { api } = fakeApi();
    const ctx = { ...context(api), view: { registered: true, owner: { credentialKind: 'PAYMENT_KEY', keyHash: '00'.repeat(28) } } } as FlowContext;
    await expect(runIntent(ctx, 'TRANSFER', body, [])).rejects.toThrow(/does not own/);
  });
});
