# Lordship
A barebones open source project for managing manufactured housing communities.


Lordship
Open source property management software built for the real world — specifically, the part of the real world still running mobile home parks out of Excel spreadsheets OR trying to ditch expensive software.
Lordship is a full-stack platform designed to replace the duct tape: the VBA macros, the shared drives, the rent rolls that haven't been touched since 2011. It is trying to be the thing small and mid-size operators can actually use.

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

Full CRUD for tenant, lot, and lease management with interactive map support
Lease ingestion and AI-assisted review (Claude API)
Tenant onboarding workflows
Payment tracking
Compliance alerts (WA rent control, just cause eviction, etc.)
Multi-park dashboard


Stack

Backend: Spring Boot, Spring Data JPA, Spring Security
Frontend: React + TypeScript
Database: PostgreSQL
AI: Qwen (lease review, compliance flagging)


Own recent work (for developers and agents)
Deleting needs a delete permission, with one rule on top: whoever added a record may delete and restore that record for 7 days without the delete permission, while nobody else has changed the record, or added or changed anything under the record (a home on the lot, a meter on the property, a lot of the property and whatever is on that lot). This is what lets an office worker who can add a lot undo adding the lot. The rule lives in one class, audit/OwnWork.java, and reads who added a record from audit_log. No table has a created_by column for this.
- Covered today: property, lot, mobile_home, person. Soft delete only. Every other refusal (a lot with an active tenancy, a person on a tenancy) still applies.
- A new kind of record: add one entry to OwnWork.KINDS, and put hasAuthority('x:delete') or @ownWork.madeRecently('table', #uuid, authentication) on the kind's delete and restore endpoints (madeAllRecently for a list of ids). The list of deleted records uses ownWork.restorable and the Glade screen answer uses ownWork.mine. The class comment has the details.
- A new table that points at a covered record (something new a coworker can hang on a lot or a property): add one Under to that kind's entry in OwnWork.KINDS. Without the Under, the maker could take the record back from under the coworker's work.
- The days are lordship.own-work.days in application.properties (0 switches the rule off). Refusals are own_work.* in messages.properties and messages_es.properties. The tests are in audit/OwnWorkIT.java; audit/OwnWorkAnnotationsTest.java fails when an endpoint's check names a table OwnWork.KINDS does not hold.

Data model philosophy
Lordship will make a hard distinction between canonical records and historical shards.
Canonical records are current, relational, and the operational source of truth. Historical shards are legacy data — imported from old spreadsheets, tagged with their source, and treated as immutable. They are not force-merged with canonical records. Ambiguous linkage is flagged, not assumed.

