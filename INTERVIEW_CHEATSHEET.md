# Шпаргалка — Compliance Pipeline Interview

> Печатать целиком. Держать перед собой. Всё здесь — реальный код, не документация.

---

## ГЛАВНАЯ СХЕМА ПАЙПЛАЙНА

```
INGEST
  ├── EXTRACT_OBLIGATIONS ──┐  (параллельно)
  └── EXTRACT_CONTROLS ─────┘
         ├── SANCTIONS_SCREEN ──────────────┐  (параллельно)
         └── MAP_OBLIGATIONS_CONTROLS ──────┘
                  └── GAP_ANALYZE
                           └── GROUND_CHECK
                                    └── NARRATE
```

**Session states:** CREATED → UPLOADING → EXTRACTING → MAPPING → SCORING → SANCTIONS → COMPLETE

**Checkpoint:** `Session.completedStages` — если стадия уже в списке, при re-run пропускается (идемпотентность).

---

## ЧТО ТАКОЕ ctx (PipelineContext)

Общий in-memory объект на весь запуск. Живёт в памяти, не персистируется сам по себе.

| Поле | Тип | Откуда |
|------|-----|--------|
| `sessionId` | String | UUID сессии |
| `regulation` | String | текст регуляции (агрегирован IngestStage из kind=regulation документов) |
| `policy` | String | текст политики банка (kind=policy) |
| `briefText` | String | бриф (kind=brief) |
| `counterparties` | List | контрагенты для sanctions |
| `obligations` | **synchronized** List | заполняется ExtractObligationsStage параллельно |
| `controls` | **synchronized** List | заполняется ExtractControlsStage параллельно |
| `mappings` | **synchronized** List | заполняется MapStage |
| `sanctionHits` | **synchronized** List | заполняется SanctionsStage |
| `gaps` | ArrayList | заполняется GapAnalyzeStage (однопоточно) |
| `sseEmitterService` | — | отправляет события клиенту в реальном времени |

---

## ФАЗА 1 — INGEST

**Что делает:** для каждого документа сессии извлекает текст и заполняет `ctx.regulation / ctx.policy / ctx.briefText`.

### 4 пути извлечения текста:

| Условие | Что происходит | SSE |
|---------|----------------|-----|
| `doc.extractedText != null` ИЛИ `extractionS3Key != null` | читает из поля / S3 | `document.cached` |
| contentType содержит "pdf" | **AWS Textract** | `document.extracted` |
| contentType содержит "audio" | **AWS Transcribe** (ошибка → text="", не падает!) | `document.extracted` |
| всё остальное | скачивает из S3, guard: если > **5 MB** → text="" | `document.extracted` |

**Dedup:** `ConcurrentHashMap<docId, CompletableFuture>` — два concurrent запроса на один doc не запускают два Textract/Transcribe job.

**После extraction:** сохраняет в S3 по ключу `extractions/{docId}.txt` — этот файл используется в Ground Check фазе.

**pageCount:** считает `\f` (form-feed) в тексте; нет `\f` → `text.length() / 3000`; 0 → null.

**Агрегация:** все документы с kind="regulation" → `"\n\n".join(тексты)` → `ctx.setRegulation(...)`.

---

## ФАЗА 2 — EXTRACT OBLIGATIONS

**Источник:** `ctx.getRegulation()`.

**Пропуск:** если regulation null/blank → `stage.skipped`.

### Cache path (если doc.isObligationsExtracted() == true):
- Клонирует existing obligations из БД в текущую сессию
- Сохраняет **тот же ID** (content-addressable!) — важно для mapping cache
- SSE: `obligation.extracted` × N + `document.cached`

### Cold path (Bedrock):

**TextChunker — как режется текст:**
- Если ≤ **40 000 символов** → один чанк
- Граница: `\n\n` → `\n` → `". "` → hard cut (в окне ±2000 от target)
- **Overlap: 2 000 символов** (следующий чанк начинается на 2k раньше)
- Если остаток < **5 000 символов** → не создаётся, складывается в предыдущий
- Все чанки → **параллельно** в Bedrock

