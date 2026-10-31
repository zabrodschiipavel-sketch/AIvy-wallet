import json
import sqlite3
import subprocess
import sys
import unittest
from pathlib import Path
from tempfile import TemporaryDirectory
from uuid import UUID

SCRIPT = Path(__file__).with_name("audit_database.py")
TAG1 = str(UUID(int=1))
TX2 = str(UUID(int=2))
TX3 = str(UUID(int=3))

def make_db(path, orphan=False):
    con = sqlite3.connect(str(path))
    try:
        con.execute("PRAGMA user_version=131;")
        con.execute("CREATE TABLE tags(id TEXT PRIMARY KEY,isDeleted INTEGER);")
        con.execute("CREATE TABLE transactions(id TEXT PRIMARY KEY,isDeleted INTEGER,amount REAL);")
        con.execute("CREATE TABLE tags_association(tagId TEXT,associatedId TEXT,isDeleted INTEGER,PRIMARY KEY(tagId,associatedId));")
        con.execute("INSERT INTO tags(id,isDeleted) VALUES(?,0);", (TAG1,))
        con.execute("INSERT INTO transactions(id,isDeleted,amount) VALUES(?,0,10.0);", (TX2,))
        con.execute("INSERT INTO tags_association(tagId,associatedId,isDeleted) VALUES(?,?,0);", (TAG1, TX2))
        if orphan:
            con.execute("INSERT INTO tags_association(tagId,associatedId,isDeleted) VALUES(?,?,0);", (TAG1, TX3))
        con.commit()
    finally:
        con.close()

def run_cli(db, report):
    return subprocess.run([sys.executable, str(SCRIPT), "audit", str(db), "--report", str(report)], capture_output=True, text=True)

class AuditCliTests(unittest.TestCase):
    def test_missing_db_exits2_no_files(self):
        with TemporaryDirectory() as t:
            db = Path(t) / "missing.db"
            rep = Path(t) / "report.json"
            r = run_cli(db, rep)
            self.assertEqual(r.returncode, 2)
            self.assertFalse(rep.exists())
            self.assertFalse(db.exists())

    def test_clean_exits0_empty_findings(self):
        with TemporaryDirectory() as t:
            db = Path(t) / "clean.db"
            rep = Path(t) / "report.json"
            make_db(db)
            r = run_cli(db, rep)
            self.assertEqual(r.returncode, 0)
            data = json.loads(rep.read_text())
            self.assertIn("counts", data)
            self.assertEqual(data["issues"], [])
            self.assertEqual(data["candidates"], [])

    def test_orphan_exits1_candidate_missing_owner(self):
        with TemporaryDirectory() as t:
            db = Path(t) / "orphan.db"
            rep = Path(t) / "report.json"
            make_db(db, orphan=True)
            r = run_cli(db, rep)
            self.assertEqual(r.returncode, 1)
            data = json.loads(rep.read_text())
            self.assertEqual(data["candidates"], [{"tagId": TAG1, "associatedId": TX3}])

    def test_existing_report_preserved_exits2(self):
        with TemporaryDirectory() as t:
            db = Path(t) / "clean.db"
            rep = Path(t) / "report.json"
            make_db(db)
            rep.write_text("SENTINEL")
            r = run_cli(db, rep)
            self.assertEqual(r.returncode, 2)
            self.assertEqual(rep.read_text(), "SENTINEL")

if __name__ == "__main__":
    unittest.main()
