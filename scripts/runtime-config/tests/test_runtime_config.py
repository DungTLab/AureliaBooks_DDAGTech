import contextlib
import dataclasses
import hashlib
import io
import json
import os
import stat
from pathlib import Path
import sys
import threading
import unittest
import uuid
from unittest import mock

DIRECTORY = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(DIRECTORY))
from policy import (FIELDS, FLAGS, RESERVED_SECRETS, Rejected, render, require_live_contract,
                    validate_generation, validate_precedence_contract)
from offline_store import OfflineStore, SimulatedCrash, IMAGE_A, TEMPLATE

SENTINEL = "SYNTHETIC_RUNTIME_SECRET_" + uuid.uuid4().hex
REVISION = "d" * 40
FIRST, SECOND = "1" * 32, "2" * 32


def assert_absent(case, needle, value):
    # unittest membership assertions print both operands on failure. Never pass them.
    case.assertFalse(needle in value, "UNEXPECTED_OR_SECRET_VALUE")


def assert_contains(case, needle, value):
    case.assertTrue(needle in value, "EXPECTED_MARKER_MISSING")


def fixture(group="google"):
    if group == "google":
        return {"GOOGLE_CLIENT_ID": "synthetic-client", "GOOGLE_CLIENT_SECRET": SENTINEL}
    if group == "mail":
        return dict(MAIL_HOST="smtp.example.invalid", MAIL_PORT="587",
                    MAIL_USERNAME="synthetic@example.invalid", MAIL_PASSWORD=SENTINEL,
                    MAIL_AUTH="true", MAIL_STARTTLS="true", MAIL_FROM="synthetic@example.invalid")
    if group == "twilio":
        return dict(TWILIO_ACCOUNT_SID="AC" + "0" * 32, TWILIO_AUTH_TOKEN=SENTINEL,
                    TWILIO_FROM="+15555550123")
    return dict(VNPAY_TMN_CODE="synthetic-merchant", VNPAY_HASH_SECRET=SENTINEL,
                VNPAY_PAY_URL="https://pay.example.invalid/pay",
                VNPAY_RETURN_URL="https://shop.example.invalid/return",
                VNPAY_API_URL="https://api.example.invalid/query", VNPAY_VERSION="2.1.0",
                VNPAY_COMMAND="pay", VNPAY_ORDER_TYPE="other", VNPAY_EXPIRE_MINUTES="15")


