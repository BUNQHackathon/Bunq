# Interview Prep — bunq, Friday

## TL;DR — what matters most

- **Interviewers:** Arjan Molenkamp (International Expansion — operator, not engineer) + Matteo Tesei (Ops, 5+ yrs bunq — culture + ownership check).
- **Structure:** intros → short demo → Q&A (both sides).
- **Vanessa's 3 use cases** are the spine of the demo. Address each explicitly.
- **Don't overclaim.** No "production-ready," no "world-class." Honest is stronger.
- **Bridge slide before demo** — 60-90 seconds explaining how Prism maps to their 3 use cases.

---

## Personal intros — 1-2 min each

### Michael (finalized)

> Hi, I'm Michael. On the Prism project, I was responsible for the backend infrastructure and deployment.
>
> My background has mostly been in end-to-end contract work. I've built a fintech platform alongside Mikhail, my teammate here, as well as a compliance tool for an aerospace startup in Delft.
>
> My motivation to join bunq is: I want to work in a product-driven environment. I want to be in a place where I can build and own systems that directly influence the business.

### Open structure for other team members

1. Who I am (1 line — name + 1-line context)
2. What I owned on Prism (1 line — specific area)
3. What I've built before (1-2 lines — most concrete prior work)
4. Why bunq (1 line — product-driven environment / culture / deep stack)

Keep it crisp. No life story.

---

## Demo flow

### Bridge — opener + use case mapping (finalized, ~3 min)

> Before we jump into the demo, I want to give a quick background on why we built Prism.
>
> The initial idea actually came from an interview I saw with Nikolay Storonsky. He was talking about how regulatory compliance is the biggest bottleneck for global expansion, and how solving it gave Revolut a massive competitive advantage. I brought this to the team, we realized bunq probably faces the exact same pain, so we went to the bunq Update to validate it.
>
> And Arjan, this is exactly what we talked about with you at the event. When you described the expansion bottlenecks to us, it was basically our project brief spoken out loud. That's when we knew we were building the right thing.
>
> Now, to address the email Vanessa sent us. We received the three specific use cases you are looking to solve. Prism was originally built with external market expansion and feature control in mind — which is why in our demo, you'll see we actually built two modes: tracking specific features across different jurisdictions, and vice versa. But the underlying framework is highly adaptable. Basically, we built this tool specifically for people at the second line of defense — to give your compliance officers superpowers, not to replace them.
>
> Let's look at your use cases:
>
> **For your first use case: one queryable source for policies.** Prism already does this. We have a chat surface over all uploaded documents with citations back to the source paragraph. Right now, you can configure the source to be either bunq's internal policies or external regulations, depending on your goal. We'll show this in a minute.
>
> **For your second use case: policy-as-a-checklist with a compliance score and recommendations.** This is very close to what we already built. Our pipeline extracts controls and regulations, maps them to each other, scores the gaps across 5 metrics, and emits a verdict. To turn it into your desired product, we just need to adapt the pipeline to extract obligations directly from your policies instead of external regulations. It's the exact same engine, just a different perspective.
>
> **For your third use case: an agent that tracks improvements over time and alerts when laws change.** We don't have this fully built today, but the core foundation is already there. We know how important verifiable solutions are. In fact, we already implemented a basic audit log during the hackathon to track the steps and decisions the AI makes. To build the tracking you want, our next technical step would be to write a system that compares these logs to see exactly how policies changed over time. As for the alerts, during the hackathon, we actually built a bot that scrapes regulatory documents from the internet to build our initial regulations database. To complete your use case, we would just need to upgrade that existing bot to monitor for updates, and plug in a notification system.
>
> So, to conclude out of the 3: the first works as-is, the second is a reframe of what's already there, and the third is a clear area for improvement based on things we've already drafted.
>
> With that, I would like to share my screen and hand it off to [имя], who will walk you through the actual demo.

### Delivery notes

- **Pause 1-2 seconds** between "your first / second / third use case." Lets the room mentally tick off each one.
- **Don't speed-read** "one week of work" / "different perspective" / "clear area for improvement." Those are the maturity signals.
- **When you mention Arjan**, brief eye contact (1 sec, not a stare). Acknowledgement, not validation-seeking.
- **"Second line of defense"** — this is the moment you signal you know the vocabulary. Say it cleanly, no hedging.
- **"Superpowers, not to replace them"** — emphasized slightly, this is your strongest values phrase in the bridge.
- **Final transition** — short pause before "With that," then steady to the handoff.

