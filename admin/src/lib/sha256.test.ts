import { describe, expect, it } from 'vitest'
import { isSha256Hex, sha256Hex, toHex } from './sha256'

const EMPTY = 'e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855'
const ABC = 'ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad'

describe('sha256Hex', () => {
  it('matches the FIPS 180-2 test vectors', async () => {
    expect(await sha256Hex('')).toBe(EMPTY)
    expect(await sha256Hex('abc')).toBe(ABC)
  })

  it('hashes Blob, ArrayBuffer and Uint8Array inputs identically', async () => {
    const bytes = new TextEncoder().encode('abc')
    expect(await sha256Hex(bytes)).toBe(ABC)
    expect(await sha256Hex(bytes.buffer.slice(0) as ArrayBuffer)).toBe(ABC)
    expect(await sha256Hex(new Blob([bytes]))).toBe(ABC)
  })

  it('produces lower-case hex accepted by the schema check', async () => {
    const h = await sha256Hex(new Uint8Array([0, 1, 2, 255]))
    expect(isSha256Hex(h)).toBe(true)
    expect(h).toMatch(/^[0-9a-f]{64}$/)
  })

  it('rejects malformed digests', () => {
    expect(isSha256Hex(ABC.toUpperCase())).toBe(false)
    expect(isSha256Hex(ABC.slice(1))).toBe(false)
    expect(isSha256Hex(`${ABC.slice(1)}g`)).toBe(false)
  })

  it('toHex pads single digits', () => {
    expect(toHex(new Uint8Array([0, 10, 255]))).toBe('000aff')
  })
})
