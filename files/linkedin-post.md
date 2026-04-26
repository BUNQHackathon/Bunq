A few days ago we went to Bunq Update in Amsterdam to ask how Bunq launches into new countries. Kris Wulteputte pulled in people from Risk Operations, Expansion and compliance to answer our questions.

We heard overlapping pains:

▪ Tracking obligations across jurisdictions is constant work.
▪ Launches take a long time.
▪ Sanctions screening is its own slow machine.
▪ Each jurisdiction has its own timing rules for the same obligation.

We spent 17 hours building a prototype. We called it Prism.


What Prism does

You give it a product brief and a list of jurisdictions. It pulls regulations from EUR-Lex, EBA, ECB, ESMA, FATF, the Central Bank of Ireland and the Irish Statute Book. To find which apply to a given launch we wrote our own discovery helper.

It then extracts every obligation, retrieves your internal controls from a separate knowledge base, and proposes how each obligation maps to which control. Gaps get scored across five risk dimensions. Counterparties get screened against OFAC, EU, UN and UK sanctions. Every claim is ground-checked against its source paragraph.


The output: an audit-ready proof pack

Five files per run.

▪ cover.pdf: launch, jurisdiction, verdict (RED/AMBER/GREEN), counts, policy versions, unresolved gaps.
▪ gaps.pdf: every gap with obligation, four-dimension severity, narrative, remediation actions, owner, target date.
▪ mappings.xlsx: obligation-by-control matrix with confidence, mapping type, gap status.
▪ sanctions.pdf: counterparties, lists checked, matches.
▪ audit_trail.json: every pipeline decision in order with previous-hash and entry-hash. SHA-256 chained. Change one byte and every downstream entry stops verifying.


The important word is "proposes"

Prism is advisory. The model suggests obligation-to-control mappings. A human can accept, override, edit, or write a new control entirely. Every suggestion comes with its exact source. A chat over the full document set points you at the specific paragraph in the specific document.

The model never picks the verdict. RED, AMBER, GREEN are computed deterministically in Java from the dimensions the model emits. "The AI said so" is not an answer an auditor accepts. The human stays at the wheel. Controls are never reinterpreted by the model, only matched against.


The stack

AWS Bedrock (Claude Opus, Sonnet, Haiku, Nova fallback), three Bedrock Knowledge Bases on S3 Vectors, DynamoDB, ECS Fargate, Spring Boot 4 on Java 25, Terraform. React frontend with a globe.gl 3D map and streaming RAG chat with first-class citations. AWS and Anthropic are both part of Bunq's production stack.


It's a prototype. 17 hours, four people. We aren't pretending this is a finished product.

Built with Mikhail Zhemchuzhnikov, Leonid Margulis and Andrew Kalinenko. Thank you to Bunq for the hospitality at Bunq Update and for hosting the hackathon, to Anthropic for Claude, and to AWS.

Demo video in the comments. Happy to walk through how it works.