**Что идёт в Haiku:**
```
System: "You are a legal compliance expert. Extract all legal obligations...
         Only emit obligations you can ground in the provided text."

UserInput: {
  regulation_text: <чанк>,
  regulation_id: "REG-<sessionId>",
  article: "general",
  paragraph_id: "<номер чанка>"
}
```

### DDL операторы (Deontic Logic):

| Оператор | Значение | Пример |
|----------|----------|--------|
| **[O]** — Obligation | субъект **ОБЯЗАН** | "The bank shall report suspicious transactions within 24h" |
| **[F]** — Forbidden | субъект **НЕ ДОЛЖЕН** | "The bank shall not process payments to sanctioned entities" |
| **[P]** — Permitted | субъект **ВПРАВЕ** | "The bank may use automated screening tools" |

### Tool schema `extract_obligations.json`:

**Обязательные поля:** `deontic ["[O]","[F]","[P]"]`, `subject`, `action`, `source_text_snippet`, `extraction_confidence`

**Опциональные:** `conditions[]`, `risk_category`, `severity [low/medium/high/critical]`, `article`, `section`, `paragraph`

### Генерация ID obligation (детерминированная):
```
ID = "OBL-" + sha256(documentId + "\0" + subject + "\0" + action).substring(0, 24)
```
Одни и те же документы → всегда один и тот же ID → mapping cache работает корректно при повторном запуске.

### Grounding check (Java, не LLM!):
```java
needle = normalize(snippet).substring(0, Math.min(30, len))
// normalize = toLowerCase + replaceAll("\\s+", " ").trim()
if (!normalize(chunkText).contains(needle)) → ДРОП
```
- Проверяются первые **30 символов** нормализованного snippet
- Не найдено → `obligation.rejected` SSE, obligation не сохраняется
- Это строковый поиск, независимый от LLM

**SSE:** `obligation.extracted`, `obligation.rejected`, `document.cached`, `stage.skipped`

---

## ФАЗА 3 — EXTRACT CONTROLS

**Зеркало obligations, но из `ctx.getPolicy()` (kind=policy документы).**

### Ключевые отличия от Extract Obligations:

| | Extract Obligations | Extract Controls |
|--|---------------------|-----------------|
| Chunking | ДА (40k chars) | **НЕТ** (весь текст в один вызов) |
| Cache flag | `doc.isObligationsExtracted()` | `doc.isControlsExtracted()` |
| ID prefix | `OBL-` | `CTRL-` |
| ID formula | sha256(docId + subject + action) | sha256(docId + description) |

### Tool schema `extract_controls.json`:

**Обязательные:** `control_type`, `category`, `description`, `source_text_snippet`, `extraction_confidence`

**control_type:** `"technical"` / `"organizational"` / `"procedural"`

**category:** `"preventive"` / `"detective"` / `"corrective"`

**implementation_status:** `"planned"` / `"in_progress"` / `"implemented"` / `"unclear"`

Если `valueOf()` для control_type или category падает → логирует warn, поле null (не дропает весь control).

**Grounding check:** идентичен obligations — первые 30 символов snippet. Reject → `control.rejected` SSE.

**SSE:** `control.extracted`, `control.rejected`, `document.cached`, `stage.skipped`

---

## ФАЗА 4 — SANCTIONS SCREEN (параллельно с MAP)

**Пропуск:** если `counterparties` пуст → `stage.skipped`.

### Два уровня проверки:

**Уровень 1 — Java local table (точное совпадение):**
```java
normalized = name.toLowerCase().trim()
                 .replaceAll("[^a-z0-9 ]", "")  // только буквы/цифры/пробелы
                 .replaceAll("\\s+", " ")
// findByNormalizedName(normalized) → exact match в DynamoDB
```
Нашёл → `SanctionHit` с `matchScore=1.0`, `status=flagged`. В Python sidecar не идёт.

**Уровень 2 — Python sidecar (fuzzy matching):**

Все counterparties без local hit → POST `/sanctions/screen`

