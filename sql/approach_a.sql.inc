ALTER TABLE contract ADD CONSTRAINT contract_no_overlap
EXCLUDE USING gist (contract_id WITH =, valid_period WITH &&);
ALTER TABLE contract_price ADD CONSTRAINT price_no_overlap
EXCLUDE USING gist (contract_id WITH =, valid_period WITH &&);

-- Every writer of either table participates, including direct SQL and COPY.
-- The lock lasts to transaction end and is scoped to one contract in this DB.
CREATE FUNCTION lock_contract() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        PERFORM pg_advisory_xact_lock(OLD.contract_id);
        RETURN OLD;
    END IF;
    IF TG_OP = 'UPDATE' AND NEW.contract_id <> OLD.contract_id THEN
        RAISE EXCEPTION 'contract_id is immutable' USING ERRCODE = '23514';
    END IF;
    PERFORM pg_advisory_xact_lock(NEW.contract_id);
    RETURN NEW;
END;
$$;
CREATE TRIGGER b_lock BEFORE INSERT OR UPDATE OR DELETE ON contract
FOR EACH ROW EXECUTE FUNCTION lock_contract();
CREATE TRIGGER b_lock BEFORE INSERT OR UPDATE OR DELETE ON contract_price
FOR EACH ROW EXECUTE FUNCTION lock_contract();

CREATE FUNCTION check_price_coverage() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    covered datemultirange;
BEGIN
    SELECT range_agg(valid_period) INTO covered
    FROM contract WHERE contract_id = NEW.contract_id;
    IF covered IS NULL OR NOT (covered @> NEW.valid_period) THEN
        RAISE EXCEPTION 'Price period is not covered by contract %', NEW.contract_id
            USING ERRCODE = '23503';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER price_coverage AFTER INSERT OR UPDATE ON contract_price
FOR EACH ROW EXECUTE FUNCTION check_price_coverage();

-- AFTER ROW sees the complete statement's changes. VOLATILE queries at
-- READ COMMITTED obtain a fresh snapshot after waiting for the advisory lock.
CREATE FUNCTION check_remaining_coverage() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    covered datemultirange;
BEGIN
    SELECT range_agg(valid_period) INTO covered
    FROM contract WHERE contract_id = OLD.contract_id;
    IF EXISTS (
        SELECT FROM contract_price
        WHERE contract_id = OLD.contract_id
          AND (covered IS NULL OR NOT (covered @> valid_period))
    ) THEN
        RAISE EXCEPTION 'Parent change leaves a price uncovered for contract %', OLD.contract_id
            USING ERRCODE = '23503';
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER parent_coverage AFTER UPDATE OR DELETE ON contract
FOR EACH ROW EXECUTE FUNCTION check_remaining_coverage();
