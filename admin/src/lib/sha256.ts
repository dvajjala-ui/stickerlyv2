import { sha256Hex as sha256OfBytes, toHex } from '../../../backend/supabase/functions/intake/lib.ts'

export { toHex }

/** Lower-case hex SHA-256 via SubtleCrypto (browser, Node 22+, Deno). */
export async function sha256Hex(input: Blob | ArrayBuffer | Uint8Array | string): Promise<string> {
  let bytes: Uint8Array
  if (typeof input === 'string') bytes = new TextEncoder().encode(input)
  else if (input instanceof Uint8Array) bytes = input
  else if (input instanceof ArrayBuffer) bytes = new Uint8Array(input)
  else bytes = new Uint8Array(await input.arrayBuffer())
  return sha256OfBytes(bytes)
}

export function isSha256Hex(value: string): boolean {
  return /^[0-9a-f]{64}$/.test(value)
}
