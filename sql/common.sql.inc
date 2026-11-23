-- Schema installation is transactional. Existing tables cause a safe failure.
CREATE EXTENSION IF NOT EXISTS btree_gist;

-- Transaction-start timestamps describe logical versions, not commit visibility.
-- The supported write isolation level is READ COMMITTED in both approaches.
CREATE FUNCTION require_read_committed() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    IF current_setting('transaction_isolation') <> 'read committed' THEN
        RAISE EXCEPTION 'Writes require READ COMMITTED for this experiment'
            USING ERRCODE = '0A000';
    END IF;
    RETURN NULL;
END;
$$;

CREATE TABLE contract (
    row_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_id bigint NOT NULL CHECK (contract_id > 0),
    valid_period daterange NOT NULL,
    system_period tstzrange NOT NULL,
    version_txid xid8 NOT NULL,
    CHECK (NOT isempty(valid_period) AND NOT lower_inf(valid_period)
           AND isfinite(lower(valid_period))
           AND (upper_inf(valid_period) OR isfinite(upper(valid_period)))),
    CHECK (NOT isempty(system_period) AND NOT lower_inf(system_period)
           AND upper_inf(system_period) AND lower_inc(system_period))
);

CREATE TABLE contract_history (
    history_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    row_id bigint NOT NULL,
    contract_id bigint NOT NULL,
    valid_period daterange NOT NULL CHECK (NOT isempty(valid_period)),
    system_period tstzrange NOT NULL,
    version_txid xid8 NOT NULL,
    CHECK (NOT isempty(system_period) AND NOT lower_inf(system_period)
           AND NOT upper_inf(system_period)
           AND lower_inc(system_period) AND NOT upper_inc(system_period))
);

CREATE INDEX contract_history_lookup
ON contract_history USING gist (contract_id, valid_period, system_period);

CREATE TRIGGER a_require_isolation
BEFORE INSERT OR UPDATE OR DELETE ON contract
FOR EACH STATEMENT EXECUTE FUNCTION require_read_committed();

CREATE TABLE contract_price (
    row_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    contract_id bigint NOT NULL CHECK (contract_id > 0),
    price numeric(12,2) NOT NULL CHECK (price >= 0 AND price <> 'NaN'::numeric),
    valid_period daterange NOT NULL,
    system_period tstzrange NOT NULL,
    version_txid xid8 NOT NULL,
    CHECK (NOT isempty(valid_period) AND NOT lower_inf(valid_period)
           AND isfinite(lower(valid_period))
           AND (upper_inf(valid_period) OR isfinite(upper(valid_period)))),
    CHECK (NOT isempty(system_period) AND NOT lower_inf(system_period)
           AND upper_inf(system_period) AND lower_inc(system_period))
);

CREATE TABLE contract_price_history (
    history_id bigint GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    row_id bigint NOT NULL,
    contract_id bigint NOT NULL,
    price numeric(12,2) NOT NULL CHECK (price >= 0 AND price <> 'NaN'::numeric),
    valid_period daterange NOT NULL CHECK (NOT isempty(valid_period)),
    system_period tstzrange NOT NULL,
    version_txid xid8 NOT NULL,
    CHECK (NOT isempty(system_period) AND NOT lower_inf(system_period)
           AND NOT upper_inf(system_period)
           AND lower_inc(system_period) AND NOT upper_inc(system_period))
);

CREATE INDEX contract_price_history_lookup
ON contract_price_history USING gist (contract_id, valid_period, system_period);

CREATE TRIGGER a_require_isolation
BEFORE INSERT OR UPDATE OR DELETE ON contract_price
FOR EACH STATEMENT EXECUTE FUNCTION require_read_committed();

-- version_txid distinguishes repeated changes in one transaction, even when
-- two different transactions happen to have equal timestamps.
CREATE FUNCTION preserve_history() RETURNS trigger
LANGUAGE plpgsql AS $$
DECLARE
    boundary timestamptz := transaction_timestamp();
    writer xid8 := pg_current_xact_id();
    previous record;
