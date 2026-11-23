-- Demonstration migration, run by SupplementBenchmarkRunner in its OWN tiny schema.
-- Caller owns transaction. This is not an online production migration.
-- Step 1: remove A's explicit coverage/locking triggers and their functions (7 DDL).
DROP TRIGGER b_lock ON contract;
DROP TRIGGER b_lock ON contract_price;
DROP TRIGGER price_coverage ON contract_price;
DROP TRIGGER parent_coverage ON contract;
DROP FUNCTION lock_contract();
DROP FUNCTION check_price_coverage();
DROP FUNCTION check_remaining_coverage();
-- Step 2: remove A's exclusion constraints and their backing indexes (2 DDL).
ALTER TABLE contract DROP CONSTRAINT contract_no_overlap;
ALTER TABLE contract_price DROP CONSTRAINT price_no_overlap;
-- Step 3: install B's temporal unique constraints (2 DDL).
ALTER TABLE contract ADD CONSTRAINT contract_no_overlap
UNIQUE (contract_id, valid_period WITHOUT OVERLAPS);
ALTER TABLE contract_price ADD CONSTRAINT price_no_overlap
UNIQUE (contract_id, valid_period WITHOUT OVERLAPS);
-- Step 4: install B's temporal foreign key (1 DDL).
ALTER TABLE contract_price ADD CONSTRAINT price_coverage
FOREIGN KEY (contract_id, PERIOD valid_period)
REFERENCES contract (contract_id, PERIOD valid_period)
ON UPDATE NO ACTION ON DELETE NO ACTION NOT DEFERRABLE;
