"""Data preservation and negative controls for offline tag-link repair."""
from contextlib import closing
from copy import deepcopy
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
from uuid import UUID

from audit_database import read_snapshot, repair_copy, sha256
from database_audit import audit, fingerprint


def uid(number):
    return str(UUID(int=number))


class DatabaseAuditTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='ivy audit ')
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.source = self.root / 'данные #1.db'
        self.backup = self.root / 'before.db'
        self.output = self.root / 'repaired.db'
        with closing(sqlite3.connect(self.source)) as connection, connection:
            connection.executescript('''
                PRAGMA user_version=131;
                CREATE TABLE tags(id TEXT PRIMARY KEY, isDeleted INTEGER, name TEXT);
                CREATE TABLE transactions(id TEXT PRIMARY KEY, isDeleted INTEGER, amount REAL, note TEXT);
                CREATE TABLE tags_association(tagId TEXT, associatedId TEXT, isDeleted INTEGER, lastSyncedTime INTEGER,
                    PRIMARY KEY(tagId,associatedId));
                CREATE TABLE "extra ""state"(id INTEGER PRIMARY KEY, payload BLOB);
                CREATE VIEW notes AS SELECT note FROM transactions;
            ''')
            connection.executemany('INSERT INTO tags VALUES(?,?,?)',
                                   [(uid(1), 0, 'private tag'), (uid(2), 1, 'deleted tag')])
            connection.executemany('INSERT INTO transactions VALUES(?,?,?,?)',
                                   [(uid(3), 0, 0.000000013, 'private financial note'),
                                    (uid(4), 1, 1e20, 'deleted financial note')])
            connection.executemany('INSERT INTO tags_association VALUES(?,?,?,?)', [
                (uid(1), uid(3), 0, 123),  # Valid.
                (uid(1), uid(5), 0, 124),  # Missing transaction.
                (uid(6), uid(3), 0, 125),  # Missing tag.
                (uid(2), uid(3), 0, 126),  # Tombstoned tag: report only.
                (uid(1), uid(4), 0, 127),  # Tombstoned transaction: report only.
                (uid(7), uid(8), 1, 128),  # Tombstoned link: preserve.
            ])
            connection.execute('INSERT INTO "extra ""state" VALUES(1,?)', (b'\x00\xff\x10',))

    def report(self, source=None):
        with read_snapshot(source or self.source) as connection:
            return audit(connection)

    def change(self, sql, parameters=()):
        with closing(sqlite3.connect(self.source)) as connection, connection:
            connection.execute(sql, parameters)

    def repair(self, report=None):
        return repair_copy(self.source, report or self.report(), self.backup, self.output)

    def test_only_active_physically_missing_owner_links_are_candidates(self):
        result = self.report()
        self.assertEqual(result['candidates'], [
            {'tagId': uid(1), 'associatedId': uid(5)}, {'tagId': uid(6), 'associatedId': uid(3)},
        ])
        self.assertEqual(len(result['issues']), 5)
        self.assertNotIn('private financial note', str(result))
        self.assertNotIn('private tag', str(result))

    def test_audit_is_read_only_and_preserves_source_bytes(self):
        before = sha256(self.source)
        with read_snapshot(self.source) as connection:
            audit(connection)
            with self.assertRaises(sqlite3.OperationalError):
                connection.execute('DELETE FROM transactions')
        self.assertEqual(sha256(self.source), before)

    def test_repair_preserves_all_other_rows_schema_tombstones_and_backup(self):
        original = self.report()
        before = sha256(self.source)
        with read_snapshot(self.source) as connection:
            expected = fingerprint(connection, [(uid(1), uid(5)), (uid(6), uid(3))])
        receipt = self.repair(original)
        self.assertEqual(self.report(), original)
        self.assertEqual(self.report(self.backup), original)
        self.assertEqual(self.report(self.output)['fingerprint'], expected)
        self.assertEqual(self.report(self.output)['counts']['tags_association'], 4)
        self.assertEqual(len(self.report(self.output)['issues']), 3)
        self.assertEqual(receipt['output_fingerprint'], expected)
        self.assertEqual(sha256(self.source), before)

    def test_wal_snapshot_and_repair_include_uncheckpointed_financial_rows(self):
        with closing(sqlite3.connect(self.source)) as writer:
            writer.execute('PRAGMA journal_mode=WAL')
            writer.execute('PRAGMA wal_autocheckpoint=0')
            writer.execute('INSERT INTO transactions VALUES(?,?,?,?)', (uid(9), 0, 777.0, 'WAL only'))
            writer.commit()
            self.assertTrue(Path(str(self.source) + '-wal').stat().st_size)
            reviewed = self.report()
            self.repair(reviewed)
            self.assertEqual(self.report(self.backup), reviewed)
            self.assertEqual(self.report(self.output)['counts']['transactions'], 3)

    def test_unrelated_concurrent_change_rejects_stale_review_before_backup(self):
        reviewed = self.report()
        self.change('UPDATE transactions SET amount=42 WHERE id=?', (uid(3),))
        current = self.report()
        with self.assertRaisesRegex(ValueError, 'stale'):
            self.repair(reviewed)
        self.assertEqual(self.report(), current)
        self.assertFalse(self.backup.exists())
        self.assertFalse(self.output.exists())

    def test_altered_review_cannot_delete_a_valid_link(self):
        reviewed = deepcopy(self.report())
        reviewed['candidates'].append({'tagId': uid(1), 'associatedId': uid(3)})
        with self.assertRaisesRegex(ValueError, 'altered'):
            self.repair(reviewed)
        self.assertFalse(self.backup.exists())

    def test_second_delete_failure_never_publishes_partial_copy(self):
        self.change("CREATE TRIGGER fail_second BEFORE DELETE ON tags_association WHEN OLD.tagId='" + uid(6) +
                    "' BEGIN SELECT RAISE(ABORT,'injected second deletion'); END")
        original = self.report()
        with self.assertRaisesRegex(sqlite3.IntegrityError, 'injected second'):
            self.repair(original)
        self.assertEqual(self.report(self.backup), original)
        self.assertEqual(self.report(), original)
        self.assertFalse(self.output.exists())
        self.assertFalse(list(self.root.glob('.ivy-repair-*')))

    def test_collateral_trigger_change_is_rejected_and_backup_preserved(self):
        self.change('CREATE TRIGGER collateral AFTER DELETE ON tags_association '
                    'BEGIN UPDATE transactions SET amount=999; END')
        original = self.report()
        with self.assertRaisesRegex(ValueError, 'beyond'):
            self.repair(original)
        self.assertEqual(self.report(self.backup), original)
        self.assertEqual(self.report(), original)
        self.assertFalse(self.output.exists())

    def test_existing_output_or_backup_is_never_overwritten(self):
        for path in (self.output, self.backup):
            with self.subTest(path=path):
                path.write_bytes(b'sentinel')
                with self.assertRaisesRegex(ValueError, 'overwrite'):
                    self.repair()
                self.assertEqual(path.read_bytes(), b'sentinel')
                path.unlink()

    def test_existing_sidecar_is_not_adopted_by_a_new_backup(self):
        sidecar = Path(str(self.backup) + '-wal')
        sidecar.write_bytes(b'unrelated WAL')
        with self.assertRaisesRegex(ValueError, 'sidecar'):
            self.repair()
        self.assertEqual(sidecar.read_bytes(), b'unrelated WAL')
        self.assertFalse(self.backup.exists())

    def test_publish_collision_does_not_overwrite_another_file(self):
        import os
        link = os.link

        def collide(source, target):
            target.write_bytes(b'new concurrent output')
            link(source, target)

        with patch('audit_database.os.link', side_effect=collide), self.assertRaises(FileExistsError):
            self.repair()
        self.assertEqual(self.output.read_bytes(), b'new concurrent output')
        self.assertEqual(self.report(self.backup), self.report())

    def test_backup_failure_prevents_repair_and_publication(self):
        with patch('audit_database.create_snapshot', side_effect=OSError('disk failure')):
            with self.assertRaisesRegex(OSError, 'disk failure'):
                self.repair()
        self.assertFalse(self.output.exists())

    def test_unknown_schema_or_missing_columns_fail_closed(self):
        self.change('PRAGMA user_version=132')
        with self.assertRaisesRegex(ValueError, 'schema 131'):
            self.report()
        self.change('PRAGMA user_version=131')
        self.change('ALTER TABLE tags RENAME TO unexpected_tags')
        with self.assertRaisesRegex(ValueError, 'required columns'):
            self.report()

    def test_invalid_uuid_or_deleted_flag_is_not_silently_discarded(self):
        self.change('UPDATE tags SET isDeleted=2 WHERE id=?', (uid(1),))
        with self.assertRaisesRegex(ValueError, 'deletion flag'):
            self.report()
        self.change('UPDATE tags SET isDeleted=0,id=? WHERE id=?', ('invalid', uid(1)))
        with self.assertRaisesRegex(ValueError, 'Invalid UUID'):
            self.report()

    def test_only_tombstone_warnings_cannot_trigger_cleanup(self):
        self.change('DELETE FROM tags_association WHERE tagId=? OR associatedId=?', (uid(6), uid(5)))
        with self.assertRaisesRegex(ValueError, 'No eligible'):
            self.repair()
        self.assertFalse(self.backup.exists())

    def test_source_cannot_be_used_as_repair_output(self):
        before = sha256(self.source)
        with self.assertRaises(ValueError):
            repair_copy(self.source, self.report(), self.backup, self.source)
        self.assertEqual(sha256(self.source), before)


if __name__ == '__main__':
    unittest.main()