class PolicyTests(unittest.TestCase):
    def test_explicit_ev_es_mapping(self):
        targets = [v.target for v in FIELDS.values()] + list(FLAGS.values())
        self.assertEqual(len(targets), len(set(targets)))
        self.assertTrue(all(v.scope in {"EV", "ES"} for v in FIELDS.values()))
        for key in ("GOOGLE_CLIENT_SECRET", "MAIL_PASSWORD", "TWILIO_AUTH_TOKEN", "VNPAY_HASH_SECRET"):
            self.assertEqual("ES", FIELDS[key].scope)

    def test_r2_disabled_even_when_no_provider_configured(self):
        self.assertTrue(render({}) == b"app.r2.enabled=false\napp.r2.upload-enabled=false\n")

    def test_r2_true_and_credentials_rejected(self):
        for key in FLAGS:
            with self.subTest(key=key), self.assertRaises(Rejected):
                render({key: "true"})
        for key in RESERVED_SECRETS:
            with self.subTest(key=key), self.assertRaises(Rejected):
                render({key: SENTINEL})

    def test_database_image_bootstrap_profile_and_unknown_keys_rejected(self):
        for key in ("DB_PASS", "MYSQL_ROOT_PASSWORD", "APP_IMAGE", "BOOTSTRAP_ADMIN_PASSWORD",
                    "SPRING_PROFILES_ACTIVE", "spring.config.import", "spring.datasource.url",
                    "JAVA_TOOL_OPTIONS", "OTHER"):
            with self.subTest(key=key), self.assertRaises(Rejected) as error:
                render({key: SENTINEL})
            assert_absent(self, SENTINEL, str(error.exception))

    def test_complete_provider_groups(self):
        for group in ("google", "mail", "twilio", "vnpay"):
            with self.subTest(group=group):
                body = render(fixture(group), [group])
                assert_contains(self, b"app.r2.enabled=false\n", body)

    def test_partial_or_undeclared_group_fails(self):
        with self.assertRaises(Rejected):
            render({"GOOGLE_CLIENT_ID": "synthetic-client"}, ["google"])
        with self.assertRaises(Rejected):
            render(fixture())

    def test_google_credentials_do_not_activate_a_profile(self):
        body = render(fixture(), ["google"])
        assert_absent(self, b"spring.profiles", body)
        assert_absent(self, b"spring.security.oauth2", body)

    def test_interpolation_spel_nul_unpaired_unicode_fail_without_values(self):
        for bad in (chr(36) + "{DB_PASS}", "#{T(java.lang.Runtime)}", "\0", "\ud800"):
            with self.subTest(kind="unsupported-input"), self.assertRaises(Rejected) as error:
                render({**fixture(), "GOOGLE_CLIENT_SECRET": SENTINEL + bad}, ["google"])
            assert_absent(self, SENTINEL, str(error.exception))

    def test_format_escaping_is_ascii_and_preserves_dollars(self):
        value = " " + SENTINEL + " : = # ! $ \\ \t\r\nĐặng 😀 "
        body = render({**fixture(), "GOOGLE_CLIENT_SECRET": value}, ["google"])
        self.assertTrue(body.isascii())
        assert_contains(self, b"\\n", body)
        assert_contains(self, b"\\u", body)
        assert_contains(self, b"$", body)

    def test_smtp_security_policy(self):
        for key in ("MAIL_AUTH", "MAIL_STARTTLS"):
            with self.subTest(key=key), self.assertRaises(Rejected):
                render({**fixture("mail"), key: "false"}, ["mail"])
        assert_contains(self, b"starttls.required=true", render(fixture("mail"), ["mail"]))

    def test_urls_numbers_and_sender_validation(self):
        cases = [("vnpay", "VNPAY_PAY_URL", "http://example.invalid"),
                 ("vnpay", "VNPAY_RETURN_URL", "https://user:pass@example.invalid/x"),
                 ("vnpay", "VNPAY_API_URL", "https://example.invalid:bad"),
                 ("vnpay", "VNPAY_API_URL", "https://example.invalid/query?credential=not-allowed"),
                 ("vnpay", "VNPAY_EXPIRE_MINUTES", "0"),
                 ("mail", "MAIL_PORT", "65536"), ("mail", "MAIL_HOST", "host\ninjected"),
                 ("mail", "MAIL_FROM", "sender\ninjected"), ("twilio", "TWILIO_FROM", "invalid")]
        for group, key, value in cases:
            with self.subTest(key=key), self.assertRaises(Rejected):
                render({**fixture(group), key: value}, [group])

    def test_r2_endpoint_bucket_metadata_are_exactly_scoped(self):
        render({"R2_ENDPOINT": "https://" + "0" * 32 + ".r2.cloudflarestorage.com",
                "R2_BUCKET": "aureliabooks-staging-assets"})
        for key, value in (("R2_ENDPOINT", "https://example.invalid"),
                           ("R2_BUCKET", "production-bucket")):
            with self.subTest(key=key), self.assertRaises(Rejected):
                render({key: value})

    def test_precedence_conflicts_fail_from_names_without_reading_values(self):
        validate_precedence_contract(["DB_PASS", "APP_IMAGE", "PORT"])
        for key in ("MAIL_PASSWORD", "SPRING_MAIL_PASSWORD", "APP_R2_ENABLED",
                    "R2_ENABLED", "SPRING_APPLICATION_JSON", "SPRING_CONFIG_LOCATION",
                    "SPRING_PROFILES_ACTIVE", "JAVA_TOOL_OPTIONS", "UNKNOWN"):
            with self.subTest(key=key), self.assertRaises(Rejected):
                validate_precedence_contract([key])
        with self.assertRaises(Rejected):
            validate_precedence_contract(jvm_options=["unverified"])

    def test_generation_and_revision_validation(self):
        for generation, revision in (("../escape", REVISION), ("F" * 32, REVISION),
                                     (FIRST, "bad"), ("", REVISION)):
            with self.subTest(kind="invalid-id"), self.assertRaises(Rejected):
                validate_generation(generation, revision)

    def test_live_adapter_cannot_be_armed_by_metadata(self):
        for contract in (None, {}, {"verified": True, "production": True}):
            with self.assertRaises(Rejected) as error:
                require_live_contract(contract)
            self.assertTrue(str(error.exception).startswith("BLOCKED:"))

    def test_no_environment_or_network_discovery(self):
        with mock.patch("os.environ", {"GOOGLE_CLIENT_SECRET": SENTINEL}), \
                mock.patch("socket.socket", side_effect=AssertionError("NETWORK_FORBIDDEN")):
            assert_absent(self, SENTINEL.encode(), render({}))
            render(fixture(), ["google"])