Python нормализация: lowercase + remove punctuation + remove legal suffixes + collapse whitespace

Fuzzy: **Dynamo scan по первым 6 символам** нормализованного имени → **Jaro-Winkler** similarity

| matchScore | Status |
|------------|--------|
| **≥ 0.9** | `flagged` |
| **≥ 0.7** | `under_review` |
| < 0.7 | `clear` |

**Fuzzy threshold:** default **0.92** — должен совпасть на 92%+ для hit.

### Деградация:
Если sidecar бросает `SidecarCommunicationException` → SSE `sanctions.degraded`, `sidecarHits = List.of()`.
**Pipeline не падает.** Но compliance officer должен знать: sanctions results неполные, нужна ручная проверка.

**SSE:** `sanctions.hit` (каждый hit), `sanctions.degraded`, `stage.skipped`

---

## ФАЗА 5 — MAP OBLIGATIONS → CONTROLS (параллельно с SANCTIONS)

### Константы:
```
BATCH_SIZE = 10           обязаций в одном батче (батчи последовательны)
KB_TOP_K = 20             сколько чанков берётся из Knowledge Base
RERANK_TOP_N = 5          сколько остаётся после Cohere reranker
MAX_CANDIDATE_CONTROLS = 20  максимум кандидатов для Haiku
```

### Candidate selection — 3 уровня fallback для каждой obligation:

**1. KB Retrieval + Reranking (основной путь):**
```
query = subject + " " + action + " " + riskCategory
→ KnowledgeBaseService.retrieveControls(query, top_k=20)
  → чанки отсортированы по KB score desc
  → match chunk → control:
     a) chunk.metadata["controlId"] → direct lookup в HashMap
     b) fallback: chunkText.contains(ctrl.description.substring(0,40).toLowerCase())
→ Cohere Reranker: rerank(query, matched, top_n=5)
  → возвращает top-5 по семантической релевантности
```

**2. Structural filter** (если KB пуст или упал):
```java
ctrl.category.name == obl.riskCategory                    // OR
ctrl.mappedStandards.any { contains(obl.subject) }        // OR
ctrl.mappedStandards.any { contains(obl.action) }
// limit 20
```

**3. Fallback** (если structural тоже пуст): первые 20 controls из allControls.

### Детерминированный ID маппинга:
```
mappingId = "MAP-" + sha256(obligationId + "#" + controlId).substring(0, 16)
```

### Cache check:
```java
existing = mappingRepository.findById(mappingId)
```
Нашёл → reuse, пометить `metadata.route="cached"`, Bedrock **не вызывается**.
Не нашёл → добавить в список `uncached`.

### Haiku вызов (один на ВСЕ uncached controls одной obligation):

**ObligationControlMatcher.match():**
```
UserInput: {
  obligation_id, obligation_subject, obligation_action, obligation_risk_category,
  candidate_controls: [{control_id, description, category, mapped_standards}, ...]
}
System: "Score each match 0-100 based on semantic alignment."
```

**Tool schema `match_obligation_to_controls.json`:**

| Поле | Тип | Обязательно |
|------|-----|-------------|
| `control_id` | string | ✓ |
| `match_score` | number 0-100 | ✓ |
| `reason` | string | ✓ |
| `mapping_type` | enum | нет |

**mapping_type enum:** `"direct"` / `"partial"` / `"requires_multiple"`

⚠️ **Важно:** в tool schema НЕТ `"indirect"` и `"none"` (хотя system prompt их упоминает). Если Haiku вернёт `"indirect"` → `MappingType.valueOf("indirect")` → exception → catch → default `MappingType.partial`.

### Критическая граница — 50:
```java
mapping.setGapStatus(score >= 50 ? GapStatus.satisfied : GapStatus.partial)
```
- **≥ 50** → `GapStatus.satisfied` → obligation считается покрытой
- **< 50** → `GapStatus.partial` → obligation уйдёт в gap analysis
- `metadata.route = "llm"` для новых, `"cached"` для реиспользованных

Сохранение: `mappingRepository.saveIfNotExists(mapping)` — атомарный upsert (не перезапишет если уже есть).

