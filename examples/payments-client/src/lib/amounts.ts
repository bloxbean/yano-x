/** Decimal display units <-> integer base units. Decimals are display metadata only (#109). */
export function toBaseUnits(text: string, decimals: number): bigint {
  const value = text.trim();
  if (!/^\d+(\.\d+)?$/.test(value)) throw new Error(`"${text}" is not a non-negative decimal amount`);
  const [whole, fraction = ''] = value.split('.');
  if (fraction.length > decimals) throw new Error(`at most ${decimals} decimal places`);
  return BigInt(whole) * 10n ** BigInt(decimals) + BigInt(fraction.padEnd(decimals, '0') || '0');
}

export function formatUnits(base: bigint | string, decimals: number): string {
  const value = BigInt(base);
  if (decimals === 0) return value.toString();
  const scale = 10n ** BigInt(decimals);
  const fraction = (value % scale).toString().padStart(decimals, '0').replace(/0+$/, '');
  return fraction ? `${value / scale}.${fraction}` : (value / scale).toString();
}
