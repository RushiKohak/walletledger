-- Idempotent replay needs the original answer.
--
-- A replay must return exactly what the first request returned, which means the response has to
-- be stored with the transaction that produced it. JSONB rather than a text column so the body is
-- stored as real JSON and can be inspected/compared in SQL.
--
-- The default is only there to fill rows written before this column existed (there are none in a
-- freshly migrated database); it is dropped immediately afterwards so every new transaction row
-- must supply its own body at INSERT time.

ALTER TABLE ledger_transactions
    ADD COLUMN response_body JSONB NOT NULL DEFAULT '{}'::jsonb;

ALTER TABLE ledger_transactions
    ALTER COLUMN response_body DROP DEFAULT;