### Demo sequence (10-15 min total)

1. **Use case 1 demo (2-3 min)** — chat over policies KB. Ask a question about a specific bunq policy, show citation with source paragraph. Show KB selector if implemented (toggle to All sources to demonstrate breadth).
2. **Use case 2 demo (5-7 min)** — full pipeline run on one policy + one jurisdiction. Walk through: input → obligations extracted → controls mapped → gap scored → R/A/G verdict → proof pack opens.
3. **Use case 3 placeholder (1-2 min)** — verbal, no live demo. "Here's what we'd build next — diff between two runs reveals trend, audit log provides the immutable history, scheduled re-runs trigger on regulation changes."
4. **Architecture quick-look (1 min)** — one diagram showing the 8-stage pipeline + 3 KBs + DynamoDB + Bedrock. Don't get lost in detail.

---

## Q&A bank

### What they may ask us

**Q: How is Prism different from Vanta / Drata / Hyperproof?**

> Vanta/Drata/Hyperproof are GRC platforms for SOC2/ISO27001, mostly for SaaS companies. They work with standard tech controls (access logs, MFA, encryption) and evidence pulled from SaaS integrations.
>
> Prism is for banking regulatory compliance — EBA, ECB, PSD2, DORA, AML. Different class of problem. The real adjacent space is CUBE, Corlytics, Compliance.ai, 360factors, Nasdaq AxiomSL — enterprise RegTech players for banks.

**Q: How is it different from CUBE / Compliance.ai / 360factors?**

> Each solves a different slice:
> - **CUBE** tells you a regulation changed (upstream detector). Doesn't issue a per-product verdict.
> - **Compliance.ai** routes the change as a task (workflow). Leaves the verdict to a human.
> - **360factors Predict360** is a full GRC operating system — broad, configurable, but empty by default; you populate it.
> - **Nasdaq AxiomSL** is regulatory reporting infrastructure — what gets submitted to whom and when.
>
> None of them produce a per-(product × jurisdiction) advisory verdict bundled with a tamper-evident proof pack. That combination — deterministic R/A/G + hash-chained audit + bundled proof — is the lane Prism is in.

**Q: Why not just buy one of these?**

> If bunq wanted to digitize *all* compliance ops top-to-bottom, Predict360 or similar is the right product — long sales cycle, big integration project, but covers everything. Prism is narrower and opinionated: turn weeks of manual obligation-to-control mapping per (product, jurisdiction) into hours of advisory + an audit-ready proof pack. Different question.

**Q: How do you handle hallucinations?**

> Three layers:
> 1. **Tool-use structured output** — model emits JSON via `extract_obligations`, `extract_controls`, `match_obligation_to_controls`. No free text, schema validated server-side.
> 2. **Ground-check stage** — Amazon Nova Pro re-validates every claim against its source paragraph, in batches of 50 mappings per call.
> 3. **Hash-chained audit log** — every decision references its source chunk; reviewer can verify after the fact.
>
> Verdict itself is deterministic in Java — model never picks the colour. The model proposes, Java decides, human can override.
>
> Honest take: hallucinations don't disappear, they become auditable. Human review catches what remains.

**Q: What's different from prompting Claude/GPT directly?**

> Direct prompting doesn't give you:
> - Cross-document grounding via semantic retrieval across 3 KBs
> - Hash-chained audit trail per decision
> - Idempotency (deterministic mapping IDs)
> - Cost control through per-stage model selection + global concurrency cap
> - Schema validation through tool-use
>
> Direct prompting is an experiment. Prism is an architecture you could put in front of a regulator.

**Q: How do you handle regulation changes over time?**

> Today, we don't. Honest answer.
>
> But the foundation supports it: documents are content-addressable in DynamoDB (partition key = SHA-256 of file bytes), audit log stores source of every decision, sessions are checkpointed and re-runnable.
>
> Next step: feed monitoring on EUR-Lex/EBA RSS or JSON feeds, diff detection between regulation versions, scheduled re-runs of impacted obligations, alerts when an already-mapped control loses coverage. That's exactly Vanessa's use case 3.

