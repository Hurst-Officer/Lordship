# Lordship
A barebones open source project for managing manufactured housing communities.


Lordship
Open source property management software built for the real world — specifically, the part of the real world still running mobile home parks out of Excel spreadsheets OR trying to ditch expensive software.
Lordship is a full-stack platform designed to replace the duct tape: the VBA macros, the shared drives, the rent rolls that haven't been touched since 2011. It is not trying to be Yardi. It is trying to be the thing small and mid-size operators can actually use.

Why this exists
Most property management software is expensive, generic, and built by people who have never read a lease in their life. Mobile home park operators in particular get the short end — the compliance surface is unique, the tenant relationships are long-term, and the data is almost always a disaster inherited from whoever came before.

What it is (right now)
Lordship is in early development. The current focus is read — getting clean, reliable data visible in a usable interface before anything else gets built.

Current scope:

Ingest tenant and lot data from existing Postgres pipelines
Display park → lot → tenant relationships cleanly
Query historical data shards (legacy records that can't be cleanly linked to current tenants, treated as immutable artifacts rather than records to be merged)
Washington State 59.20 compliance tooling (in progress)

Planned:

Full CRUD for tenant, lot, and lease management
Lease ingestion and AI-assisted review (Claude API)
Tenant onboarding workflows
Payment tracking
Compliance alerts (WA rent control, just cause eviction, etc.)
Multi-park dashboard


Stack

Backend: Spring Boot, Spring Data JPA, Spring Security
Frontend: React + TypeScript
Database: PostgreSQL
AI: Anthropic Claude API (lease review, compliance flagging)


Data model philosophy
Lordship makes a hard distinction between canonical records and historical shards.
Canonical records are current, relational, and the operational source of truth. Historical shards are legacy data — imported from old spreadsheets, tagged with their source, and treated as immutable. They are not force-merged with canonical records. Ambiguous linkage is flagged, not assumed.
When companies adopt Lordship, they don't lose their historical data. They also don't corrupt their clean data trying to reconcile it.