Audit: `"mapping_created"` с `{obligation_id, control_id, confidence, evidence_sha256s}`.

**SSE:** `mapping.computed` (каждый маппинг), `mapping.progress` (processed/total)

---

## ФАЗА 6 — GAP ANALYZE

### Кто "covered":
```java
coveredObligationIds = mappings
    .filter(m.mappingConfidence != null && m.mappingConfidence >= 50)
    .map(obligationId).toSet()
```

### Кто вообще "mapped" (хоть с каким score):
```java
mappedObligationIds = mappings.map(obligationId).toSet()
```

`uncovered` = obligations **НЕ** в `coveredObligationIds` → все scored **параллельно**.

### GapScorer — что отправляет в Haiku:
```
UserInput: {obligation_id, obligation_subject, obligation_action,
            risk_category, regulatory_penalty}
System: SCORE_GAP (с calibration anchors)
Tool: score_gap
```

### Два блока метрик:

**Legacy dimensions (severity_dimensions):**

| Измерение | Значение |
|-----------|----------|
| `regulatory_urgency` | срочность с точки зрения регулятора |
| `penalty_severity` | тяжесть потенциального штрафа |
| `probability` | вероятность реализации |
| `business_impact` | бизнес-последствия |

Java **игнорирует** `combined_risk_score` из tool output и пересчитывает сам:
```java
combined = (regulatory_urgency + penalty_severity + probability + business_impact) / 4.0
```

**Residual risk (5 измерений, взвешенный):**

| Измерение | Вес | Шкала |
|-----------|-----|-------|
| `severity` | **40%** | 0=тривиально, 1=экзистенциальная угроза |
| `likelihood` | **25%** | 0=маловероятно, 1=почти неизбежно |
| `detectability` | **15%** | 0=сразу заметно, **1=тихая катастрофа** ⚠️ |
| `blast_radius` | **10%** | 0=один пользователь, 1=вся организация |
| `recoverability` | **10%** | 0=легко откатить, 1=невозможно восстановить |

```
residualRisk = 0.40×severity + 0.25×likelihood + 0.15×detectability
             + 0.10×blast_radius + 0.10×recoverability
```

Missing values → 0.0 (не null).

**Calibration anchors в prompt (только для калибровки шкалы, не цитировать):**
- detectability 0.9 → "correspondent-bank SWIFT screening gap; undetected until de-risking notice"
- severity 0.9 → "sanctions screening offline 24h; high probability of prohibited transactions clearing"

### Классификация gap после scoring:
```java
gap.setGapType(GapType.control_missing)  // по умолчанию
gap.setGapStatus(GapStatus.gap)          // по умолчанию

if (mappedObligationIds.contains(gap.getObligationId())) {
    // есть маппинги но все <50
    gap.setGapType(GapType.control_weak)
    gap.setGapStatus(GapStatus.partial)
}
```

| Тип | Когда | Смысл для compliance |
|-----|-------|----------------------|
| `control_missing` | нет ни одного маппинга | этой области нет вообще никакого контроля |
| `control_weak` | есть маппинги, но все <50 | контроль есть, но недостаточен для этой нормы — **хуже чем кажется** |

`escalationRequired=true` → статус пайплайна **red**.

Если scoring упал → `defaultGapScore()`: всё null, escalation=false, residualRisk=0.0.

Gap ID: случайный `"gap-" + UUID` (не детерминированный, в отличие от obligation/control).

**SSE:** `gap.identified` (с полным DTO)

---

## ФАЗА 7 — GROUND CHECK

**Что проверяет:** семантические обоснования маппингов (`semanticReason`) против исходного текста документа.

### Партиционирование:

| Условие маппинга | Что происходит |
|-----------------|----------------|
| `semanticReason == null` | полностью пропускается |
| `route="cached"` И нет "ground-check failed" в reviewerNotes | skip, SSE `ground_check.verified` сразу |
| всё остальное | идёт на верификацию |

### Батчи по 10, параллельно:

