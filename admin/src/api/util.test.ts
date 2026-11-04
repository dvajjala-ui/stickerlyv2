import { describe, expect, it } from 'vitest'
import { ApiError, chunk, fail, friendlyMessage, matchesTerms, searchTerms, slugify } from './util'

describe('searchTerms', () => {
  it('splits on whitespace and drops LIKE / PostgREST wildcards so input cannot widen a filter', () => {
    expect(searchTerms('  chai   pe charcha ')).toEqual(['chai', 'pe', 'charcha'])
    expect(searchTerms('%')).toEqual([])
    expect(searchTerms('100%_off*')).toEqual(['100', 'off'])
    expect(searchTerms('a\\b')).toEqual(['a', 'b'])
    expect(searchTerms(undefined)).toEqual([])
  })

  it('caps the number and length of terms', () => {
    expect(searchTerms('a b c d e f g h')).toHaveLength(6)
    expect(searchTerms('x'.repeat(100))[0]).toHaveLength(40)
  })

  it('matchesTerms is a case-insensitive AND of contains', () => {
    expect(matchesTerms('Kitten in a KULHAD chai cup', ['kulhad', 'Chai'])).toBe(true)
    expect(matchesTerms('Kitten in a cup', ['kulhad', 'chai'])).toBe(false)
  })
})

describe('error messages', () => {
  it('maps Postgres codes to plain language', () => {
    expect(friendlyMessage({ message: 'x', code: '42501' }, 'Save')).toMatch(/public\.admins/)
    expect(friendlyMessage({ message: 'dup', code: '23505', details: 'Key (title)=(a) exists' }, 'Add')).toMatch(/already exists/)
    expect(friendlyMessage({ message: 'TypeError: Failed to fetch' }, 'Load')).toMatch(/network error/)
  })

  it('fail() throws an ApiError that keeps the code', () => {
    expect(() => fail({ message: 'bad', code: '23514' }, 'Save')).toThrow(ApiError)
    try {
      fail({ message: 'bad', code: '23514' }, 'Save')
    } catch (e) {
      expect((e as ApiError).code).toBe('23514')
    }
  })
})

describe('misc', () => {
  it('slugify makes URL-safe slugs', () => {
    expect(slugify('Chai & Chill: Vol. 2')).toBe('chai-chill-vol-2')
    expect(slugify('Diwali Dhamaka 2026 ✨')).toBe('diwali-dhamaka-2026')
    expect(slugify('***')).toBe('')
  })

  it('chunk splits lists for batched requests', () => {
    expect(chunk([1, 2, 3, 4, 5], 2)).toEqual([[1, 2], [3, 4], [5]])
    expect(chunk([], 3)).toEqual([])
  })
})
