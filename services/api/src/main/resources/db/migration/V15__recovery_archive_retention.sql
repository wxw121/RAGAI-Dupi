ALTER TABLE recovery_restore_jobs
    DROP CONSTRAINT recovery_restore_jobs_archive_id_fkey;

ALTER TABLE recovery_restore_jobs
    ADD CONSTRAINT recovery_restore_jobs_archive_id_fkey
    FOREIGN KEY (archive_id) REFERENCES recovery_archives(id) ON DELETE CASCADE;