**Q: How do you handle ambiguity in regulatory mapping?**

> Prism is advisory AI, not autonomous. Model proposes, Java decides verdict, human accepts/overrides/edits.
>
> Every proposed mapping ships with confidence score + clickable source paragraph. Reviewer can accept, override with reasoning, edit existing mapping, or write a new control entirely.
>
> Ambiguity is modelled explicitly — three of the five risk dimensions (likelihood, detectability, recoverability) capture different facets of uncertainty rather than treating mapping as binary.

**Q: Does Prism do policy-as-checklist (Vanessa use case 2)?**

> Right now, not in that direction. Prism currently goes regulations → obligations → check coverage by controls. That's external compliance.
>
> Vanessa described the inverse: policies → obligations → check actual evidence. Same machine, opposite arrow. Concrete change: ExtractObligationsStage source = policy instead of regulation, mapping stage redirects to evidence documents, UI section labels swap.
>
> Estimate: one week of work. Infrastructure (audit log, gap engine, proof pack) reused as-is.

**Q: Does Prism do trend tracking / improvement-area agent (Vanessa use case 3)?**

> Not yet. The foundation is the hash-chained audit log — it already stores every decision with timestamp and source. Diff between two session runs on the same policy = trend. Annual review reminders and alerts on law changes are separate integrations on top — EUR-Lex feed, cron triggers — not core engine work.

**Q: Why bunq vs. a startup?**

(Personal — placeholder, fill in own answer)