class FailedAssertionSafetyTests(unittest.TestCase):
    def captured_failure(self, assertion, message):
        class DeliberatelyFailingCase(unittest.TestCase):
            def runTest(self):
                assertion(self)
        output = io.StringIO()
        result = unittest.TextTestRunner(stream=output, verbosity=2, tb_locals=False).run(
            DeliberatelyFailingCase())
        # Do not use operand-printing assertions to validate the guard's own failure.
        self.assertTrue(len(result.failures) == 1 and not result.errors, "EXPECTED_ONE_FAILURE")
        diagnostic = output.getvalue()
        self.assertFalse(SENTINEL in diagnostic, "REGRESSION_SECRET_LEAK")
        self.assertTrue(message in diagnostic, "FIXED_DIAGNOSTIC_MISSING")

    def test_failed_text_leakage_guard_does_not_print_secret(self):
        self.captured_failure(lambda case: assert_absent(case, SENTINEL, "error:" + SENTINEL),
                              "UNEXPECTED_OR_SECRET_VALUE")

    def test_failed_binary_leakage_guard_does_not_print_secret(self):
        self.captured_failure(lambda case: assert_absent(case, SENTINEL.encode(), SENTINEL.encode()),
                              "UNEXPECTED_OR_SECRET_VALUE")

    def test_failed_property_contract_does_not_dump_secret_bearing_payload(self):
        self.captured_failure(lambda case: assert_contains(case, b"required-marker",
                              ("password=" + SENTINEL).encode()), "EXPECTED_MARKER_MISSING")


