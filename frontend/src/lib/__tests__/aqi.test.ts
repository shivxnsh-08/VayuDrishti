import { describe, expect, it } from 'vitest';
import { CATEGORIES, categorize, formatAge, pollutantLabel } from '../aqi';

describe('categorize (CPCB bands)', () => {
  it('maps band edges to the right category', () => {
    expect(categorize(0)).toBe('GOOD');
    expect(categorize(50)).toBe('GOOD');
    expect(categorize(51)).toBe('SATISFACTORY');
    expect(categorize(100)).toBe('SATISFACTORY');
    expect(categorize(101)).toBe('MODERATE');
    expect(categorize(200)).toBe('MODERATE');
    expect(categorize(201)).toBe('POOR');
    expect(categorize(301)).toBe('VERY_POOR');
    expect(categorize(401)).toBe('SEVERE');
    expect(categorize(999)).toBe('SEVERE');
  });

  it('rounds like the backend (50.4 is GOOD, 50.5 is SATISFACTORY)', () => {
    expect(categorize(50.4)).toBe('GOOD');
    expect(categorize(50.5)).toBe('SATISFACTORY');
  });

  it('has contiguous, non-overlapping bands', () => {
    for (let i = 1; i < CATEGORIES.length; i++) expect(CATEGORIES[i].min).toBe(CATEGORIES[i - 1].max + 1);
  });
});

describe('formatting', () => {
  it('describes age honestly, including never', () => {
    expect(formatAge(null)).toBe('never');
    expect(formatAge(new Date(Date.now() - 5 * 60_000).toISOString())).toBe('5 min ago');
    expect(formatAge(new Date(Date.now() - 125 * 60_000).toISOString())).toBe('2 h 5 min ago');
  });

  it('labels pollutants and handles missing values', () => {
    expect(pollutantLabel(null)).toBeTruthy();
    expect(pollutantLabel('PM25')).toMatch(/PM2\.5|PM₂.₅/);
  });
});
