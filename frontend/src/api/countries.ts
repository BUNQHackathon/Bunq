// Shared country code utilities used by jurisdiction map views.

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
