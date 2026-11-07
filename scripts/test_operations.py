"""Run with: python -m unittest discover -s scripts -p test_operations.py"""
import contextlib
import io
import json
from pathlib import Path
import tempfile
import threading
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
import unittest
from unittest.mock import patch
from uuid import uuid4

import operations


class OperationsTest(unittest.TestCase):
    def bundle(self, base):
        folder = base / "backup"
        (folder / "evidence").mkdir(parents=True)
        (folder / "database.dump").write_bytes(b"disposable custom dump fixture")
        key = str(uuid4())
        (folder / "evidence" / key).write_bytes(b"photo fixture")
        manifest = {"format": "mfu-doctracking-backup-v1", "source_database": "source", "snapshot_started_at": operations.utc(), "files": operations.inventory(folder)}
        (folder / "manifest.json").write_text(json.dumps(manifest), encoding="utf-8")
        return folder, key

    def test_verification_rejects_corruption_and_unlisted_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder, key = self.bundle(Path(temporary))
            operations.verify(folder)
            path = folder / "evidence" / key
            path.write_bytes(b"altered")
            with self.assertRaisesRegex(ValueError, "mismatch"):
                operations.verify(folder)

    def test_restore_preview_does_not_call_database_or_storage(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder, _ = self.bundle(Path(temporary))
            args = operations.parser().parse_args(["restore", "--bundle", str(folder), "--target-database", "isolated_restore", "--evidence-root", str(Path(temporary) / "restored")])
            with patch.object(operations, "command", side_effect=AssertionError("Dry run executed command")), contextlib.redirect_stdout(io.StringIO()):
                operations.restore(args)

    def test_restore_rejects_source_database_and_unnamed_target(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder, _ = self.bundle(Path(temporary))
            args = operations.parser().parse_args(["restore", "--bundle", str(folder), "--target-database", "source", "--evidence-root", str(Path(temporary) / "restored")])
            with self.assertRaisesRegex(ValueError, "differ"):
                operations.restore(args)
            with self.assertRaises(ValueError):
                operations.database_name("postgresql://host/database?password=private")

    def test_backup_verifies_finalized_evidence_and_records_one_dump(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            photos = base / "photos"
            photos.mkdir()
            key = str(uuid4())
            photo = photos / key
            photo.write_bytes(b"finalized fixture")
            args = operations.parser().parse_args(["backup", "--database", "source", "--output", str(base / "bundle"), "--release-id", "fixture", "--evidence-root", str(photos), "--maintenance-confirmed"])
            calls = []
            def execute(command, output=None):
                calls.append(command)
                if command[0] == "pg_dump":
                    Path(command[-1]).write_bytes(b"custom dump fixture")
                    return ""
                if "pg_stat_activity" in command[-1]:
                    return "0\n"
                return f"{key},{operations.sha(photo)},{photo.stat().st_size}\n"
            with patch.object(operations, "command", side_effect=execute), contextlib.redirect_stdout(io.StringIO()):
                operations.backup(args)
            manifest = operations.verify(base / "bundle")
            self.assertEqual(1, manifest["finalized_evidence_count"])
            self.assertEqual(1, sum(call[0] == "pg_dump" for call in calls))

    def test_restore_refuses_nonempty_database_without_cleaning(self):
        with tempfile.TemporaryDirectory() as temporary:
            folder, _ = self.bundle(Path(temporary))
            args = operations.parser().parse_args(["restore", "--bundle", str(folder), "--target-database", "isolated_restore", "--confirm-target", "isolated_restore", "--execute", "--maintenance-confirmed", "--evidence-root", str(Path(temporary) / "restored")])
            with patch.object(operations, "sql", side_effect=["0", "3"]), patch.object(operations, "command") as execute:
                with self.assertRaisesRegex(ValueError, "empty"):
                    operations.restore(args)
                execute.assert_not_called()

    def test_restore_execution_copies_evidence_and_reports_no_invented_rpo_rto(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            folder, key = self.bundle(base)
            photo = folder / "evidence" / key
            args = operations.parser().parse_args(["restore", "--bundle", str(folder), "--target-database", "isolated_restore", "--confirm-target", "isolated_restore", "--execute", "--maintenance-confirmed", "--evidence-root", str(base / "restored")])
            def execute(command, output=None):
                if command[0] == "pg_restore":
                    self.assertNotIn("--clean", command)
                    return ""
                if "pg_stat_activity" in command[-1] or "pg_class" in command[-1]:
                    return "0\n"
                return f"{key},{operations.sha(photo)},{photo.stat().st_size}\n"
            stdout = io.StringIO()
            with patch.object(operations, "command", side_effect=execute), contextlib.redirect_stdout(stdout):
                operations.restore(args)
            self.assertEqual(photo.read_bytes(), (base / "restored" / key).read_bytes())
            report = json.loads(stdout.getvalue())
            self.assertIsNone(report["rpo_seconds"])
            self.assertIsNone(report["service_rto_seconds"])
            self.assertEqual("DATA_RESTORED_SERVICE_VALIDATION_REQUIRED", report["status"])

    def test_release_requires_digest_and_never_starts_deployment(self):
        with tempfile.TemporaryDirectory() as temporary:
            output = Path(temporary) / "release.yaml"
            args = operations.parser().parse_args(["release", "--backend-image", "registry/backend:latest", "--frontend-image", "registry/frontend:latest", "--output", str(output)])
            with self.assertRaisesRegex(ValueError, "digests"):
                operations.release(args)
            args.backend_image = "registry/backend@sha256:" + "a" * 64
            args.frontend_image = "registry/frontend@sha256:" + "b" * 64
            with patch.object(operations, "command", side_effect=AssertionError("Unexpected deployment")), contextlib.redirect_stdout(io.StringIO()):
                operations.release(args)
            self.assertIn(args.backend_image, output.read_text(encoding="utf-8"))

    def test_smoke_refuses_non_https_remote_origin(self):
        args = operations.parser().parse_args(["smoke", "--origin", "http://remote.example.invalid", "--allow-http-localhost"])
        with self.assertRaisesRegex(ValueError, "HTTPS"):
            operations.smoke(args)

    def test_anonymous_smoke_accepts_readiness_and_requires_auth_for_csrf_and_session(self):
        requests = []
        class Handler(BaseHTTPRequestHandler):
            def do_GET(self):
                requests.append(self.path)
                self.send_response(200 if self.path == "/healthz" else 401)
                self.send_header("Content-Type", "application/json")
                self.end_headers()
                self.wfile.write(b'{"status":"UP"}' if self.path == "/healthz" else b'{"code":"UNAUTHENTICATED"}')
            def log_message(self, *unused):
                pass
        server = ThreadingHTTPServer(("127.0.0.1", 0), Handler)
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            args = operations.parser().parse_args(["smoke", "--origin", f"http://127.0.0.1:{server.server_port}", "--allow-http-localhost"])
            with contextlib.redirect_stdout(io.StringIO()):
                operations.smoke(args)
            self.assertEqual(["/healthz", "/api/v1/auth/csrf", "/api/v1/auth/me"], requests)
        finally:
            server.shutdown()
            server.server_close()
            thread.join()


if __name__ == "__main__":
    unittest.main()
