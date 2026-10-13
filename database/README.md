# Database

This folder stores PostgreSQL schema, migration metadata, seeds, and analysis queries for Smart Assessment AI.

New databases should be initialized with `smart_assessment_ai.sql`. Each user registers with one unique contact method: an email address or a phone number.

For an existing database, apply migrations in order: `V001__add_phone_number_to_users.sql`, then `V002__allow_email_or_phone_registration.sql`. These preserve existing accounts, make email optional, and enforce that each user has at least one contact method.

Phone numbers are stored in normalized form: an optional leading `+` followed by 8 to 15 digits. Spaces, parentheses, periods, and hyphens are removed before saving and lookup. Users sign in with whichever contact method they used to register.
