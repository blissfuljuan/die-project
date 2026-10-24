-- Temporary credentials are no longer burned permanently by wrong guesses (anyone who knows a student number could
-- otherwise force an administrator reset). Too many wrong guesses now lock the credential for a while instead.
ALTER TABLE temp_credential ADD COLUMN locked_until TIMESTAMPTZ;
