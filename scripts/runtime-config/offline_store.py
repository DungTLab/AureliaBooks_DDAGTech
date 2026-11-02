"""Disposable transaction simulation, NOT a VPS installer or Docker adapter."""
from contextlib import contextmanager
from dataclasses import dataclass
import fcntl
import hashlib
import json
import os
from pathlib import Path
import stat
import tempfile
from policy import check, Rejected, render, validate_generation, GROUPS

TEMPLATE = Path(__file__).resolve().parents[2] / "deployment/runtime-config/compose.runtime.template.yml"
IMAGE_A = "sha256:" + "a" * 64


class SimulatedCrash(BaseException):
    pass


@dataclass(frozen=True)
class DatabaseIdentity:
    container: str = "synthetic-db-container"
    credentials_fingerprint: str = "opaque-synthetic-identity-not-credentials"


class FakeApplication:
    def __init__(self):
        self.image = IMAGE_A
        self.generation = None
        self.overlay = None
        self.database = DatabaseIdentity()
        self.fail_next = False
        self.fail_all = False

    def recreate(self, image, generation, overlay):
        self.image, self.generation, self.overlay = image, generation, overlay
        if self.fail_next or self.fail_all:
            self.fail_next = False
            raise Rejected("SIMULATED_READINESS_FAILURE")


