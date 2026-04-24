# EU Regulatory Corpus

Raw financial regulatory documents from the EU and partner organizations. PDFs are stored locally in `docs/` with an index at `index.csv`.

## Setup

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/playwright install chromium
```

## Download

```bash
.venv/bin/python download.py                          # all sources
.venv/bin/python download.py --source {eurlex|eba|ecb|esma|fatf}   # one source
```

Idempotent: skips files whose SHA-256 matches the index.

## Output

- Primary legislation: `docs/primary/` (EUR-Lex)
- Guidance: `docs/guidance/{eba,ecb,esma,fatf}/`
- Index: `index.csv` (one row per document)
- Logs: `logs/<source>.log`

Current corpus: 1,011 documents / ~710M.

**Out of scope:** parsing, search, database. Use downstream systems for those.
