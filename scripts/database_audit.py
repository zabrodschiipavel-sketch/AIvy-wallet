"""Schema-131 tag-link rules and a private, deterministic whole-database fingerprint."""
import hashlib
import json
import sqlite3
from uuid import UUID


def quote(name):
    return '"' + name.replace('"', '""') + '"'


def tables(connection):
    return [row[0] for row in connection.execute("SELECT name FROM main.sqlite_schema WHERE type='table' ORDER BY name")]


def columns(connection, table):
    return [row[1] for row in connection.execute('PRAGMA main.table_info(' + quote(table) + ')')]


def cell(value):
    if value is None:
        return ['null']
    if isinstance(value, bytes):
        return ['blob', value.hex()]
    if isinstance(value, float):
        return ['real', value.hex()]
    return ['integer' if isinstance(value, int) else 'text', value]


def fingerprint(connection, excluded_links=()):
    excluded = set(excluded_links)
    digest = hashlib.sha256()

    def add(value):
        digest.update((json.dumps(value, ensure_ascii=True, separators=(',', ':')) + '\n').encode('utf-8'))

    add([connection.execute('PRAGMA user_version').fetchone()[0],
         connection.execute('PRAGMA application_id').fetchone()[0]])
    for row in connection.execute('SELECT type,name,tbl_name,sql FROM main.sqlite_schema ORDER BY type,name'):
        add(list(row))
    for table in tables(connection):
        names = columns(connection, table)
        add([table, names])
        order = ','.join(str(index + 1) for index in range(len(names)))
        for row in connection.execute('SELECT * FROM main.' + quote(table) + ' ORDER BY ' + order):
            if table == 'tags_association' and excluded:
                key = row[names.index('tagId')], row[names.index('associatedId')]
                if key in excluded:
                    continue
            add([cell(value) for value in row])
    return digest.hexdigest()


def audit(connection):
    version = connection.execute('PRAGMA user_version').fetchone()[0]
    if version != 131:
        raise ValueError('Only Ivy schema 131 is supported; migrate a copy before auditing older data')
    integrity = [row[0] for row in connection.execute('PRAGMA integrity_check')]
    if integrity != ['ok']:
        raise ValueError('SQLite integrity check failed')
    required = {'tags': ['id', 'isDeleted'], 'transactions': ['id', 'isDeleted'],
                'tags_association': ['tagId', 'associatedId', 'isDeleted']}
    for table, names in required.items():
        if not set(names).issubset(columns(connection, table)):
            raise ValueError('Missing required columns in ' + table)
        primary = [row[1] for row in sorted(connection.execute('PRAGMA main.table_info(' + quote(table) + ')'),
                                           key=lambda row: row[5]) if row[5]]
        if primary != (['tagId', 'associatedId'] if table == 'tags_association' else ['id']):
            raise ValueError('Unexpected primary key in ' + table)

    def rows(table):
        names = required[table]
        for row in connection.execute('SELECT ' + ','.join(quote(name) for name in names) +
                                      ' FROM main.' + quote(table) + ' ORDER BY 1,2'):
            if row[-1] not in (0, 1) or not isinstance(row[-1], int):
                raise ValueError('Invalid deletion flag in ' + table)
            for identifier in row[:-1]:
                if not isinstance(identifier, str):
                    raise ValueError('Expected text UUID in ' + table)
                try:
                    UUID(identifier)
                except ValueError as error:
                    raise ValueError('Invalid UUID in ' + table) from error
            yield row

    tags = dict(rows('tags'))
    owners = dict(rows('transactions'))
    issues, candidates = [], []
    for tag_id, owner_id, deleted in rows('tags_association'):
        reasons = []
        for identifier, entities, name in ((tag_id, tags, 'tag'), (owner_id, owners, 'transaction')):
            if identifier not in entities:
                reasons.append('missing_' + name)
            elif entities[identifier]:
                reasons.append('deleted_' + name)
        if reasons:
            key = {'tagId': tag_id, 'associatedId': owner_id}
            issues.append({**key, 'reasons': reasons, 'association_deleted': bool(deleted)})
            if not deleted and any(reason.startswith('missing_') for reason in reasons):
                candidates.append(key)
    counts = {table: connection.execute('SELECT COUNT(*) FROM main.' + quote(table)).fetchone()[0]
              for table in tables(connection)}
    return {'format_version': 1, 'schema_version': version, 'integrity': integrity,
            'fingerprint': fingerprint(connection), 'counts': counts, 'issues': issues, 'candidates': candidates}
