-- Correct a fully covered interval, including intervals spanning several prices.
-- Surrounding fragments retain their prices. All changes are one atomic operation.
CREATE FUNCTION correct_price(
    target_contract bigint, portion daterange, replacement_price numeric
) RETURNS void LANGUAGE plpgsql AS $$
DECLARE
    previous contract_price%ROWTYPE;
    covered datemultirange;
    fragment daterange;
BEGIN
    IF portion IS NULL OR isempty(portion) OR replacement_price IS NULL
       OR replacement_price < 0 OR replacement_price = 'NaN'::numeric THEN
        RAISE EXCEPTION 'A nonempty period and nonnegative price are required'
            USING ERRCODE = '23514';
    END IF;

    PERFORM row_id FROM contract_price
    WHERE contract_id = target_contract AND valid_period && portion
    ORDER BY row_id FOR UPDATE;

    SELECT range_agg(valid_period) INTO covered FROM contract_price
    WHERE contract_id = target_contract AND valid_period && portion;
    IF covered IS NULL OR NOT (covered @> portion) THEN
        RAISE EXCEPTION 'Correction period must be fully covered by existing prices'
            USING ERRCODE = '23514';
    END IF;

    FOR previous IN
        SELECT * FROM contract_price
        WHERE contract_id = target_contract AND valid_period && portion
        ORDER BY row_id
    LOOP
        DELETE FROM contract_price WHERE row_id = previous.row_id;
        FOR fragment IN
            SELECT unnest(datemultirange(previous.valid_period) - datemultirange(portion))
        LOOP
            INSERT INTO contract_price (contract_id, price, valid_period)
            VALUES (target_contract, previous.price, fragment);
        END LOOP;
    END LOOP;
    INSERT INTO contract_price (contract_id, price, valid_period)
    VALUES (target_contract, replacement_price, portion);
END;
$$;
