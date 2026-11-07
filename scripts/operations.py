#!/usr/bin/env python3
"""Offline-verifiable release/backup tooling. PostgreSQL tools/AWS CLI are operator prerequisites."""
import argparse
import csv
import hashlib
import io
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from uuid import UUID


def utc():
    return datetime.now(timezone.utc).isoformat()


def command(args, output=None):
    # Credentials must come from PGPASSFILE/AWS credential providers; never echo command output/errors.
    result = subprocess.run(args, stdout=output or subprocess.PIPE, stderr=subprocess.PIPE, check=False)
    if result.returncode:
        raise RuntimeError(f"{Path(args[0]).name} failed (exit {result.returncode}); inspect service/DB logs using private operator tooling")
    return "" if output else result.stdout.decode("utf-8")


def sql(database, query):
    return command(["psql", "-X", "--no-password", "--dbname", database, "--csv", "--tuples-only", "--command", query])


def database_name(value):
    if not re.fullmatch(r"[a-z][a-z0-9_]{0,62}", value):
        raise ValueError("Use a simple explicit PostgreSQL database name; connection URLs are not accepted")
    return value


def sha(path):
    digest = hashlib.sha256()
    with path.open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def uuid_key(value):
    if str(UUID(value)) != value:
        raise ValueError("Unexpected evidence key; only canonical generated UUID keys are supported")
    return value


def plain_file(path):
    if path.is_symlink() or not path.is_file():
        raise ValueError("Backup input must be a regular file, never a symlink")
    return path


def inventory(folder):
    rows = []
    for path in sorted(folder.rglob("*")):
        if path.is_symlink():
            raise ValueError("Symlinks are not permitted in a backup bundle")
        if path.is_file() and path != folder / "manifest.json":
            rows.append({"path": path.relative_to(folder).as_posix(), "bytes": path.stat().st_size, "sha256": sha(path)})
    return rows


def verify(folder):
    folder = folder.resolve()
    manifest = json.loads(plain_file(folder / "manifest.json").read_text(encoding="utf-8"))
    if manifest.get("format") != "mfu-doctracking-backup-v1":
        raise ValueError("Unsupported backup format")
    if manifest["files"] != inventory(folder):
        raise ValueError("Backup checksum/file inventory mismatch")
    if "database.dump" not in {item["path"] for item in manifest["files"]}:
        raise ValueError("Database dump missing")
    for row in manifest["files"]:
        name = row["path"]
        if name != "database.dump":
            if not name.startswith("evidence/") or name.count("/") != 1:
                raise ValueError("Unexpected bundle path")
            uuid_key(name.split("/")[1])
    return manifest


def require_quiescence(database):
    # Stop every application/worker/timer/cleanup instance and revoke/fence other writers first.
    active = int(sql(database, "select count(*) from pg_stat_activity where datname=current_database() and pid<>pg_backend_pid() and backend_type='client backend'").strip())
    if active:
        raise ValueError("Other DB clients remain: fence all writers before backup/restore")


def evidence_rows(database):
    query = "select storage_key,checksum_sha256,size_bytes from evidence_uploads where status='FINALIZED' order by storage_key"
    return list(csv.reader(io.StringIO(sql(database, query))))


def check_evidence(database, folder):
    rows = evidence_rows(database)
    for key, digest, size in rows:
        path = plain_file(folder / uuid_key(key))
        if path.stat().st_size != int(size) or sha(path) != digest:
            raise ValueError("Finalized evidence does not match database checksum/size")
    return len(rows)


def s3_args(args):
    return ["aws"] + (["--endpoint-url", args.s3_endpoint] if args.s3_endpoint else []) + ["s3api"]


def s3_keys(args, bucket):
    # AWS CLI auto-paginates the full listing. No public ACLs or signed URLs are created.
    result = command(s3_args(args) + ["list-objects-v2", "--bucket", bucket, "--output", "json"])
    return [uuid_key(item["Key"]) for item in json.loads(result).get("Contents", [])]


