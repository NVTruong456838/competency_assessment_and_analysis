# Database

This folder stores PostgreSQL schema, migration metadata, seeds, and analysis queries for Smart Assessment AI.

New databases should be initialized with `smart_assessment_ai.sql`. Each user registers with one unique contact method: an email address or a phone number.

For an existing database, apply migrations in numeric order. `V001` and `V002`
preserve existing accounts, make email optional, and enforce that each user has
at least one contact method. `V003` through `V005` add taxonomy release provenance,
Question Bank review/import, Question Pattern foundations, and Question Version/asset
history protections. `V006` adds versioned assessment blueprints, test-build
provenance, published Test Version immutability, and server-owned attempt deadlines.
`V007` constrains HSA optional-branch selection, and `V008` adds versioned
Question Groups with ordered membership, shared stimulus, review evidence, and
published snapshots on Test Items. These migrations have only been verified in isolated PostgreSQL testing and are
not approved for the running
`smart_assessment_ai` database until the outstanding rollout and versioning
decisions are resolved.

See [migrations/README.md](migrations/README.md) for isolated test and rollback
instructions. Do not re-run the baseline schema against an existing database.

Phone numbers are stored in normalized form: an optional leading `+` followed by 8 to 15 digits. Spaces, parentheses, periods, and hyphens are removed before saving and lookup. Users sign in with whichever contact method they used to register.
