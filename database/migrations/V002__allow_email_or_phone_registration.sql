BEGIN;

ALTER TABLE public.users
    ALTER COLUMN email DROP NOT NULL;

DO $$
BEGIN
    IF NOT EXISTS (
        SELECT 1
        FROM pg_constraint
        WHERE conname = 'chk_users_email_or_phone'
          AND conrelid = 'public.users'::regclass
    ) THEN
        ALTER TABLE public.users
            ADD CONSTRAINT chk_users_email_or_phone
            CHECK (
                (email IS NOT NULL AND BTRIM(email) <> '')
                OR (phone_number IS NOT NULL AND BTRIM(phone_number) <> '')
            );
    END IF;
END
$$;

COMMIT;