class StoreTests(unittest.TestCase):
    def staged(self, store, generation=FIRST):
        return store.stage(generation, REVISION, fixture(), ["google"])

    def test_stage_does_not_activate(self):
        with OfflineStore() as store:
            self.staged(store)
            self.assertIsNone(store.confirmed())
            self.assertIsNone(store.app.generation)

    def test_immutable_generation_cannot_be_reused(self):
        with OfflineStore() as store:
            self.staged(store)
            with self.assertRaises(Rejected):
                self.staged(store)

    def test_activation_preserves_image_and_database(self):
        with OfflineStore() as store:
            before = store.app.database
            self.staged(store)
            store.activate(FIRST, None, IMAGE_A)
            self.assertEqual(FIRST, store.confirmed())
            self.assertEqual(IMAGE_A, store.app.image)
            self.assertEqual(before, store.app.database)

    def test_readiness_failure_restores_previous_generation(self):
        with OfflineStore() as store:
            self.staged(store)
            store.activate(FIRST, None, IMAGE_A)
            self.staged(store, SECOND)
            store.app.fail_next = True
            with self.assertRaises(Rejected):
                store.activate(SECOND, FIRST, IMAGE_A)
            self.assertEqual(FIRST, store.confirmed())
            self.assertEqual(FIRST, store.app.generation)
            self.assertEqual("ROLLED_BACK", store._json("transaction.json")["status"])

    def test_crash_recovery_all_transaction_boundaries(self):
        for point in ("before_journal", "after_journal", "after_pointer", "after_recreate",
                      "before_confirm", "after_confirm", "after_commit"):
            with self.subTest(point=point), OfflineStore() as store:
                self.staged(store)
                store.activate(FIRST, None, IMAGE_A)
                self.staged(store, SECOND)
                with self.assertRaises(SimulatedCrash):
                    store.activate(SECOND, FIRST, IMAGE_A, crash_at=point)
                store.recover()
                expected = SECOND if point == "after_commit" else FIRST
                self.assertEqual(expected, store.confirmed())
                self.assertEqual(expected, store.app.generation)

    def test_pending_recovery_blocks_other_deployments(self):
        with OfflineStore() as store:
            self.staged(store)
            with self.assertRaises(SimulatedCrash):
                store.activate(FIRST, None, IMAGE_A, crash_at="after_pointer")
            with self.assertRaises(Rejected):
                store.simulate_image_deployment("sha256:" + "b" * 64, None)

    def test_fsync_failure_before_pointer_has_no_partial_activation(self):
        with OfflineStore() as store:
            self.staged(store)
            with mock.patch("offline_store.os.fsync", side_effect=OSError("SYNTHETIC_DISK_FAILURE")):
                with self.assertRaises(OSError):
                    store.activate(FIRST, None, IMAGE_A)
            self.assertIsNone(store.confirmed())
            self.assertIsNone(store.app.generation)
            self.assertFalse(list(store.root.glob("*.pending-*")))

    def test_io_failure_after_pointer_rolls_back_without_leaking_exception(self):
        with OfflineStore() as store:
            self.staged(store)
            original = store._write
            failed = False
            def fail_confirmation(name, value):
                nonlocal failed
                if name == "confirmed.json" and not failed:
                    failed = True
                    raise OSError(SENTINEL)
                original(name, value)
            store._write = fail_confirmation
            with self.assertRaises(Rejected) as error:
                store.activate(FIRST, None, IMAGE_A)
            assert_absent(self, SENTINEL, str(error.exception))
            self.assertIsNone(store.confirmed())
            self.assertIsNone(store.app.generation)

    def test_failed_rollback_stays_blocked_then_can_recover(self):
        with OfflineStore() as store:
            self.staged(store)
            store.app.fail_all = True
            with self.assertRaises(Rejected):
                store.activate(FIRST, None, IMAGE_A)
            self.assertEqual("RECOVERY_FAILED", store._json("transaction.json")["status"])
            with self.assertRaises(Rejected):
                store.simulate_image_deployment("sha256:" + "b" * 64, None)
            store.app.fail_all = False
            store.recover()
            self.assertIsNone(store.confirmed())

    def test_stale_generation_and_image_approvals_rejected(self):
        with OfflineStore() as store:
            self.staged(store)
            for previous, image in ((SECOND, IMAGE_A), (None, "sha256:" + "b" * 64)):
                with self.subTest(kind="stale"), self.assertRaises(Rejected):
                    store.activate(FIRST, previous, image)

    def test_replay_of_current_generation_is_rejected(self):
        with OfflineStore() as store:
            self.staged(store)
            store.activate(FIRST, None, IMAGE_A)
            with self.assertRaises(Rejected):
                store.activate(FIRST, FIRST, IMAGE_A)

    def test_silent_provider_removal_is_blocked(self):
        with OfflineStore() as store:
            store.stage(FIRST, REVISION, fixture("mail"), ["mail"])
            store.activate(FIRST, None, IMAGE_A)
            self.staged(store, SECOND)
            with self.assertRaises(Rejected) as error:
                store.activate(SECOND, FIRST, IMAGE_A)
            self.assertTrue(str(error.exception) == "PROVIDER_REMOVAL_REQUIRES_SEPARATE_APPROVAL", "UNEXPECTED_ERROR_CODE")
            self.assertEqual(FIRST, store.confirmed())

    def test_explicit_rollback_to_confirmed_snapshot_can_restore_provider_set(self):
        with OfflineStore() as store:
            self.staged(store)
            store.activate(FIRST, None, IMAGE_A)
            store.stage(SECOND, REVISION, {**fixture(), **fixture("mail")}, ["google", "mail"])
            store.activate(SECOND, FIRST, IMAGE_A)
            store.simulate_image_deployment("sha256:" + "b" * 64, SECOND)
            store.rollback(FIRST, SECOND, "sha256:" + "b" * 64)
            self.assertEqual(FIRST, store.confirmed())
            self.assertEqual("sha256:" + "b" * 64, store.app.image)
            self.assertEqual(str(store.root / "releases" / FIRST / "compose.runtime.yml"), store.app.overlay)

    def test_rollback_to_unconfirmed_generation_or_by_receiver_is_denied(self):
        with OfflineStore() as store:
            self.staged(store)
            store.activate(FIRST, None, IMAGE_A)
            self.staged(store, SECOND)
            with self.assertRaises(Rejected):
                store.rollback(SECOND, FIRST, IMAGE_A)
            with self.assertRaises(Rejected):
                store.rollback(FIRST, FIRST, IMAGE_A, role="receiver")

    def test_role_boundaries(self):
        with OfflineStore() as store:
            for role in ("application", "unknown"):
                with self.subTest(role=role), self.assertRaises(Rejected):
                    store.stage(FIRST, REVISION, fixture(), ["google"], role=role)
            self.staged(store)
            with self.assertRaises(Rejected):
                store.activate(FIRST, None, IMAGE_A, role="receiver")
            with self.assertRaises(Rejected):
                store.recover(role="receiver")

    def test_permissions_and_tampered_generation(self):
        with OfflineStore() as store:
            self.staged(store)
            path = store.root / "releases" / FIRST / "runtime-integrations.properties"
            self.assertEqual(0o440, path.stat().st_mode & 0o777)
            path.chmod(0o600)
            with self.assertRaises(Rejected):
                store.activate(FIRST, None, IMAGE_A)
            path.write_bytes(b"tampered")
            path.chmod(0o440)
            with self.assertRaises(Rejected):
                store.activate(FIRST, None, IMAGE_A)

    def test_restrictive_umask_preserves_modes_activation_rollback_and_recovery(self):
        previous = os.umask(0o077)
        try:
            with OfflineStore() as store:
                self.staged(store)
                store.activate(FIRST, None, IMAGE_A)
                self.staged(store, SECOND)
                store.app.fail_next = True
                with self.assertRaises(Rejected):
                    store.activate(SECOND, FIRST, IMAGE_A)
                self.assertEqual(FIRST, store.confirmed())
                with self.assertRaises(SimulatedCrash):
                    store.activate(SECOND, FIRST, IMAGE_A, crash_at="after_pointer")
                store.recover()
                self.assertEqual(FIRST, store.confirmed())
                store.activate(SECOND, FIRST, IMAGE_A)
                store.rollback(FIRST, SECOND, IMAGE_A)
                self.assertEqual(FIRST, store.confirmed())
                for path in (store.root, store.root / "releases"):
                    self.assertEqual(0o700, stat.S_IMODE(path.stat().st_mode))
                for generation in (FIRST, SECOND):
                    release = store.root / "releases" / generation
                    self.assertEqual(0o750, stat.S_IMODE(release.stat().st_mode))
                    for name in ("runtime-integrations.properties", "compose.runtime.yml"):
                        self.assertEqual(0o440, stat.S_IMODE((release / name).stat().st_mode))
                    self.assertEqual(0o600, stat.S_IMODE((release / "metadata.json").stat().st_mode))
                for name in ("host.lock", "confirmed.json", "current.json", "transaction.json", "history.json"):
                    self.assertEqual(0o600, stat.S_IMODE((store.root / name).stat().st_mode))
        finally:
            os.umask(previous)

    def test_creation_and_promotion_never_expose_broad_permissions(self):
        previous = os.umask(0o000)
        try:
            with OfflineStore() as store:
                original_open, original_chmod = os.open, os.fchmod
                original_replace, original_mkdir = os.replace, os.mkdir
                created, grants, promotions = [], [], []
                release = store.root / "releases" / FIRST
                def observe_mkdir(path, mode=0o777, *args, **kwargs):
                    self.assertFalse(mode & 0o077, "DIRECTORY_NOT_PRIVATE_AT_CREATION")
                    return original_mkdir(path, mode, *args, **kwargs)
                def observe_open(path, flags, mode=0o777, **kwargs):
                    fd = original_open(path, flags, mode, **kwargs)
                    if flags & os.O_CREAT:
                        try:
                            self.assertFalse(mode & 0o077, "FILE_NOT_PRIVATE_AT_CREATION")
                            self.assertFalse(stat.S_IMODE(os.fstat(fd).st_mode) & 0o077,
                                             "CREATED_FILE_TOO_BROAD")
                        except BaseException:
                            os.close(fd)
                            raise
                        created.append(True)
                    return fd
                def observe_chmod(fd, mode):
                    if mode == 0o440:
                        self.assertEqual(0o700, stat.S_IMODE(release.stat().st_mode))
                        grants.append(True)
                    return original_chmod(fd, mode)
                def observe_replace(source, target, *args, **kwargs):
                    self.assertEqual(0o600, stat.S_IMODE(Path(source).stat().st_mode))
                    promotions.append(True)
                    return original_replace(source, target, *args, **kwargs)
                with mock.patch("offline_store.os.mkdir", side_effect=observe_mkdir), \
                        mock.patch("offline_store.os.open", side_effect=observe_open), \
                        mock.patch("offline_store.os.fchmod", side_effect=observe_chmod), \
                        mock.patch("offline_store.os.replace", side_effect=observe_replace):
                    self.staged(store)
                    store.activate(FIRST, None, IMAGE_A)
                self.assertTrue(bool(created) and len(grants) == 2 and bool(promotions),
                                "PERMISSION_OBSERVATIONS_MISSING")
        finally:
            os.umask(previous)

    def test_existing_unsafe_lock_is_rejected_without_normalizing_it(self):
        with OfflineStore() as store:
            self.staged(store)
            lock = store.root / "host.lock"
            lock.chmod(0o666)
            with self.assertRaises(Rejected):
                self.staged(store, SECOND)
            self.assertEqual(0o666, stat.S_IMODE(lock.stat().st_mode))

    def test_symlink_and_unknown_file_rejected(self):
        with OfflineStore() as store:
            self.staged(store)
            release = store.root / "releases" / FIRST
            extra = release / "unexpected"
            extra.touch()
            with self.assertRaises(Rejected):
                store.validate(FIRST)
            extra.unlink()
            props = release / "runtime-integrations.properties"
            props.unlink()
            props.symlink_to(store.root / "confirmed.json")
            with self.assertRaises((OSError, Rejected)):
                store.validate(FIRST)

    def test_database_drift_blocks_operations(self):
        with OfflineStore() as store:
            store.app.database = dataclasses.replace(store.app.database, container="different")
            with self.assertRaises(Rejected):
                self.staged(store)

    def test_subsequent_image_deploy_preserves_active_overlay(self):
        with OfflineStore() as store:
            self.staged(store)
            store.activate(FIRST, None, IMAGE_A)
            overlay = store.app.overlay
            store.simulate_image_deployment("sha256:" + "b" * 64, FIRST)
            self.assertEqual(overlay, store.app.overlay)
            self.assertEqual(FIRST, store.app.generation)
            self.assertEqual(FIRST, store.confirmed())

    def test_failed_image_deploy_restores_image_and_overlay(self):
        with OfflineStore() as store:
            self.staged(store)
            store.activate(FIRST, None, IMAGE_A)
            store.app.fail_next = True
            with self.assertRaises(Rejected):
                store.simulate_image_deployment("sha256:" + "b" * 64, FIRST)
            self.assertEqual(IMAGE_A, store.app.image)
            self.assertEqual(FIRST, store.app.generation)

    def test_actual_shared_flock_blocks_concurrent_operations_both_directions(self):
        for operation in ("config", "image"):
            with self.subTest(holder=operation), OfflineStore() as store:
                self.staged(store)
                store.activate(FIRST, None, IMAGE_A)
                self.staged(store, SECOND)
                entered, release = threading.Event(), threading.Event()
                original = store.app.recreate
                def held(*args):
                    entered.set()
                    if not release.wait(5):
                        raise Rejected("TEST_HOLD_TIMEOUT")
                    original(*args)
                store.app.recreate = held
                errors = []
                def holder():
                    try:
                        if operation == "config":
                            store.activate(SECOND, FIRST, IMAGE_A)
                        else:
                            store.simulate_image_deployment("sha256:" + "b" * 64, FIRST)
                    except BaseException as error:
                        errors.append(type(error).__name__)
                thread = threading.Thread(target=holder)
                thread.start()
                try:
                    self.assertTrue(entered.wait(5))
                    with self.assertRaises(Rejected) as failure:
                        if operation == "config":
                            store.simulate_image_deployment("sha256:" + "b" * 64, FIRST)
                        else:
                            store.activate(SECOND, FIRST, IMAGE_A)
                    self.assertTrue(str(failure.exception) == "BUSY_SHARED_DEPLOYMENT_LOCK", "UNEXPECTED_ERROR_CODE")
                finally:
                    release.set()
                    thread.join(5)
                self.assertFalse(thread.is_alive())
                self.assertFalse(errors)

    def test_no_secret_in_logs_metadata_or_retained_artifacts(self):
        stdout, stderr = io.StringIO(), io.StringIO()
        with contextlib.redirect_stdout(stdout), contextlib.redirect_stderr(stderr):
            with OfflineStore() as store:
                root = store.root
                self.staged(store)
                store.activate(FIRST, None, IMAGE_A)
                for path in root.rglob("*"):
                    if path.is_file() and path.name != "runtime-integrations.properties":
                        self.assertFalse(SENTINEL.encode() in path.read_bytes(), "SECRET_LEAK")
        self.assertFalse(SENTINEL in stdout.getvalue() + stderr.getvalue(), "SECRET_LEAK")
        self.assertFalse(root.exists())