**1. Загрузка документа из S3:**
```
S3 key: "extractions/{docId}.txt"  (создан IngestStage)
Обрезается до 80 000 символов (~20k токенов)
```

**Если S3 недоступен (NoSuchKeyException или Exception):**
```java
// FAIL CLOSED — весь батч
m.setReviewerNotes("ground-check failed: source document text unavailable")
mappingRepository.save(m)
SSE: ground_check.dropped × batch.size()
return  // батч прерывается
```

**2. Payload в Nova Pro:**
```json
{
  "documents": {"docId1": "full text...", "docId2": "..."},
  "checks": [
    {"mapping_id": "...", "claim": "<semanticReason>", "doc_id": "docId1"}
  ]
}
```
Один doc text не дублируется в каждом check — передаётся по ссылке на `doc_id`.

**Модель:** `Nova Pro` (более мощная, чем Haiku).

**3. Результаты:**

| verified | Что происходит |
|----------|----------------|
| `false` | `mapping.reviewerNotes = "ground-check failed: claim not found in source text"` → save → audit `mapping_ground_check_failed` → SSE `ground_check.dropped` |
| `true` | audit `mapping_verified` → SSE `ground_check.verified` |
| отсутствует | default `true` (`.asBoolean(true)`) |

**Маппинг НЕ удаляется** — остаётся в БД с reviewer note как evidence.

**4. Обработка ошибок Bedrock:**
```
batch size > 1 → split пополам → recursive retry каждой половины
batch size == 1 → ошибка → FAIL OPEN → applyResult(mapping, true)
                  (не терять маппинг из-за временной ошибки модели)
```

**5. Re-run поведение:**
```java
boolean alreadyFailed = m.getReviewerNotes() != null
        && m.getReviewerNotes().contains("ground-check failed")
if (isCached && !alreadyFailed) → skip
else → toCheck  // провалившийся cached маппинг перепроверяется!
```

---

## ФАЗА 8 — NARRATE

### Логика overall цвета:
```java
if (gaps.isEmpty())                          → "green"
if (any gap.escalationRequired == true)      → "red"
else                                         → "amber"
// gaps.size() > 3 → тоже amber (dead code — оба branch дают amber)
```

### generateNarrative() → Haiku, max_tokens=512:
```
UserInput: {
  overall_severity, gap_count, mapping_count,
  top_gaps: первые 3 gap с {obligation_id, narrative, escalation,
                             regulation, article, section, source_text}
}
```

**КРИТИЧЕСКОЕ ПРАВИЛО в system prompt:**
> "Every regulatory reference — regulation name, directive number, article, section, numeric threshold — MUST appear verbatim in the provided gaps' source_text, regulation, article, or section fields. **Never invent, infer, or guess.**"

Если ошибка → fallback строка, pipeline не падает.

### После narrative:
- `session.executiveSummary = narrative` → сохранить в БД
- `ReportService.generate(ctx, summary)` → PDF proof pack → S3 → `ctx.reportUrl`
- Report failure: **non-fatal**, pipeline продолжает

### SSE последовательность (финал):
```
narrative.completed  → ExecutiveSummaryDTO
pipeline.completed   → {summary, reportUrl}
done                 → {session_id}
sseEmitterService.complete(sessionId)  → закрыть SSE стрим
```

### JurisdictionRun update (если задан launchId):
`status=COMPLETE`, `verdict=overall.toUpperCase()`, `gapsCount`, `sanctionsHits`, `proofPackS3Key`

---

## ЗАЩИТА ОТ ГАЛЛЮЦИНАЦИЙ — 5 слоёв

### Слой 1 — Java grounding check (extraction)
Haiku обязан дать `source_text_snippet`. Java проверяет первые 30 символов нормализованного snippet против исходного чанка. **Строковый поиск, не LLM.** Failure → дроп obligation/control.

### Слой 2 — Ground Check (Nova Pro)
Отдельная, более мощная модель проверяет `semanticReason` каждого маппинга против полного текста документа. Две разных LLM — независимая верификация.

