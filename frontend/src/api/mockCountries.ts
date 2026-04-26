// ── Shared country data used by JurisdictionsPage and LaunchDetailPage ────────

export const ISO2_TO_ISO3: Record<string, string> = {
  NL: 'NLD', DE: 'DEU', FR: 'FRA', GB: 'GBR', UK: 'GBR',
  US: 'USA', IE: 'IRL', AT: 'AUT', ES: 'ESP', IT: 'ITA', BE: 'BEL',
  PL: 'POL', SE: 'SWE', NO: 'NOR', FI: 'FIN', DK: 'DNK', CH: 'CHE',
  PT: 'PRT', GR: 'GRC', CZ: 'CZE', HU: 'HUN', RO: 'ROU', BG: 'BGR',
  RU: 'RUS', BY: 'BLR', UA: 'UKR', TR: 'TUR', JP: 'JPN', CN: 'CHN',
  IN: 'IND', BR: 'BRA', AU: 'AUS', CA: 'CAN', MX: 'MEX', ZA: 'ZAF',
  AE: 'ARE', SA: 'SAU', IR: 'IRN', KP: 'PRK', SY: 'SYR', EG: 'EGY',
  NG: 'NGA', AR: 'ARG', KR: 'KOR', SG: 'SGP', HK: 'HKG', NZ: 'NZL',
  IS: 'ISL',
};

export const ISO3_TO_ISO2: Record<string, string> = Object.fromEntries(
  Object.entries(ISO2_TO_ISO3).map(([a, b]) => [b, a]),
);

export const MOCK_COUNTRY_COLOR: Record<string, string> = {
  // Green — compliant
  GBR: '#61B650', NLD: '#61B650', FRA: '#61B650', DEU: '#61B650',
  IRL: '#61B650', ESP: '#61B650', ITA: '#61B650', BEL: '#61B650',
  AUT: '#61B650', PRT: '#61B650', POL: '#61B650', SWE: '#61B650',
  NOR: '#61B650', FIN: '#61B650', DNK: '#61B650', CHE: '#61B650',
  ISL: '#61B650', JPN: '#61B650', AUS: '#61B650', NZL: '#61B650',
  CAN: '#61B650', SGP: '#61B650', KOR: '#61B650',
  // Amber — needs changes
  USA: '#F5C836', MEX: '#F5C836', BRA: '#F5C836', IND: '#F5C836',
  CHN: '#F5C836', ARE: '#F5C836', SAU: '#F5C836', TUR: '#F5C836',
  ZAF: '#F5C836', ARG: '#F5C836', EGY: '#F5C836', HKG: '#F5C836',
  GRC: '#F5C836', CZE: '#F5C836', HUN: '#F5C836', ROU: '#F5C836',
  BGR: '#F5C836',
  // Red — non-compliant / sanctioned
  RUS: '#E22F30', BLR: '#E22F30', IRN: '#E22F30', PRK: '#E22F30',
  SYR: '#E22F30', UKR: '#E22F30', NGA: '#E22F30',
};

export const MOCK_COUNTRY_LABEL: Record<string, string> = {
  GBR: 'United Kingdom', NLD: 'Netherlands', FRA: 'France', DEU: 'Germany',
  IRL: 'Ireland', ESP: 'Spain', ITA: 'Italy', BEL: 'Belgium', AUT: 'Austria',
  PRT: 'Portugal', POL: 'Poland', SWE: 'Sweden', NOR: 'Norway', FIN: 'Finland',
  DNK: 'Denmark', CHE: 'Switzerland', ISL: 'Iceland', JPN: 'Japan',
  AUS: 'Australia', NZL: 'New Zealand', CAN: 'Canada', SGP: 'Singapore',
  KOR: 'South Korea', USA: 'United States', MEX: 'Mexico', BRA: 'Brazil',
  IND: 'India', CHN: 'China', ARE: 'United Arab Emirates', SAU: 'Saudi Arabia',
  TUR: 'Turkey', ZAF: 'South Africa', ARG: 'Argentina', EGY: 'Egypt',
  HKG: 'Hong Kong', GRC: 'Greece', CZE: 'Czechia', HUN: 'Hungary',
  ROU: 'Romania', BGR: 'Bulgaria', RUS: 'Russia', BLR: 'Belarus',
  IRN: 'Iran', PRK: 'North Korea', SYR: 'Syria', UKR: 'Ukraine', NGA: 'Nigeria',
};

// BUNQ-operating countries that get a brand-color overlay on the
// jurisdictions map. Currently NL only — easter egg for the home market.
export const BUNQ_GRADIENT_COLOR: Record<string, string> = {
  NLD: '#ef6a2a',
};
