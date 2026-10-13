BEGIN;

ALTER TABLE public.users
    ADD COLUMN IF NOT EXISTS phone_number VARCHAR(20);

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_phone_number
    ON public.users (phone_number);

COMMIT;
