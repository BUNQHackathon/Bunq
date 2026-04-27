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
  // Compliant — C2 warm gold
  GBR: '#e8c97a', NLD: '#e8c97a', FRA: '#e8c97a', DEU: '#e8c97a',
  IRL: '#e8c97a', ESP: '#e8c97a', ITA: '#e8c97a', BEL: '#e8c97a',
  AUT: '#e8c97a', PRT: '#e8c97a', POL: '#e8c97a', SWE: '#e8c97a',
  NOR: '#e8c97a', FIN: '#e8c97a', DNK: '#e8c97a', CHE: '#e8c97a',
  ISL: '#e8c97a', JPN: '#e8c97a', AUS: '#e8c97a', NZL: '#e8c97a',
  CAN: '#e8c97a', SGP: '#e8c97a', KOR: '#e8c97a',
  // Needs changes — warm orange (C palette fallback; SVG can't render stripes)
  USA: '#e89a4f', MEX: '#e89a4f', BRA: '#e89a4f', IND: '#e89a4f',
  CHN: '#e89a4f', ARE: '#e89a4f', SAU: '#e89a4f', TUR: '#e89a4f',
  ZAF: '#e89a4f', ARG: '#e89a4f', EGY: '#e89a4f', HKG: '#e89a4f',
  GRC: '#e89a4f', CZE: '#e89a4f', HUN: '#e89a4f', ROU: '#e89a4f',
  BGR: '#e89a4f',
  // Not compliant — warm red-orange
  RUS: '#d94a2e', BLR: '#d94a2e', IRN: '#d94a2e', PRK: '#d94a2e',
  SYR: '#d94a2e', UKR: '#d94a2e', NGA: '#d94a2e',
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
// jurisdictions map. Currently empty — NL inherits the standard
// compliant color from MOCK_COUNTRY_COLOR.
export const BUNQ_GRADIENT_COLOR: Record<string, string> = {};
