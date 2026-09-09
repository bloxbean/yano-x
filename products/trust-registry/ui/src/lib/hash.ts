import { toHex } from './cbor';

/** SHA-256 over the document bytes, the certificate digest form. Bytes never leave the browser. */
export async function sha256Hex(bytes: Uint8Array): Promise<string> {
  const subtle = webCrypto();
  if (!subtle) throw new Error('WebCrypto SHA-256 is unavailable in this browser');
  return toHex(new Uint8Array(await subtle.digest('SHA-256', bytes as BufferSource)));
}

/**
 * Ed25519 verification through WebCrypto. Returns null when the runtime has no
 * Ed25519 support, so the caller can label the check as unavailable rather than failed.
 */
export async function ed25519Verify(
  publicKey: Uint8Array,
  signature: Uint8Array,
  message: Uint8Array
): Promise<boolean | null> {
  const subtle = webCrypto();
  if (!subtle) return null;
  try {
    const key = await subtle.importKey('raw', publicKey as BufferSource, { name: 'Ed25519' }, false, ['verify']);
    return await subtle.verify({ name: 'Ed25519' }, key, signature as BufferSource, message as BufferSource);
  } catch (cause) {
    if (cause instanceof Error && /not supported|unrecognized|NotSupported|Unrecognized/i.test(
      `${cause.name} ${cause.message}`)) {
      return null;
    }
    return false;
  }
}

function webCrypto(): SubtleCrypto | null {
  return globalThis.crypto?.subtle ?? null;
}
