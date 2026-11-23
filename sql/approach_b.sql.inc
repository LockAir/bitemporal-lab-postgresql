ALTER TABLE contract ADD CONSTRAINT contract_no_overlap
UNIQUE (contract_id, valid_period WITHOUT OVERLAPS);
ALTER TABLE contract_price ADD CONSTRAINT price_no_overlap
UNIQUE (contract_id, valid_period WITHOUT OVERLAPS);
ALTER TABLE contract_price ADD CONSTRAINT price_coverage
FOREIGN KEY (contract_id, PERIOD valid_period)
REFERENCES contract (contract_id, PERIOD valid_period)
ON UPDATE NO ACTION ON DELETE NO ACTION NOT DEFERRABLE;
