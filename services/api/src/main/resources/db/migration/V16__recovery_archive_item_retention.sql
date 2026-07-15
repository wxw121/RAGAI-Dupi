ALTER TABLE recovery_restore_items
    DROP CONSTRAINT recovery_restore_items_archive_item_id_fkey;

ALTER TABLE recovery_restore_items
    ADD CONSTRAINT recovery_restore_items_archive_item_id_fkey
    FOREIGN KEY (archive_item_id) REFERENCES recovery_archive_items(id) ON DELETE CASCADE;
