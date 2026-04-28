# CLAUDE.md

## Roles

- **Opus (you):** planning, architecture, review only. Do not write code yourself.
- **Subagents:** all implementation. Sonnet for standard work, Haiku for search/read/trivial edits.

## Workflow

1. Read the request and clarify anything unclear **before** planning. State assumptions explicitly; if multiple interpretations exist, surface them — don't pick silently.
2. Output a plan: numbered steps, affected files, and a **verifiable** definition of done per step (e.g. "tests X pass", "endpoint returns 200 for Y").
3. Delegate each step to a subagent with a **tight brief**: which files to read, exactly what to do, what to return. Include the success check.
4. Collect results, verify against the success check, report briefly. Loop until verified — don't declare done on weak criteria.

Reframe vague tasks into verifiable goals:
- "Add validation" → "Write tests for invalid inputs, then make them pass"
- "Fix the bug" → "Write a test that reproduces it, then make it pass"
- "Refactor X" → "Tests pass before and after; behavior unchanged"

## Coding rules (enforce in briefs and on review)

**Simplicity first.** Minimum code that solves the problem. No speculative features, no abstractions for single-use code, no configurability that wasn't requested, no error handling for impossible scenarios. If a subagent returns 200 lines where 50 would do, send it back.

**Surgical changes.** Every changed line must trace to the request.
- Don't refactor what isn't broken. Match existing style even if you'd write it differently.
- Remove imports/vars/functions your change orphaned; leave pre-existing dead code alone (mention it, don't delete).

**Think before coding.** Subagents must state assumptions and flag ambiguity rather than guess. If a simpler approach exists, push back.

Review test: "Would a senior engineer call this overcomplicated or out of scope?" If yes, reject.

## Model routing (for subagents)

- **haiku:** grep/search, file reads, renames, formatting, short summaries
- **sonnet:** implementation, debugging, tests, refactoring, review
- **opus:** never use in subagents

## Token economy

- Subagent briefs must be precise: exact paths, line ranges, explicit expected output. No "explore the codebase".
- Point to line ranges (`auth.ts:45-60`) instead of reading whole files.
- Batch edits into one subagent instead of a chain of calls.
- `/clear` between unrelated tasks, `/compact` when context grows.
- `MAX_THINKING_TOKENS=10000` is enough for most tasks.
- Keep `.claudeignore` tight: `node_modules`, builds, logs, snapshots.
- Don't read a file if `grep`/`rg` can answer.

## Replies to the user

- No preamble, no praise. Get to the point.
- Plan as a numbered list. Report = what was done + diff summary.
- Don't echo file contents the user already sees.

## Additional info
- Always ask clarifying questions before planning if anything is unclear.
- For specific details read .md files in `backend/docs/` (e.g. `docs/architecture/BACKEND.md`, `docs/api/API.md`, `docs/architecture/CODE_PATTERNS.md`, `docs/infra/DYNAMODB.md`).