def backup(args):
    database = database_name(args.database)
    if not args.maintenance_confirmed:
        raise ValueError("Stop/fence all writers and explicitly confirm maintenance")
    output = Path(args.output).resolve()
    if output.exists():
        raise ValueError("Backup output must be a new directory")
    require_quiescence(database)
    started = utc()
    output.mkdir(parents=True, mode=0o700)
    photos = output / "evidence"
    photos.mkdir(mode=0o700)
    # Single pg_dump snapshot contains application, Flyway, session, outbox and Flowable-owned tables.
    command(["pg_dump", "--no-password", "--dbname", database, "--format=custom", "--file", str(output / "database.dump")])
    if args.evidence_root:
        source = Path(args.evidence_root).resolve()
        if source == photos or output.is_relative_to(source) or source.is_relative_to(output):
            raise ValueError("Backup output and evidence root must be separate")
        for item in source.iterdir():
            uuid_key(item.name)
            shutil.copyfile(plain_file(item), photos / item.name)
    else:
        for key in s3_keys(args, args.s3_bucket):
            command(s3_args(args) + ["get-object", "--bucket", args.s3_bucket, "--key", key, str(photos / key)])
    finalized = check_evidence(database, photos)
    require_quiescence(database)
    manifest = {"format": "mfu-doctracking-backup-v1", "snapshot_started_at": started, "snapshot_completed_at": utc(),
                "source_database": database, "release_id": args.release_id, "writer_fence_confirmed": True,
                "evidence_backend": "local" if args.evidence_root else "s3", "finalized_evidence_count": finalized,
                "files": inventory(output)}
    (output / "manifest.json").write_text(json.dumps(manifest, indent=2) + "\n", encoding="utf-8")
    verify(output)
    print("Backup verified; keep writer fence until the bundle is secured. No retention deletion was performed.")


def restore(args):
    manifest = verify(Path(args.bundle))
    database = database_name(args.target_database)
    if database in ("postgres", "template0", "template1"):
        raise ValueError("Restore target must be a dedicated application database")
    if database == manifest["source_database"]:
        raise ValueError("Restore target must differ from the source database")
    source = Path(args.bundle).resolve() / "evidence"
    if args.evidence_root and Path(args.evidence_root).is_symlink():
        raise ValueError("Restore evidence directory must not be a symlink")
    target = Path(args.evidence_root).resolve() if args.evidence_root else None
    if target and (target == source or target.is_relative_to(source.parent) or source.parent.is_relative_to(target)):
        raise ValueError("Restore evidence target must be separate from the backup bundle")
    if not args.execute:
        print("Dry run: bundle verified; execution requires an empty isolated target DB and evidence store, maintenance confirmation, and exact target confirmation.")
        return
    if not args.maintenance_confirmed or args.confirm_target != database:
        raise ValueError("Confirm maintenance and type the exact target database")
    require_quiescence(database)
    existing = int(sql(database, "select count(*) from pg_class c join pg_namespace n on n.oid=c.relnamespace where n.nspname not in ('pg_catalog','information_schema') and n.nspname not like 'pg_toast%' and n.nspname not like 'pg_temp%'").strip())
    if existing:
        raise ValueError("Target database must be empty; no DROP/CLEAN/overwrite is supported")
    if target:
        if target.is_symlink() or (target.exists() and any(target.iterdir())):
            raise ValueError("Restore evidence directory must be empty")
    elif s3_keys(args, args.s3_bucket):
        raise ValueError("Restore S3 bucket must be empty and isolated")
    started, clock = utc(), time.monotonic()
    # No --clean: a failed restore remains isolated for investigation, never overwrites production.
    command(["pg_restore", "--no-password", "--dbname", database, "--no-owner", "--no-privileges", "--exit-on-error", str(Path(args.bundle).resolve() / "database.dump")])
    if target:
        target.mkdir(parents=True, exist_ok=True, mode=0o700)
        for item in source.iterdir():
            shutil.copyfile(plain_file(item), target / uuid_key(item.name))
        finalized = check_evidence(database, target)
    else:
        for item in source.iterdir():
            command(s3_args(args) + ["put-object", "--bucket", args.s3_bucket, "--key", uuid_key(item.name), "--body", str(plain_file(item))])
            # Verify stored content bytes, including S3-compatible services without checksum metadata.
            import tempfile
            with tempfile.TemporaryDirectory() as temporary:
                downloaded = Path(temporary) / item.name
                command(s3_args(args) + ["get-object", "--bucket", args.s3_bucket, "--key", item.name, str(downloaded)])
                if sha(downloaded) != sha(item):
                    raise ValueError("Restored S3 object checksum mismatch")
        finalized = check_evidence(database, source)
    report = {"restore_started_at": started, "restore_completed_at": utc(), "data_restore_seconds": round(time.monotonic() - clock, 3),
              "snapshot_started_at": manifest["snapshot_started_at"], "target_database": database, "finalized_evidence_verified": finalized,
              "rpo_seconds": None, "service_rto_seconds": None, "status": "DATA_RESTORED_SERVICE_VALIDATION_REQUIRED"}
    # stdout contains no credentials or evidence identifiers; operator stores this report securely.
    print(json.dumps(report, indent=2))


