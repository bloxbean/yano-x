export interface RuntimeEndpoint {
  id: string;
  nodeUrl: string;
  apiPrefix: string;
  label?: string;
}

export interface RuntimeConfig {
  schemaVersion: 1;
  productId: 'eutxo';
  endpoints: RuntimeEndpoint[];
  defaultChainId: string;
  expectedNetwork: string;
  allowEndpointOverride: boolean;
}

export interface ActiveConnection {
  endpointId: string;
  nodeUrl: string;
  apiPrefix: string;
  apiBase: string;
  apiKey: string;
  label: string;
}

export interface NodeConfig {
  protocolMagic?: number;
  network?: string;
  version?: string;
}

export interface NodeStatus {
  running?: boolean;
  syncing?: boolean;
  initialSyncComplete?: boolean;
  blocksProcessed?: number;
  localTipSlot?: number;
  localTipBlockNumber?: number;
  remoteTipSlot?: number;
  remoteTipBlockNumber?: number;
  runtimeDegraded?: boolean;
  statusMessage?: string;
}

export interface ChainSummary {
  chainId: string;
  tipHeight: number;
  stateRoot: string;
  stateCommitment?: StateCommitmentStatus;
}

export interface StateCommitmentStatus {
  profile?: string;
  backend?: string;
  formatFingerprint?: string;
  genesisId?: string;
  stateRoot?: string;
  oldestProvableHeight?: number;
}

export interface AppCapabilityComponent {
  id: string;
  version: string;
  configurationId?: string;
  stateNamespace?: string;
}

export interface AppCapabilityManifest {
  schemaVersion: number;
  applicationId: string;
  applicationVersion: string;
  manifestDigest: string;
  components: AppCapabilityComponent[];
  workflows?: Array<{ id: string; version?: string }>;
  crossCutting?: Array<{ id: string; version?: string }>;
  proofSubjects?: Array<{ id?: string; subjectId?: string; label?: string }>;
}

export interface AppChainStatus {
  chainId?: string;
  running?: boolean;
  stalled?: boolean;
  submissionsPaused?: boolean;
  tipHeight?: number;
  stateRoot?: string;
  stateMachine?: string;
  members?: number;
  threshold?: number;
  peers?: Record<string, boolean>;
  anchor?: Record<string, unknown>;
  effects?: Record<string, unknown>;
  stateCommitment?: StateCommitmentStatus;
  capabilityManifest?: AppCapabilityManifest;
}

export interface DiscoveredChain {
  summary: ChainSummary;
  status: AppChainStatus;
  bridge: EutxoBridgeInfo | null;
  bridgeState: 'available' | 'disabled' | 'unavailable';
}

export interface EutxoBridgeInfo {
  chainId: string;
  vaultAddress: string;
  vaultScriptHash: string;
  withdrawalAddress: string;
  bridgeEpoch: number;
  maxDepositLovelace: number;
  withdrawalsPaused: boolean;
  stabilityDepth: number;
}

export interface DepositBuildResponse {
  chainId: string;
  unsignedTxCborHex: string;
  transactionId: string;
  vaultAddress: string;
  depositorAddress: string;
  l2OwnerAddress: string;
  lovelace: number;
  fee: number;
  ttlSlot: number;
  datumHex: string;
}

export interface DepositAssembleResponse {
  signedTxCborHex: string;
  transactionId: string;
}

export interface L2BuildResponse {
  chainId?: string;
  unsignedTxCborHex: string;
  transactionId: string;
  fromAddress?: string;
  toAddress?: string;
  payoutAddress?: string;
  lovelace?: number;
  submitTopic: string;
}

export interface MessageSubmitResult {
  messageId: string;
  chainId: string;
  topic: string;
}

export interface EutxoTransactionEntry {
  outpoint: string;
  address: string;
  lovelace: string;
}

export interface EutxoTransactionSummary {
  transactionId: string;
  messageId: string;
  sequence: number;
  appHeight: number;
  ordinal: number;
  l1Slot: number;
  status: 'ACCEPTED' | 'REJECTED';
  authorizationProfile: string;
  inputs: EutxoTransactionEntry[];
  outputs: EutxoTransactionEntry[];
  code: string;
}

