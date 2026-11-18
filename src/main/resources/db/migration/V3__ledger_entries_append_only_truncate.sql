-- TRUNCATE is the one mutation the row trigger cannot see.
--
-- A BEFORE UPDATE OR DELETE ... FOR EACH ROW trigger never fires for TRUNCATE, because TRUNCATE
-- does not visit rows; it swaps the table's storage. So the same rejection function gets a second
-- trigger: a statement-level BEFORE TRUNCATE, which fires once for the whole statement.

CREATE TRIGGER ledger_entries_append_only_truncate
    BEFORE TRUNCATE
    ON ledger_entries
    FOR EACH STATEMENT
EXECUTE FUNCTION ledger_entries_reject_mutation();
