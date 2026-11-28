/**
 * Explorer fixtures captured from a DevKit devnet chain (payments-devkit-1, e2e/devkit-node.sh): an L1 deposit
 * observation, an owner-signed withdrawal, and the receipt and deposit record views the domain API returned for them.
 */
import type { ChainMessage } from '../lib/chain';

export const LIVE_DOMAIN = {
  networkMagic: 42, addressNetworkId: 0, chainId: 'payments-devkit-1',
  genesisId: 'c2b9c92a865dfa7c218a1a6e49f1dd88163372e40466009876458c01609d0d70', applicationId: 'asset-payments'
};

export const LIVE_ACCOUNT = 'b9e031e9e7d3b9f59ae65f06806088f9a71dd66626c116b8ead072e6';
export const LIVE_ADA_VAULT = '05599f61be9ad060edb28e3064d144a319a1b28f03191d58ef3a8620e0a50166';
export const MEMBER = '8a88e3dd7409f195fd52db2d3cba5d72ca6709bf1d94121bf3748801b40f6f5c';

function message(height: number, index: number, messageId: string, topic: string, bodyHex: string): ChainMessage {
  return { messageId, topic, sender: MEMBER, senderSeq: '0', bodyHex, size: bodyHex.length / 2, height, index };
}

/** A 25 ADA deposit to LIVE_ACCOUNT, observed before the account registered (UNCREDITED UNKNOWN_BENEFICIARY). */
export const DEPOSIT_MESSAGE = message(1, 0, '7d16d66bf3aaa04ca5c31a51cb128970fcf04f34fa4085e1e53d8c4eee41956a', '~l1/deposits',
  '8701686465706f73697473820058203f9db60cb6b5b4d9c0f9d0f45956844d1b3ba30e5718ecee84056ec92e200d050019d08c58205d16f92f3d7d33eb2be6b738e8061ef598132fdd1dd5431e36e5c1b866cd5c7758dc8d0158200629e8076c56c4afc61622d512cb42f53c3c6b92d54a5e6be4b8e29a2c8294a6582005599f61be9ad060edb28e3064d144a319a1b28f03191d58ef3a8620e0a5016658203f9db60cb6b5b4d9c0f9d0f45956844d1b3ba30e5718ecee84056ec92e200d050019d08c58205d16f92f3d7d33eb2be6b738e8061ef598132fdd1dd5431e36e5c1b866cd5c77008200581cb9e031e9e7d3b9f59ae65f06806088f9a71dd66626c116b8ead072e61a017d78401a017d784000582017e60b30e2dca0970917bcff0591b70e58d5a5739e8b2f42677dd5687d877136');

/** LIVE_ACCOUNT requests a 1000 ADA withdrawal, signed by its owner with CIP-30 signData. */
export const WITHDRAW_MESSAGE = message(10, 0, 'dc38b6caa4d9ce005346360264e051809229b7c8a83d3eff358f85a708e32163', 'asset-payments.intents',
  '8401005901dc845882a3012704583900c8c47610a36034aac6fc58848bdae5c278d994ff502c05455e3b3ee8f8ed3a0eea0ef835ffa7bbfcde55f7fe9d2cc5d55ea62cecb42bab3c6761646472657373583900c8c47610a36034aac6fc58848bdae5c278d994ff502c05455e3b3ee8f8ed3a0eea0ef835ffa7bbfcde55f7fe9d2cc5d55ea62cecb42bab3ca166686173686564f45901098c6e79616e6f2d696e74656e742d7631182a00717061796d656e74732d6465766b69742d315820c2b9c92a865dfa7c218a1a6e49f1dd88163372e40466009876458c01609d0d706e61737365742d7061796d656e747372524551554553545f5749544844524157414c581cb9e031e9e7d3b9f59ae65f06806088f9a71dd66626c116b8ead072e6810050eeeb838fab4fa6ebc4991747878623901b000001a222b33d54586484582005599f61be9ad060edb28e3064d144a319a1b28f03191d58ef3a8620e0a50166583900c8c47610a36034aac6fc58848bdae5c278d994ff502c05455e3b3ee8f8ed3a0eea0ef835ffa7bbfcde55f7fe9d2cc5d55ea62cecb42bab3c1a3b9aca00005840da6e833dc9d677aaff73e16028fb6ae8263dda8ef428e8ea2846bf4eeb917dcfeeb3bcf219e5ec81955f3dff18a928502a7ca07562d7b092fcc9fba8e76e5a0b5866a5010102583900c8c47610a36034aac6fc58848bdae5c278d994ff502c05455e3b3ee8f8ed3a0eea0ef835ffa7bbfcde55f7fe9d2cc5d55ea62cecb42bab3c032720062158206ea31d27d585439ea8fd9cd8e6664ed83e605c06aec24d32dfaba488e49287d9');

