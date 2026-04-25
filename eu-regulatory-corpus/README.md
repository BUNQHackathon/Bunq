# EU Regulatory Corpus

Raw financial regulatory documents from the EU and partner organizations, plus per-jurisdiction national additions. PDFs are stored locally in `docs/` with an index at `index.csv`.

## Setup

```bash
python3 -m venv .venv
.venv/bin/pip install -r requirements.txt
.venv/bin/playwright install chromium
```

## Download

```bash
.venv/bin/python download.py                                            # all sources
.venv/bin/python download.py --source {eurlex|eba|ecb|esma|fatf}        # one EU source
.venv/bin/python download.py --source {irishstatutebook|cbi|ireland}    # Irish primary / CBI guidance / both
```

Idempotent: skips files whose SHA-256 matches the index.

## Output

- EU primary legislation: `docs/primary/` (EUR-Lex)
- EU supervisory guidance: `docs/guidance/{eba,ecb,esma,fatf}/`
- National additions: `docs/national/<jurisdiction>/{primary,guidance/<regulator>}/`
- Index: `index.csv` (one row per document)
- Logs: `logs/<source>.log`

Current corpus: 1,217 documents / ~845M.

## Ireland

Ireland-specific regulation lives under `docs/national/ireland/`. Source lists are in `sources.yaml` under `national.ireland`.

- **Primary legislation** (`docs/national/ireland/primary/`, 12 docs): Acts of the Oireachtas (Criminal Justice ML/TF Act 2010, Data Protection Act 2018, Central Bank Act 1942 incl. amendments, Central Bank (Supervision & Enforcement) Act 2013, IAF Act 2023, Investor Compensation Act 1998, Consumer Protection Act 2007) plus EU directive transpositions (PSD2, EMD2, MiFID II, CCD, MCD). Acts use `revisedacts.lawreform.ie` consolidated PDFs where available, falling back to original `irishstatutebook.ie` PDFs.
- **Central Bank of Ireland guidance** (`docs/national/ireland/guidance/cbi/`, 194 docs): collected from flat listing pages (Consumer Protection codes, Fitness & Probity, Payment / E-Money Institution sectors), two named direct PDFs (Cross-Industry Outsourcing + Operational Resilience), and one-level-deep hub traversal of the IAF, MiCAR, AML/CFT, and Credit Institutions sections.

To refresh: `.venv/bin/python download.py --source ireland`. Idempotent — re-running reports `unchanged` for files whose SHA-256 matches.

### Known gaps

- **DPC (Data Protection Commission)** is out of scope. A `sources.yaml` stub exists; no crawler is wired.
- **Hub traversal is one-level-deep.** Documents nested two or more levels under a hub page may be missed. AML/CFT in particular has deep substructure (per-sector ML/TF risk evaluations) where only the top-level subpage PDFs are pulled.
- **No consolidated/revised version available** for the IAF Act 2023 (too recent) or the Investor Compensation Act 1998 — original PDF used as fallback. The Central Bank Act 1942 revised PDF reflects amendments only up to the LRC's last consolidation.
- **CBI rotates URLs.** The `direct_pdfs` entries (cp138/cp140 paths) and the listed hub URLs were valid at build time but should be re-validated periodically. If a hub URL 404s, the run continues — the dead link surfaces as a `WARNING` in `logs/cbi.log`.
- **Minimum Competency Code** is not separately enumerated; if it's linked from the Consumer Protection page it's already in the corpus, otherwise it's missing.

## Out of scope

Parsing, search, database — downstream systems built on this corpus, not part of this folder.