def release(args):
    for image in [args.backend_image, args.frontend_image]:
        if not re.fullmatch(r"[a-zA-Z0-9][a-zA-Z0-9._/:-]*@sha256:[0-9a-f]{64}", image):
            raise ValueError("Both release images require immutable registry image@sha256 digests")
    output = Path(args.output)
    if output.exists():
        raise ValueError("Release override already exists")
    output.write_text(f"services:\n  backend:\n    image: {args.backend_image}\n  frontend:\n    image: {args.frontend_image}\n", encoding="utf-8")
    print("Pinned image override written; deployment was not started.")


def smoke(args):
    from urllib.parse import urlparse
    origin = args.origin.rstrip("/")
    parsed = urlparse(origin)
    if parsed.scheme != "https" and not (args.allow_http_localhost and parsed.scheme == "http" and parsed.hostname in ("localhost", "127.0.0.1", "::1")):
        raise ValueError("HTTPS origin required (HTTP allowed only explicitly on loopback)")
    if parsed.path not in ("", "/") or parsed.username or parsed.password or parsed.query or parsed.fragment:
        raise ValueError("Use a plain origin without credentials/path/query")
    class NoRedirect(urllib.request.HTTPRedirectHandler):
        def redirect_request(self, *unused):
            return None
    client = urllib.request.build_opener(NoRedirect())
    for path, expected in [("/healthz", 200), ("/api/v1/auth/csrf", 401), ("/api/v1/auth/me", 401)]:
        payload = None
        try:
            with client.open(origin + path, timeout=15) as response:
                status = response.status
                if status == 200:
                    payload = json.loads(response.read(65537))
        except urllib.error.HTTPError as failure:
            status = failure.code
        if status != expected:
            raise ValueError(f"Smoke failed at {path}: expected {expected}, received {status}")
        if path == "/healthz" and (not isinstance(payload, dict) or payload.get("status") != "UP"):
            raise ValueError("Readiness response is not UP")
    print("Anonymous readiness and protected CSRF/session smoke passed; no login or notification was triggered.")


def parser():
    root = argparse.ArgumentParser(description=__doc__)
    commands = root.add_subparsers(dest="action", required=True)
    item = commands.add_parser("backup")
    item.add_argument("--database", required=True)
    item.add_argument("--output", required=True)
    item.add_argument("--release-id", required=True)
    item.add_argument("--maintenance-confirmed", action="store_true")
    source = item.add_mutually_exclusive_group(required=True)
    source.add_argument("--evidence-root")
    source.add_argument("--s3-bucket")
    item.add_argument("--s3-endpoint", default="")
    item.set_defaults(run=backup)
    item = commands.add_parser("verify")
    item.add_argument("--bundle", required=True)
    item.set_defaults(run=lambda args: (verify(Path(args.bundle)), print("Backup checksum inventory verified.")))
    item = commands.add_parser("restore")
    item.add_argument("--bundle", required=True)
    item.add_argument("--target-database", required=True)
    destination = item.add_mutually_exclusive_group(required=True)
    destination.add_argument("--evidence-root")
    destination.add_argument("--s3-bucket")
    item.add_argument("--s3-endpoint", default="")
    item.add_argument("--execute", action="store_true")
    item.add_argument("--maintenance-confirmed", action="store_true")
    item.add_argument("--confirm-target", default="")
    item.set_defaults(run=restore)
    item = commands.add_parser("release")
    item.add_argument("--backend-image", required=True)
    item.add_argument("--frontend-image", required=True)
    item.add_argument("--output", required=True)
    item.set_defaults(run=release)
    item = commands.add_parser("smoke")
    item.add_argument("--origin", required=True)
    item.add_argument("--allow-http-localhost", action="store_true")
    item.set_defaults(run=smoke)
    return root


if __name__ == "__main__":
    try:
        arguments = parser().parse_args()
        arguments.run(arguments)
    except (ValueError, OSError, RuntimeError, KeyError) as failure:
        print(f"Operation stopped: {failure}", file=sys.stderr)
        sys.exit(1)