export interface EutxoTransactionPage {
  chainId: string;
  stateMachineId: string;
  committedHeight: number;
  stateRoot: string;
  data: EutxoTransactionSummary[];
  nextBefore: number;
}

export interface EutxoTransactionDetail extends Omit<EutxoTransactionPage, 'data' | 'nextBefore'> {
  data: EutxoTransactionSummary;
}

export interface EutxoIndexProjection {
  kind: 'DERIVED';
  status: string;
  indexedHeight: number;
  finalizedHeight: number;
  lagBlocks: number;
  historyFromHeight: number;
  fullHistory: boolean;
}

export interface EutxoIndexEnvelope<T> {
  apiVersion: 'eutxo-index/v1';
  chainId: string;
  stateMachineId: 'eutxo-ledger';
  projection: EutxoIndexProjection;
  data: T;
}

export interface EutxoIndexPage<T> {
  items: T[];
  cursor: string;
  scanTruncated?: boolean;
}

export interface EutxoIndexStatus {
  storeType: string;
  checkpointHeight: number;
  finalizedHeight: number;
  lagBlocks: number;
  coverage: 'NONE' | 'PARTIAL' | 'FULL';
  normalizedDigest: string;
  validityAvailable?: boolean;
  diagnosticCode?: string;
}

export interface EutxoIndexedAccount {
  address: string;
  lovelace: string;
  utxos: EutxoTransactionEntry[];
  activityTransactionIds: string[];
}

export interface EutxoDeposit {
  acceptedOutpoint: string;
  stagingOutpoint: string;
  mirroredOutpoint: string;
  l2Address: string;
  l1Slot: number;
  l1BlockHash: string;
  creditedHeight: number;
}

export interface EutxoWithdrawal {
  claimId: string;
  status: string;
  withdrawalOutpoint: string;
  destinationAddress: string;
  lovelace: string;
  requestedHeight: number;
  settlementTransactionId: string;
  confirmedSlot: number;
  confirmedBlockHash: string;
  updatedHeight: number;
}

export interface EutxoLineage {
  nodes: Array<{ kind: string; id: string; status: string }>;
  edges: Array<{ from: string; to: string; relation: string }>;
  truncated: boolean;
}

export interface EutxoValidityBatch {
  batchId: string;
  provider: string;
  proofSystem: string;
  profileId: string;
  profileDigest: string;
  transactionIds: string[];
  previousRoot: string;
  nextRoot: string;
  dataCommitment: string;
  dataStatus: string;
  proofDigest: string;
  verificationKeyDigest: string;
  proofStatus: string;
  settlementStatus: string;
  settlementTransactionId: string;
  settlementSlot: number;
  settlementBlockHash: string;
}

export interface AnchorCommitment {
  chainId: string;
  mode: string;
  anchoredHeight: number;
  stateRoot: string;
  blockHash: string;
  transactionHash: string;
  l1Slot: number;
  provenance: string;
  trustWarning?: string;
}

export interface L1Transaction {
  hash?: string;
  block?: string;
  block_height?: number;
  block_time?: number;
  slot?: number;
  index?: number;
  fees?: string;
  invalid_before?: string | null;
  invalid_hereafter?: string | null;
}

export interface L1TransactionUtxo {
  tx_hash?: string;
  output_index?: number;
  address?: string;
  amount?: Array<{ unit?: string; quantity?: string }>;
  data_hash?: string | null;
  inline_datum?: string | null;
}

export interface L1TransactionUtxos {
  hash?: string;
  inputs?: L1TransactionUtxo[];
  outputs?: L1TransactionUtxo[];
}

export interface L1Detail {
  id: string;
  state: 'loading' | 'ready' | 'unavailable' | 'not-found' | 'failed';
  transaction?: L1Transaction;
  utxos?: L1TransactionUtxos;
  message?: string;
}

export interface OperationReceipt {
  kind: 'deposit' | 'transfer' | 'withdrawal';
  transactionId: string;
  messageId?: string;
  l2OwnerAddress?: string;
  submittedAt: number;
}