class RepositoryContracts(unittest.TestCase):
    def test_existing_deployment_and_application_files_unchanged(self):
        root = DIRECTORY.parents[1]
        baseline = json.loads((root / "deployment/runtime-config/baseline-sha256.json").read_text())
        for name, digest in baseline["files"].items():
            with self.subTest(file=name):
                self.assertEqual(digest, hashlib.sha256((root / name).read_bytes()).hexdigest())

    def test_workflow_never_accesses_secrets_or_transports_payload(self):
        body = (DIRECTORY.parents[1] / ".github/workflows/runtime-config-sync.yml").read_text()
        assert_absent(self, "secrets.", body)
        assert_absent(self, "upload-artifact", body)
        assert_absent(self, "ssh ", body)
        assert_contains(self, "if: " + chr(36) + "{{ false }}", body)
        assert_contains(self, "environment: production", body)
        assert_contains(self, "refs/heads/main", body)
        assert_contains(self, "group: aureliabooks-production-deploy", body)

    def test_overlay_has_only_additive_runtime_settings(self):
        body = TEMPLATE.read_text()
        for forbidden in ("image:", "mysql-db:", "DB_PASS", "APP_IMAGE", "entrypoint:", "command:", "ports:"):
            assert_absent(self, forbidden, body)
        for required in ('R2_ENABLED: "false"', 'R2_UPLOAD_ENABLED: "false"',
                         "read_only: true", "create_host_path: false", "__IMMUTABLE_RELEASE_DIRECTORY__"):
            assert_contains(self, required, body)