### Слой 3 — Narrative grounding rule
Explicit запрет в system prompt: нельзя упоминать директивы/артикулы/пороги, которых нет в `source_text` gap-а. Haiku видит только данные этой сессии.

### Слой 4 — Structured output (tool-use)
Все LLM-вызовы через Bedrock tool-use — JSON schema с enum ограничениями. Нет свободного текста где возможны фабрикации. `deontic` может быть только `[O]/[F]/[P]` — ничего другого.

### Слой 5 — Calibration anchors
В score_gap prompt — конкретные 0.2/0.5/0.9 якоря с реальными compliance примерами. Предотвращает score drift (когда всё получает 0.5 по умолчанию).

### Что система НЕ защищает (говорить честно):
- **Semantic error в deontic:** цитата реальная, интерпретация [O] vs [P] неверная — Java не поймает
- **Неполное извлечение:** пропущенные obligations просто не появляются (false negative)
- **Score accuracy:** Haiku может дать 49 где реальная связь есть, или 51 где нет
- **Cached mappings:** ground check пропускается для `route=cached` без prior failure

---

## КЛЮЧЕВЫЕ ЦИФРЫ — запомнить

| Параметр | Значение |
|----------|----------|
| Порог "достаточного" маппинга | **≥ 50** (не > 50, именно >=) |
| Chunk size | **40 000 символов** |
| Chunk overlap | **2 000 символов** |
| Минимальный chunk | **5 000 символов** |
| KB top-k | **20** чанков |
| Rerank top-n | **5** контролей |
| Max candidates для Haiku | **20** |
| Batch size (Map) | **10** obligations |
| Batch size (Ground Check) | **10** маппингов |
| Ground check doc cap | **80 000 символов** |
| Plain text size guard | **5 MB** |
| Grounding needle | первые **30 символов** |
| Jaro-Winkler fuzzy threshold | **0.92** |
| Sanctions status: flagged | score **≥ 0.9** |
| Sanctions status: under_review | score **≥ 0.7** |
| Narrative max tokens | **512** |
| Obligation ID prefix | `OBL-` + sha256[:24] |
| Control ID prefix | `CTRL-` + sha256[:24] |
| Mapping ID | `MAP-` + sha256[:16] |
| Gap ID | `gap-` + UUID (случайный) |

---

## ВОПРОСЫ COMPLIANCE OFFICERS И ОТВЕТЫ

---

**"Насколько точно система мапит правила и не придумывает ли она законы от себя?"**

> Система не устраняет галлюцинации полностью — это невозможно с LLM. Три независимых барьера: Java строковый поиск при extraction, Nova Pro при verification маппингов, explicit запрет в prompt при narrative generation. Вывод — structured evidence package с флагами доверия, не compliance decision. Финальное решение принимает человек.

---

**"Что произойдёт если sanctions sidecar недоступен?"**

> Pipeline продолжает работу, эмитит `sanctions.degraded`. Это сознательный выбор — gap analysis не должна блокироваться на availability внешнего сервиса. Compliance officer видит явный сигнал и проверяет sanctions вручную. Маппинги, gaps и narrative генерируются нормально.

---

**"Почему threshold именно 50?"**

> Это граница между семантическим соответствием и его отсутствием. Haiku даёт 0-100, и 50 — точка, где система считает, что контроль хотя бы умеренно адресует obligation. Всё ниже — partial coverage, что gap analysis расценивает как недостаточное. Это конфигурируемый параметр для обсуждения с регулятором как часть методологии.

---

**"Что такое control_weak и чем это хуже чем control_missing?"**

> `control_missing` — для этого требования вообще нет контроля. `control_weak` — контроль существует, но не набрал 50 баллов: он не покрывает эту конкретную норму. `control_weak` часто хуже с операционной точки зрения: создаёт ложное ощущение compliance ("у нас есть процесс"). Регулятор скажет: "процесс есть, но он не покрывает эту конкретную норму."

---

**"Почему detectability весит только 15% а severity 40%?"**

> В финансовом compliance даже маловероятный, но экзистенциальный риск (отзыв лицензии, enforcement action) перевешивает всё остальное. В отличие от простого среднего четырёх измерений (legacy combined score), severity-dominant модель не позволяет "размыть" критический риск тремя низкими баллами по другим осям.

