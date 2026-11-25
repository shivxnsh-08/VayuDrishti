-- Completeness accounting per ingestion run.
-- expected:           the API's reported "total" for the snapshot
-- payload_duplicates: identical records received more than once within one run (pagination overlap)
-- duplicates (existing column) now means only: already stored by an earlier run (same snapshot re-read)
ALTER TABLE ingestion_runs ADD COLUMN expected INT;
ALTER TABLE ingestion_runs ADD COLUMN payload_duplicates INT;