class OfflineStore:
    def __init__(self):
        # Caller cannot point this helper at /opt/apps, a checkout or an existing tree.
        self._temporary = tempfile.TemporaryDirectory(prefix="aurelia-config-offline-", dir="/tmp")
        self.root = Path(self._temporary.name)
        self.root.chmod(0o700)
        (self.root / "releases").mkdir(mode=0o700)
        (self.root / "releases").chmod(0o700)
        self.app = FakeApplication()
        self._database = self.app.database
        self._write("confirmed.json", {"generation": None})
        self._write("current.json", {"generation": None})
        self._write("transaction.json", {"status": "IDLE"})
        self._write("history.json", {"generations": []})

    def __enter__(self):
        return self

    def __exit__(self, *_):
        self._temporary.cleanup()

    def _guard(self):
        for directory in (self.root, self.root / "releases"):
            mode = directory.lstat()
            check(stat.S_ISDIR(mode.st_mode) and mode.st_uid == os.getuid()
                  and stat.S_IMODE(mode.st_mode) == 0o700, "UNSAFE_FIXTURE_DIRECTORY")
        check(self.app.database == self._database, "DATABASE_CONTRACT_CHANGED")

    @contextmanager
    def lock(self):
        self._guard()
        try:
            fd = os.open(self.root / "host.lock",
                         os.O_CREAT | os.O_EXCL | os.O_RDWR | os.O_NOFOLLOW, 0o600)
        except FileExistsError:
            # Never normalize an existing unsafe object into an apparently safe one.
            fd = os.open(self.root / "host.lock", os.O_RDWR | os.O_NOFOLLOW)
        else:
            try:
                os.fchmod(fd, 0o600)
            except BaseException:
                os.close(fd)
                raise
        try:
            info = os.fstat(fd)
            check(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
                  and stat.S_IMODE(info.st_mode) == 0o600, "UNSAFE_LOCK")
            try:
                fcntl.flock(fd, fcntl.LOCK_EX | fcntl.LOCK_NB)
            except BlockingIOError:
                raise Rejected("BUSY_SHARED_DEPLOYMENT_LOCK") from None
            yield
        finally:
            os.close(fd)

    def _fsync_directory(self, directory):
        fd = os.open(directory, os.O_RDONLY | os.O_DIRECTORY | os.O_NOFOLLOW)
        try:
            os.fsync(fd)
        finally:
            os.close(fd)

    def _write(self, name, value):
        path = self.root / name
        fd, filename = tempfile.mkstemp(prefix=name + ".pending-", dir=self.root)
        temporary = Path(filename)
        encoded = json.dumps(value, sort_keys=True).encode("ascii")
        try:
            with os.fdopen(fd, "wb") as stream:
                os.fchmod(stream.fileno(), 0o600)
                stream.write(encoded)
                stream.flush()
                os.fsync(stream.fileno())
            os.replace(temporary, path)
            self._fsync_directory(self.root)
        finally:
            temporary.unlink(missing_ok=True)

    def _read(self, path, mode=0o600):
        fd = os.open(path, os.O_RDONLY | os.O_NOFOLLOW)
        with os.fdopen(fd, "rb") as stream:
            info = os.fstat(stream.fileno())
            check(stat.S_ISREG(info.st_mode) and info.st_uid == os.getuid()
                  and stat.S_IMODE(info.st_mode) == mode, "UNSAFE_FILE")
            data = stream.read(131073)
            check(len(data) <= 131072, "OVERSIZED_FILE")
            return data

    def _json(self, name):
        try:
            return json.loads(self._read(self.root / name))
        except (ValueError, UnicodeError):
            raise Rejected("INVALID_METADATA") from None

    def confirmed(self):
        return self._json("confirmed.json")["generation"]

    def _role(self, role, operation):
        permitted = {"receiver": {"stage"}, "installer": {"stage", "activate", "recover", "deploy", "rollback"}}
        check(operation in permitted.get(role, set()), "ROLE_DENIED")

    def stage(self, generation, revision, values, groups=(), role="receiver"):
        self._role(role, "stage")
        validate_generation(generation, revision)
        payload = render(values, groups)
        with self.lock():
            release = self.root / "releases" / generation
            check(not release.exists() and not release.is_symlink(), "GENERATION_ALREADY_EXISTS")
            # No group access while a generation is being assembled, even umask 000.
            release.mkdir(mode=0o700)
            release.chmod(0o700)
            overlay = TEMPLATE.read_text().replace("__GENERATION__", generation).replace(
                "__APPROVED_READ_GID__", str(os.getgid())).replace(
                "__IMMUTABLE_RELEASE_DIRECTORY__", str(release))
            files = {
                "runtime-integrations.properties": (payload, 0o440),
                "compose.runtime.yml": (overlay.encode("ascii"), 0o440),
            }
            # Digests remain private fixture metadata; never emitted as workflow outputs.
            metadata = {"generation": generation, "revision": revision, "groups": sorted(set(groups)),
                        "digests": {k: hashlib.sha256(v[0]).hexdigest() for k, v in files.items()}}
            files["metadata.json"] = (json.dumps(metadata).encode("ascii"), 0o600)
            for name, (body, mode) in files.items():
                fd = os.open(release / name,
                             os.O_CREAT | os.O_EXCL | os.O_WRONLY | os.O_NOFOLLOW, 0o600)
                with os.fdopen(fd, "wb") as stream:
                    os.fchmod(stream.fileno(), 0o600)
                    stream.write(body)
                    stream.flush()
                    os.fchmod(stream.fileno(), mode)
                    os.fsync(stream.fileno())
            release.chmod(0o750)
            self._fsync_directory(release)
            self._fsync_directory(release.parent)
            return generation  # No payload, values or digest in the public result.

    def validate(self, generation):
        validate_generation(generation, "0" * 40)
        release = self.root / "releases" / generation
        info = release.lstat()
        check(stat.S_ISDIR(info.st_mode) and info.st_uid == os.getuid()
              and stat.S_IMODE(info.st_mode) == 0o750, "UNSAFE_RELEASE")
        check({p.name for p in release.iterdir()} == {
            "metadata.json", "runtime-integrations.properties", "compose.runtime.yml"}, "UNEXPECTED_RELEASE_FILE")
        try:
            metadata = json.loads(self._read(release / "metadata.json"))
        except (ValueError, UnicodeError):
            raise Rejected("INVALID_METADATA") from None
        check(metadata.get("generation") == generation, "GENERATION_MISMATCH")
        validate_generation(generation, metadata.get("revision"))
        groups = metadata.get("groups")
        check(isinstance(groups, list) and all(isinstance(g, str) for g in groups)
              and set(groups) <= GROUPS and groups == sorted(set(groups)), "INVALID_GROUP_METADATA")
        for name in ("runtime-integrations.properties", "compose.runtime.yml"):
            body = self._read(release / name, 0o440)
            check(hashlib.sha256(body).hexdigest() == metadata["digests"].get(name),
                  "IMMUTABLE_GENERATION_CHANGED")
        expected_overlay = TEMPLATE.read_text().replace("__GENERATION__", generation).replace(
            "__APPROVED_READ_GID__", str(os.getgid())).replace(
            "__IMMUTABLE_RELEASE_DIRECTORY__", str(release)).encode("ascii")
        check(self._read(release / "compose.runtime.yml", 0o440) == expected_overlay, "OVERLAY_CONTRACT_CHANGED")
        return str(release / "compose.runtime.yml")

    def _clean(self):
        check(self._json("transaction.json")["status"] in {"IDLE", "CONFIRMED", "ROLLED_BACK"},
              "RECOVERY_REQUIRED")
        check(self._json("current.json")["generation"] == self.confirmed()
              == self.app.generation, "RUNNING_GENERATION_CONFLICT")

    def _crash(self, requested, point):
        if requested == point:
            raise SimulatedCrash("SIMULATED_CRASH")

    def activate(self, generation, expected_current, expected_image, role="installer", crash_at=None):
        return self._activate(generation, expected_current, expected_image, role, crash_at, False)

    def rollback(self, generation, expected_current, expected_image, role="installer"):
        return self._activate(generation, expected_current, expected_image, role, None, True)

    def _activate(self, generation, expected_current, expected_image, role, crash_at, rollback):
        self._role(role, "rollback" if rollback else "activate")
        with self.lock():
            self._clean()
            check(self.confirmed() == expected_current, "STALE_APPROVAL")
            check(self.app.image == expected_image, "IMAGE_CONFLICT")
            check(generation != expected_current, "GENERATION_ALREADY_ACTIVE")
            overlay = self.validate(generation)
            history = self._json("history.json")["generations"]
            if rollback:
                check(generation in history, "ROLLBACK_TARGET_NOT_PREVIOUSLY_CONFIRMED")
            elif expected_current:
                self.validate(expected_current)
                old = json.loads(self._read(self.root / "releases" / expected_current / "metadata.json"))
                new = json.loads(self._read(self.root / "releases" / generation / "metadata.json"))
                check(set(old["groups"]) <= set(new["groups"]), "PROVIDER_REMOVAL_REQUIRES_SEPARATE_APPROVAL")
            self._crash(crash_at, "before_journal")
            transaction = {"status": "APPLYING", "old": expected_current,
                           "target": generation, "image": expected_image, "history_before": history}
            self._write("transaction.json", transaction)
            self._crash(crash_at, "after_journal")
            try:
                self._write("current.json", {"generation": generation})
                self._crash(crash_at, "after_pointer")
                self.app.recreate(expected_image, generation, overlay)
                self._crash(crash_at, "after_recreate")
                self._crash(crash_at, "before_confirm")
                self._write("confirmed.json", {"generation": generation})
                self._crash(crash_at, "after_confirm")
                self._write("history.json", {"generations": history + [generation]})
                self._write("transaction.json", {**transaction, "status": "CONFIRMED"})
                self._crash(crash_at, "after_commit")
            except (Rejected, OSError, ValueError, KeyError):
                self._restore(transaction)
                raise Rejected("ACTIVATION_FAILED_ROLLED_BACK") from None

    def _restore(self, transaction):
        old = transaction["old"]
        try:
            overlay = self.validate(old) if old else None
            self._write("current.json", {"generation": old})
            self.app.recreate(transaction["image"], old, overlay)
            self._write("confirmed.json", {"generation": old})
            self._write("history.json", {"generations": transaction["history_before"]})
            self._write("transaction.json", {**transaction, "status": "ROLLED_BACK"})
        except (Rejected, OSError, ValueError, KeyError):
            self._write("transaction.json", {**transaction, "status": "RECOVERY_FAILED"})
            raise Rejected("ROLLBACK_FAILED_MANUAL_RECOVERY_REQUIRED") from None

    def recover(self, role="installer"):
        self._role(role, "recover")
        with self.lock():
            transaction = self._json("transaction.json")
            if transaction["status"] in {"APPLYING", "RECOVERY_FAILED"}:
                self._restore(transaction)
            self._clean()

    def simulate_image_deployment(self, image, expected_current, role="installer"):
        """Contract for subsequent deploy.sh: same host lock and active overlay."""
        import re
        self._role(role, "deploy")
        check(bool(re.fullmatch("sha256:[0-9a-f]{64}", image)), "INVALID_IMAGE_ID")
        with self.lock():
            self._clean()
            check(self.confirmed() == expected_current, "STALE_DEPLOYMENT")
            overlay = self.validate(expected_current) if expected_current else None
            transaction = {"status": "APPLYING", "old": expected_current,
                           "target": expected_current, "image": self.app.image,
                           "history_before": self._json("history.json")["generations"]}
            self._write("transaction.json", transaction)
            try:
                self.app.recreate(image, expected_current, overlay)
                self._write("transaction.json", {**transaction, "status": "CONFIRMED"})
            except (Rejected, OSError, ValueError, KeyError):
                self._restore(transaction)
                raise Rejected("IMAGE_DEPLOYMENT_FAILED_ROLLED_BACK") from None