Key beats to hit: culture (extreme ownership, flat, fast), AI-native banking (you're already building this), real regulatory complexity as a learning environment, deep tech stack which won't exist at a 5-person startup.

### What we ask them

1. **What does compliance handling look like at bunq today?** What's the current workflow for new-market obligation mapping — who owns it, what tools, where's the friction?
2. **If we were building this for real inside bunq, what would the MVP look like to you?** Which of the three use cases is highest-priority right now?
3. **Build vs buy — where is bunq leaning?** Is the strategy to assemble best-of-breed (CUBE + AxiomSL + internal tooling) or build internal capability?
4. **What would success look like at 1, 3, 6 months?** Helps us calibrate scope and signal that we think in milestones.
5. **Who would be the internal sponsor / team lead?** Asking helps us understand the political surface — and signals we're thinking about how work actually lands.

---

## Reference sections

### DORA in 60 seconds (for talking to Arjan)

EU Regulation 2022/2554, in force January 2025. Mandates operational resilience for financial institutions. Five pillars:

| Pillar | Substance |
|---|---|
| ICT Risk Management | Board-approved framework, documented RTO/RPO |
| ICT Incident Reporting | Tight timing: 4h initial, 72h intermediate, 1 month final |
| Resilience Testing | Annual testing + TLPT every 3 years for big banks |
| Third-Party Risk | **Register of Information** — every ICT vendor, every contract, sub-outsourcing; annual filing |
| Information Sharing | Optional threat intelligence sharing |

**Killer talking point for Arjan:** "Every new market entry means new ICT third-party contracts that land in bunq's Register of Information. Prism doesn't auto-populate the Register today, but DORA Article 28 is itself a set of obligations — and our gap-analysis pipeline can verify Register completeness per jurisdiction. Same engine, applied to ICT third-party domain."

### Three lines of defence

Not about license status — about **internal organization** of bunq.

- **1st line (Business / Risk owners):** teams that do the work and own risks in execution. Onboarding, Payments engineering, Customer Service. They *execute* controls.
- **2nd line (Compliance / Risk management):** independent function that sets policies, oversees 1st line, runs gap analyses, reports to board. Vera (formerly), Umut (currently), Kris as CRO. They *define and validate*.
- **3rd line (Internal Audit):** independent assurance over 1st and 2nd, reports directly to the board. Periodic deep reviews. They *verify the verifiers*.
- **External (sometimes called 4th line):** regulators (DNB, ECB, EBA), external auditors.

**Where Prism sits:** It's a **2nd-line tool**. Compliance officers use it for obligation → control mapping and gap analysis. But its outputs (proof pack with hash-chained audit) are useful across all three lines + external regulators. The audit trail is what makes it cross-line usable.

**Wrong framing to avoid:** "Prism sits in 3rd line." It doesn't — it serves 2nd line primarily, with outputs that auditors and regulators consume downstream.

### License realities (for Arjan)

- **EMI license** (Electronic Money Institution): 12–18 months. For payment services and e-money.
- **Full banking license** (CRD IV credit institution): 2–3+ years. Needed for deposit-taking and lending.
- **bunq has a full banking license through DNB** → European passport across EEA.

What this means for Arjan: his job isn't "obtain a license in Germany." His job is "navigate host-country supervisory expectations + local AML/CFT transposition + product-level compliance mapping per jurisdiction." Prism helps with the third layer.

### Competitor landscape (one-liners)

- **CUBE Global** — regulatory change detector. 10,000+ regulators, 750 jurisdictions. Tells you a rule changed.
- **Corlytics** — regulatory risk analytics + enforcement-action analysis. Quantifies regulatory risk in aggregate.
- **Compliance.ai** — workflow platform for regulatory change. Routes obligations as tasks to compliance teams.
- **360factors Predict360** — full GRC operating system. Policy management, RCSA, audit, attestations, vendor risk — all in one platform.
- **Nasdaq AxiomSL** — regulatory reporting infrastructure. Used by 90% of G-SIBs including Revolut. About *what gets submitted* to whom and when.

**Adjacent but different class:** Vanta, Drata, Hyperproof — these are SOC2/ISO27001 GRC for SaaS companies. Not the same market.

### Industry vocabulary (use these, don't fake it)

- **Obligation inventory** — master catalog of what the firm must do.
- **Applicability assessment** — determining which regulations apply to which products/jurisdictions.
- **Regulatory deconstruction** — turning vague legal text into specific, testable requirements.
- **Control inventory / Control library** — what the firm actually does.
- **Gap analysis** — where controls don't cover obligations.
- **Control owner** — named individual responsible for executing a control and producing evidence.
- **Evidence trail / Audit-ready evidence** — chronological, attributable proof that controls executed.
- **Regulatory Change Management (RCM)** — formal process for detecting + implementing rule changes.
- **Horizon scanning** — monitoring proposed rules before they're binding.
- **Three lines of defence** — see above.
- **Expert In The Loop (EITL)** — Compliance.ai's term for human-validated ML outputs.

---

## Anti-patterns — what NOT to do

1. **Don't say "production-ready."** Arjan has run regulated banks; the gap between hackathon and production is obvious to him. Honest framing wins.
2. **Don't trash competitors.** Frame as "different question / different lane," not "they suck."
3. **Don't fake domain knowledge.** If they use a term you don't know, ask: "By X do you mean Y or Z?" Better than nodding through.
4. **Don't oversell trend-tracking.** Use case 3 doesn't exist yet. Be clear.
5. **Don't pitch Prism as a finished product.** It's a hackathon foundation. Pitch the team + the architecture + the trajectory.
6. **Don't say "Prism sits in 3rd line."** Wrong line. Say 2nd-line tool with outputs that span all three.
7. **Don't quote regulation article numbers.** If pressed, "Article 28 of DORA" yes; "subsection 4(b) of Annex II" no.
8. **Don't argue if challenged.** Show curiosity: "That's a fair pushback — how would you approach it?" Then listen.

---

## Logistics checklist

- [ ] Demo flow rehearsed end-to-end at least once
- [ ] Backup screenshots/recording in case live demo breaks
- [ ] AWS connected and accessible from demo machine
- [ ] Chat with KB selection working
- [ ] UI rename (Jurisdictions → Policies) done
- [ ] Architecture diagram ready (one slide, not five)
- [ ] LinkedIn post link handy in case useful
- [ ] Demo video link handy as fallback
- [ ] Each team member knows their part of the demo
- [ ] One person owns timing / cuts off if someone rambles

---

## Open items (for tomorrow — to be filled by Q&A subagent)

- [ ] Full polished answers to the 9 Q&A items above (currently draft-level)
- [ ] Personal intro for each team member
- [ ] Verbatim bridge-slide script (currently close-to-final)
- [ ] Specific demo script with timing per section
- [ ] Backup answers for "Why didn't you win the hackathon?" if asked
- [ ] Backup answers for "What would you build first if hired?"
- [ ] Personal "Why bunq" answers per team member
