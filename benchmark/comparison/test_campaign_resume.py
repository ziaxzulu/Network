"""Protect captured benchmark evidence when resuming an interrupted runner."""
import hashlib
import json
from pathlib import Path
import tempfile
import unittest

from campaign import preserve_unrecorded_attempt, validate_completed


class ResumeEvidenceTest(unittest.TestCase):
    def setUp(self):
        self.temporary = tempfile.TemporaryDirectory()
        self.addCleanup(self.temporary.cleanup)
        self.root = Path(self.temporary.name)
        self.row = dict(case="curve", transport="raknet", iteration=1,
                        command=["run-row.sh", "--transport", "raknet"], out=str(self.root / "row"))
        self.folder = Path(self.row["out"])
        self.folder.mkdir()
        self.result = self.folder / "result.json"
        self.result.write_text('{"status":"measured"}\n')
        self.entry = dict(self.row, exitCode=0,
                          resultSha256=hashlib.sha256(self.result.read_bytes()).hexdigest())

    def write_ledger(self, entries):
        (self.root / "completed.jsonl").write_text("".join(json.dumps(e) + "\n" for e in entries))

    def test_recorded_failure_is_retained_as_completed(self):
        self.entry["exitCode"] = 1
        self.write_ledger([self.entry])
        self.assertEqual(validate_completed(self.root, [self.row]), [self.entry])

    def test_tampered_result_prevents_resume(self):
        self.write_ledger([self.entry])
        self.result.write_text('{"status":"changed"}\n')
        with self.assertRaisesRegex(ValueError, "Completed result changed"):
            validate_completed(self.root, [self.row])

    def test_missing_or_reordered_prefix_prevents_resume(self):
        self.write_ledger([dict(self.entry, iteration=2)])
        with self.assertRaisesRegex(ValueError, "unchanged prefix"):
            validate_completed(self.root, [self.row])

    def test_completed_rows_cannot_gain_an_unrecorded_result(self):
        self.entry.pop("resultSha256")
        self.write_ledger([self.entry])
        with self.assertRaisesRegex(ValueError, "Unrecorded result appeared"):
            validate_completed(self.root, [self.row])

    def test_unrecorded_attempt_is_preserved_for_a_fresh_run(self):
        original = self.result.read_bytes()
        (self.root / "row.log").write_text("runner was interrupted\n")
        preserve_unrecorded_attempt(self.root, self.row)
        self.assertFalse(self.folder.exists())
        archives = list((self.root / "interrupted-attempts").iterdir())
        self.assertEqual(len(archives), 1)
        self.assertEqual((archives[0] / "row/result.json").read_bytes(), original)
        self.assertTrue((archives[0] / "row.log").exists())
        self.assertIn("unknown", json.loads((archives[0] / "interruption.json").read_text())["reason"])


if __name__ == "__main__":
    unittest.main()
