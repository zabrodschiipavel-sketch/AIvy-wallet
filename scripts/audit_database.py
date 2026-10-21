"""Offline tag-link audit and reviewed repair into a new SQLite copy (stdlib only)."""
import argparse
from contextlib import closing, contextmanager
import hashlib
import json
import os
from pathlib import Path
import shutil
import sqlite3
import sys
import tempfile

from database_audit import audit, fingerprint


@contextmanager
def read_snapshot(path):
    source = Path(path).resolve(strict=True)
    with closing(sqlite3.connect(source.as_uri() + '?mode=ro', uri=True, timeout=10)) as connection:
        connection.execute('PRAGMA query_only=ON')
        connection.execute('BEGIN')
        try:
            yield connection
        finally:
            connection.rollback()


def new_path(path):
    if any(os.path.lexists(str(path) + suffix) for suffix in ('', '-wal', '-shm', '-journal')):
        raise ValueError(f'Refusing to overwrite an existing path or SQLite sidecar: {path}')
    path = Path(path).resolve()
    if any(os.path.lexists(str(path) + suffix) for suffix in ('', '-wal', '-shm', '-journal')):
        raise ValueError(f'Refusing to overwrite {path}')
    if not path.parent.is_dir():
        raise ValueError(f'Parent directory does not exist: {path.parent}')
    return path


def create_snapshot(connection, destination):
    """Exclusive destination; SQLite backup includes uncheckpointed WAL records."""
    descriptor = os.open(destination, os.O_CREAT | os.O_EXCL | os.O_RDWR, 0o600)
    os.close(descriptor)
    try:
        with closing(sqlite3.connect(destination)) as backup:
            connection.backup(backup)
            backup.execute('PRAGMA journal_mode=DELETE')
    except BaseException:
        Path(destination).unlink(missing_ok=True)
        raise


def sha256(path):
    with Path(path).open('rb') as stream:
        return hashlib.file_digest(stream, 'sha256').hexdigest()


def repair_copy(database, review, backup, output):
    """Never writes to database; publishes a complete new file without overwriting."""
    source = Path(database).resolve(strict=True)
    backup, output = new_path(backup), new_path(output)
    if len({source, backup, output}) != 3:
        raise ValueError('Database, backup and output must be separate paths')
    temporary = None
    with read_snapshot(source) as connection:
        current = audit(connection)
        if review != current:
            raise ValueError('Reviewed report is stale or altered; run audit again')
        keys = [(item['tagId'], item['associatedId']) for item in current['candidates']]
        if not keys:
            raise ValueError('No eligible missing-owner tag links in the reviewed report')
        expected = fingerprint(connection, keys)
        create_snapshot(connection, backup)
        with read_snapshot(backup) as snapshot:
            if audit(snapshot) != current:
                raise ValueError('Pre-repair backup did not preserve the reviewed snapshot')
        try:
            descriptor, name = tempfile.mkstemp(prefix='.ivy-repair-', suffix='.db', dir=output.parent)
            os.close(descriptor)
            temporary = Path(name).resolve()
            temporary.relative_to(output.parent)
            shutil.copyfile(backup, temporary)
            with closing(sqlite3.connect(temporary)) as repaired, repaired:
                repaired.execute('PRAGMA foreign_keys=ON')
                repaired.execute('BEGIN IMMEDIATE')
                for tag_id, owner_id in keys:
                    changed = repaired.execute(
                        'DELETE FROM tags_association WHERE tagId=? AND associatedId=? AND isDeleted=0 '
                        'AND (NOT EXISTS(SELECT 1 FROM tags WHERE id=tags_association.tagId) '
                        'OR NOT EXISTS(SELECT 1 FROM transactions WHERE id=tags_association.associatedId))',
                        (tag_id, owner_id),
                    ).rowcount
                    if changed != 1:
                        raise ValueError('A reviewed link could not be removed exactly once')
                after = audit(repaired)
                if after['fingerprint'] != expected:
                    raise ValueError('Repair changed data beyond the reviewed tag links')
            # Same-volume hard link publishes atomically and fails if output already exists.
            os.link(temporary, output)
        finally:
            if temporary is not None:
                temporary.unlink(missing_ok=True)
    return {
        'format_version': 1, 'reviewed_fingerprint': current['fingerprint'],
        'backup': str(backup), 'backup_sha256': sha256(backup),
        'output': str(output), 'output_sha256': sha256(output),
        'deleted_links': current['candidates'], 'output_fingerprint': expected,
        'remaining_issues': after['issues'],
    }


def main(argv=None):
    parser = argparse.ArgumentParser(description=__doc__)
    commands = parser.add_subparsers(dest='command', required=True)
    inspect = commands.add_parser('audit', help='Read-only report; no names, notes or amounts')
    inspect.add_argument('database', type=Path)
    inspect.add_argument('--report', required=True, type=Path)
    repair = commands.add_parser('repair-copy', help='Repair only reviewed missing-owner links in a new copy')
    repair.add_argument('database', type=Path)
    repair.add_argument('--review', required=True, type=Path)
    repair.add_argument('--backup', required=True, type=Path)
    repair.add_argument('--output', required=True, type=Path)
    args = parser.parse_args(argv)
    try:
        if args.command == 'audit':
            report_path = new_path(args.report)
            with read_snapshot(args.database) as connection:
                report = audit(connection)
            with report_path.open('x', encoding='utf-8') as stream:
                json.dump(report, stream, indent=2)
                stream.write('\n')
            print(json.dumps({'report': str(report_path), 'issues': len(report['issues']),
                              'repair_candidates': len(report['candidates'])}))
            return 1 if report['issues'] else 0
        review = json.loads(args.review.read_text(encoding='utf-8-sig'))
        print(json.dumps(repair_copy(args.database, review, args.backup, args.output), indent=2))
        return 0
    except (OSError, sqlite3.Error, ValueError) as error:
        print(json.dumps({'error': str(error)}), file=sys.stderr)
        return 2


if __name__ == '__main__':
    sys.exit(main())