export const WITHDRAW_INTENT_ID = '0604fd66e38e226d7598581343339f989c0ed6b57ad69e80eb1e09cb076d2910';
export const DEPOSIT_ID = '0629e8076c56c4afc61622d512cb42f53c3c6b92d54a5e6be4b8e29a2c8294a6';

export const WITHDRAW_RECEIPT = {"chainId": "payments-devkit-1", "intentId": "0604fd66e38e226d7598581343339f989c0ed6b57ad69e80eb1e09cb076d2910", "committed": {"stateRoot": "2804921c0d5afb6ca74e3ab93ac12eb92af90a30f9d8116818c492a095b68e55", "height": 10}, "found": true, "proof": {"keyHex": "61702f726563656970742f0604fd66e38e226d7598581343339f989c0ed6b57ad69e80eb1e09cb076d2910", "valueHex": "890158200604fd66e38e226d7598581343339f989c0ed6b57ad69e80eb1e09cb076d2910581cb9e031e9e7d3b9f59ae65f06806088f9a71dd66626c116b8ead072e672524551554553545f5749544844524157414c8240401a3b9aca0000600a", "height": 10, "proofPath": "app-chain/chains/payments-devkit-1/state/proof/61702f726563656970742f0604fd66e38e226d7598581343339f989c0ed6b57ad69e80eb1e09cb076d2910?height=10"}, "receipt": {"accountId": "b9e031e9e7d3b9f59ae65f06806088f9a71dd66626c116b8ead072e6", "action": "REQUEST_WITHDRAWAL", "asset": {"policyId": "", "assetName": "", "unit": "lovelace", "ada": true}, "amount": "1000000000", "outcome": "ACCEPTED", "reason": "", "height": 10}};

export const DEPOSIT_RECORD = {"chainId": "payments-devkit-1", "depositId": "0629e8076c56c4afc61622d512cb42f53c3c6b92d54a5e6be4b8e29a2c8294a6", "committed": {"stateRoot": "2804921c0d5afb6ca74e3ab93ac12eb92af90a30f9d8116818c492a095b68e55", "height": 10}, "found": true, "proof": {"keyHex": "61702f6465706f7369742f0629e8076c56c4afc61622d512cb42f53c3c6b92d54a5e6be4b8e29a2c8294a6", "valueHex": "910158200629e8076c56c4afc61622d512cb42f53c3c6b92d54a5e6be4b8e29a2c8294a6582005599f61be9ad060edb28e3064d144a319a1b28f03191d58ef3a8620e0a5016658203f9db60cb6b5b4d9c0f9d0f45956844d1b3ba30e5718ecee84056ec92e200d050019d08c01000109581cb9e031e9e7d3b9f59ae65f06806088f9a71dd66626c116b8ead072e61a017d78401a017d784000582017e60b30e2dca0970917bcff0591b70e58d5a5739e8b2f42677dd5687d8771360000", "height": 10, "proofPath": "app-chain/chains/payments-devkit-1/state/proof/61702f6465706f7369742f0629e8076c56c4afc61622d512cb42f53c3c6b92d54a5e6be4b8e29a2c8294a6?height=10"}, "deposit": {"status": "UNCREDITED", "reason": "UNKNOWN_BENEFICIARY", "shape": "DEPOSIT", "beneficiary": "b9e031e9e7d3b9f59ae65f06806088f9a71dd66626c116b8ead072e6", "slot": 53388, "height": 1, "registeredQuantity": "25000000", "lovelace": "25000000", "creditedAmount": "0", "creditedLovelace": "0"}};
