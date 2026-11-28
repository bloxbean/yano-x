import type { Info } from '../lib/api';
import { assetIdText, factOf, short, vaultLabel, vaultStatus } from '../lib/display';
import { Badge, Facts, Mono } from '../ui';
import { ProofCheck } from './common';

/** The chain this client is bound to: its intent domain and the vault registry, each vault with its proof. */
export function LedgerPanel({ info }: { info: Info }) {
  const domain = info.intentDomain;
  return (
    <div className="space-y-4" data-testid="chain-info">
      <Facts rows={[
        ['Chain id', info.chainId],
        ['Application', domain.applicationId],
        ['Genesis id', domain.genesisId],
        ['Network', `magic ${domain.networkMagic}, address network id ${domain.addressNetworkId}`],
        ['Committed height', String(info.committed.height)],
        ['State root', info.committed.stateRoot],
        ['Intent topic', info.intentTopic],
        ['Withdrawals', info.withdrawalsEnabled ? 'enabled' : 'disabled (no payout observer)'],
      ]} />
      <div className="overflow-x-auto rounded-xl border border-line">
        <table className="w-full min-w-[520px] text-sm">
          <caption className="sr-only">Vault registry</caption>
          <thead className="bg-surface-2 text-left text-[11px] uppercase tracking-wider text-faint">
            <tr><th className="px-3 py-2">Asset</th><th className="px-3 py-2">Status</th><th className="px-3 py-2">Vault</th>
              <th className="px-3 py-2">Registry proof</th></tr>
          </thead>
          <tbody className="divide-y divide-line/70">
            {info.vaults.map((vault) => {
              const status = vaultStatus(vault.status);
              return (
                <tr key={vault.vaultId} className="align-top">
                  <td className="px-3 py-2">
                    <div className="font-medium">{vaultLabel(vault)} <span className="text-xs font-normal text-faint">{vault.decimals} dp</span></div>
                    <Mono className="block max-w-[220px] break-all text-[10px]">{assetIdText(vault)}</Mono>
                  </td>
                  <td className="px-3 py-2"><Badge tone={status.tone}>{status.label}</Badge></td>
                  <td className="px-3 py-2">
                    <Mono className="block text-[11px]" title={vault.vaultId}>{short(vault.vaultId, 8)}</Mono>
                    <Mono className="block max-w-[220px] break-all text-[10px]">{vault.address}</Mono>
                  </td>
                  <td className="px-3 py-2"><ProofCheck fact={factOf(vault.proof, info.committed)} label="Verify" /></td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
}
