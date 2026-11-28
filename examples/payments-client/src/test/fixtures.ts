/** Shared test fixtures: a chain info in the shape of payments/info, on the Java vectors' intent domain. */
import type { Info, VaultView } from '../lib/api';
import type { IntentDomain } from '../lib/intents';

export const DOMAIN: IntentDomain = {
  networkMagic: 42, addressNetworkId: 0, chainId: 'payments-devnet-1',
  genesisId: '6c'.repeat(32), applicationId: 'asset-payments'
};

/** The vectors' first account: an enterprise payment-key address. */
export const ACCOUNT = {
  addressHex: '600d6a577e9441ad8ed9663931906e4d43ece8f82c712b1d0235affb06',
  keyHash: '0d6a577e9441ad8ed9663931906e4d43ece8f82c712b1d0235affb06',
  accountId: '6606b7b53e71d498faa0f1fcff2116f3f10d45b232c7b5d3d821548f'
};

const proof = { keyHex: 'aa', valueHex: 'bb', height: 7, proofPath: '' };

export const ADA: VaultView = {
  vaultId: '01'.repeat(32), asset: { policyId: '', assetName: '', unit: 'lovelace', ada: true }, ticker: 'ADA',
  decimals: 6, status: 'ACTIVE', address: 'addr_test1wr3w9chzut3w9chzut3w9chzut3w9chzut3w9chzut3w9csx9ld7v',
  depositsEnabled: true, transfersEnabled: true, proof
};

export const USDM: VaultView = {
  vaultId: '02'.repeat(32),
  asset: { policyId: 'f0'.repeat(28), assetName: '5553444d', unit: 'f0'.repeat(28) + '5553444d', ada: false },
  ticker: 'USDM', decimals: 6, status: 'ACTIVE', address: 'addr_test1wr3w9chzut3w9chzut3w9chzut3w9chzut3w9chzut3w9csx9ld7v',
  depositsEnabled: true, transfersEnabled: true, proof
};

export const INFO: Info = {
  chainId: DOMAIN.chainId, committed: { height: 7, stateRoot: '3c'.repeat(32) }, intentTopic: 'asset-payments.intents',
  intentDomain: DOMAIN, maxIntentTtlMs: 600_000, withdrawalsEnabled: true, minAdaPayout: '1000000',
  payoutLovelaceCap: '5000000', vaults: [ADA, USDM]
};