---

**"Что такое detectability 0.9?"**

> Тихая катастрофа: failure, которое невозможно обнаружить без внешнего триггера. Пример: gap в SWIFT screening у correspondent bank — о нём узнаёте только когда приходит de-risking notice или регулятор. Это не операционный риск, это риск регуляторного обнаружения.

---

**"Как мы можем доверять результату AI для регулятора?"**

> Четыре точки доверия: 1) Audit trail с SHA-256 хешами evidence — криптографическая доказуемость цепочки. 2) Детерминированные IDs — те же документы дают те же маппинги, воспроизводимость. 3) `escalationRequired` флаг — обязательный human review для критических gaps. 4) Reviewer notes — флаги ground check failure остаются в маппинге как evidence. Система не принимает решения, она структурирует доказательную базу для человека.

---

**"Как вы обеспечиваете explainability для регулятора?"**

> У каждого маппинга есть `semanticReason` — конкретное обоснование почему control X адресует obligation Y. Это верифицировано Nova Pro против исходного документа. У каждого gap есть `narrative` и `recommendedActions` с `suggested_owner` и `effort_days`. Регулятор видит полную цепочку: норма → где в документе → какой контроль → почему → насколько хорошо → что делать.

---

**"Что если compliance team не согласна с AI-скором маппинга?"**

> Система даёт рекомендацию, не решение. Compliance officer может добавить reviewer note к маппингу, изменить статус. Audit trail сохранит оба состояния — до и после review. Это соответствует EU AI Act требованию human oversight для high-risk AI systems.

---

**"Что будет если пайплайн падает на середине?"**

> Checkpoint system: `Session.completedStages` — список пройденных стадий. При re-run стадии из этого списка пропускаются. Маппинги сохраняются через `saveIfNotExists` — повторный запуск не создаст дубликаты. Детерминированные IDs гарантируют что при re-run те же пары obligation/control дадут те же маппинги.

---

## GOTCHAS — что отличается от документации

| В документации | В коде (реальность) |
|----------------|---------------------|
| "sufficient при score > 50" | **`>= 50`** (включая 50) |
| mapping_type: direct/partial/indirect/none | tool schema: **direct/partial/requires_multiple** |
| combined_risk_score из tool | **пересчитывается Java как average 4 dims**, tool-значение игнорируется |
| gaps.size() > 3 → amber | **мёртвый код**: оба branch дают amber |
| sanctions сразу к sidecar | **сначала Java exact local lookup**, только без hit → sidecar |

---

## АРХИТЕКТУРА ДОВЕРИЯ (одна страница для compliance officer)

```
Regulation text
      │
      ▼
[INGEST] → извлекает текст (Textract/Transcribe/S3)
      │
      ▼
[EXTRACT OBLIGATIONS] → Haiku tool-use → Java grounding check (30 символов)
      │                  ↓ snippet не в тексте → ДРОП + obligation.rejected
      │
      ▼
[MAP] → KB + Rerank → Haiku scoring (0-100)
      │                ↓ score <50 → partial coverage
      │                ↓ score ≥50 → satisfied
      │
      ▼
[GROUND CHECK] → Nova Pro верифицирует semanticReason
      │          ↓ не найдено → reviewer note + audit (маппинг не удалён)
      │          ↓ S3 недоступен → fail closed (весь батч dropped)
      │
      ▼
[GAP ANALYZE] → obligations без coverage ≥50 → Haiku scoring → Gap record
      │         ↓ есть маппинги <50 → control_weak
      │         ↓ нет маппингов → control_missing
      │
      ▼
[NARRATE] → Haiku executive summary (запрет на изобретение ссылок)
      │     ↓ green/amber/red + narrative + recommendedActions
      │
      ▼
Proof pack (PDF) → S3 → Compliance Officer
```

**Главный принцип:** система не делает compliance decisions. Она создаёт структурированный, аудируемый, воспроизводимый evidence package с флагами для человека.