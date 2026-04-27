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
  // Compliant — muted gold
  GBR: '#cfb275', NLD: '#cfb275', FRA: '#cfb275', DEU: '#cfb275',
  IRL: '#cfb275', ESP: '#cfb275', ITA: '#cfb275', BEL: '#cfb275',
  AUT: '#cfb275', PRT: '#cfb275', POL: '#cfb275', SWE: '#cfb275',
  NOR: '#cfb275', FIN: '#cfb275', DNK: '#cfb275', CHE: '#cfb275',
  ISL: '#cfb275', JPN: '#cfb275', AUS: '#cfb275', NZL: '#cfb275',
  CAN: '#cfb275', SGP: '#cfb275', KOR: '#cfb275',
  // Needs changes — muted amber (C palette fallback; SVG can't render stripes)
  USA: '#b87538', MEX: '#b87538', BRA: '#b87538', IND: '#b87538',
  CHN: '#b87538', ARE: '#b87538', SAU: '#b87538', TUR: '#b87538',
  ZAF: '#b87538', ARG: '#b87538', EGY: '#b87538', HKG: '#b87538',
  GRC: '#b87538', CZE: '#b87538', HUN: '#b87538', ROU: '#b87538',
  BGR: '#b87538',
  // Not compliant — muted red
  RUS: '#a83820', BLR: '#a83820', IRN: '#a83820', PRK: '#a83820',
  SYR: '#a83820', UKR: '#a83820', NGA: '#a83820',
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