BEGIN
    IF TG_OP = 'INSERT' THEN
        NEW.system_period := tstzrange(boundary, NULL, '[)');
        NEW.version_txid := writer;
        RETURN NEW;
    END IF;

    IF TG_OP = 'UPDATE' THEN
        IF NEW.row_id <> OLD.row_id OR NEW.contract_id <> OLD.contract_id THEN
            RAISE EXCEPTION 'row_id and contract_id are immutable'
                USING ERRCODE = '23514';
        END IF;
    END IF;

    IF OLD.version_txid <> writer THEN
        IF boundary <= lower(OLD.system_period) THEN
            RAISE EXCEPTION 'Transaction predates the current version; retry the whole transaction'
                USING ERRCODE = '40001';
        END IF;
        previous := OLD;
        previous.system_period := tstzrange(lower(OLD.system_period), boundary, '[)');
        IF TG_TABLE_NAME = 'contract' THEN
            INSERT INTO contract_history
                (row_id, contract_id, valid_period, system_period, version_txid)
            VALUES (previous.row_id, previous.contract_id, previous.valid_period,
                    previous.system_period, previous.version_txid);
        ELSE
            INSERT INTO contract_price_history
                (row_id, contract_id, price, valid_period, system_period, version_txid)
            VALUES (previous.row_id, previous.contract_id, previous.price,
                    previous.valid_period, previous.system_period, previous.version_txid);
        END IF;
    END IF;

    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    NEW.system_period := tstzrange(boundary, NULL, '[)');
    NEW.version_txid := writer;
    RETURN NEW;
END;
$$;

CREATE TRIGGER c_history BEFORE INSERT OR UPDATE OR DELETE ON contract
FOR EACH ROW EXECUTE FUNCTION preserve_history();
CREATE TRIGGER c_history BEFORE INSERT OR UPDATE OR DELETE ON contract_price
FOR EACH ROW EXECUTE FUNCTION preserve_history();

CREATE VIEW contract_versions AS
SELECT row_id, contract_id, valid_period, system_period FROM contract
UNION ALL
SELECT row_id, contract_id, valid_period, system_period FROM contract_history;

CREATE VIEW contract_price_versions AS
SELECT row_id, contract_id, price, valid_period, system_period FROM contract_price
UNION ALL
SELECT row_id, contract_id, price, valid_period, system_period FROM contract_price_history;

-- TRUNCATE does not run row-level history or coverage triggers.
CREATE FUNCTION reject_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION 'TRUNCATE bypasses history; use DELETE in the experiment'
        USING ERRCODE = '0A000';
END;
$$;
CREATE TRIGGER reject_truncate BEFORE TRUNCATE ON contract
FOR EACH STATEMENT EXECUTE FUNCTION reject_truncate();
CREATE TRIGGER reject_truncate BEFORE TRUNCATE ON contract_price
FOR EACH STATEMENT EXECUTE FUNCTION reject_truncate();

-- Validate temporal ordering after constraint waits. A transaction that started
-- before intervening commits must not insert a version into occupied history.
CREATE FUNCTION check_version_order() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    boundary timestamptz := transaction_timestamp();
    conflict_found boolean;
    covered datemultirange;
BEGIN
    IF TG_OP <> 'DELETE' THEN
        EXECUTE format(
            'SELECT EXISTS (SELECT FROM %I WHERE contract_id = $1 AND valid_period && $2 AND system_period && tstzrange($3, NULL, ''[)''))',
            TG_TABLE_NAME || '_history'
        ) INTO conflict_found USING NEW.contract_id, NEW.valid_period, boundary;
        IF conflict_found THEN
            RAISE EXCEPTION 'New version would overlap a historical version; retry transaction'
                USING ERRCODE = '40001';
        END IF;
        IF TG_TABLE_NAME = 'contract_price' THEN
            SELECT range_agg(valid_period) INTO covered FROM contract
            WHERE contract_id = NEW.contract_id AND lower(system_period) <= boundary;
            IF covered IS NULL OR NOT (covered @> NEW.valid_period) THEN
                RAISE EXCEPTION 'Parent version is newer than transaction; retry transaction'
                    USING ERRCODE = '40001';
            END IF;
        END IF;
    END IF;
    IF TG_TABLE_NAME = 'contract' AND TG_OP <> 'INSERT' THEN
        SELECT range_agg(valid_period) INTO covered FROM contract
        WHERE contract_id = OLD.contract_id AND lower(system_period) <= boundary;
        IF EXISTS (
            SELECT FROM contract_price_history
            WHERE contract_id = OLD.contract_id AND upper(system_period) > boundary
              AND (covered IS NULL OR NOT (covered @> valid_period))
        ) THEN
            RAISE EXCEPTION 'Parent change would invalidate intervening child history; retry transaction'
                USING ERRCODE = '40001';
        END IF;
    END IF;
    RETURN NULL;
END;
$$;
CREATE TRIGGER z_version_order AFTER INSERT OR UPDATE OR DELETE ON contract
FOR EACH ROW EXECUTE FUNCTION check_version_order();
CREATE TRIGGER z_version_order AFTER INSERT OR UPDATE ON contract_price
FOR EACH ROW EXECUTE FUNCTION check_version_order();
