-- Append-only enforcement for the ledger.
--
-- A repository interface without a delete method is a convention the application can abandon in
-- one line; a trigger is enforced by the database for every caller, including psql, a migration
-- script or a future service. Any UPDATE or DELETE on ledger_entries raises an exception, which
-- aborts the surrounding statement and therefore the surrounding transaction.

CREATE FUNCTION ledger_entries_reject_mutation() RETURNS trigger
    LANGUAGE plpgsql AS
$$
BEGIN
    RAISE EXCEPTION 'ledger_entries is append-only: % is not allowed', TG_OP;
END;
$$;

CREATE TRIGGER ledger_entries_append_only
    BEFORE UPDATE OR DELETE
    ON ledger_entries
    FOR EACH ROW
EXECUTE FUNCTION ledger_entries_reject_mutation();
