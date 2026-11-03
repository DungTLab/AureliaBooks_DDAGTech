"""Pure offline mapping. Never discovers credentials or reads process environment."""
from dataclasses import dataclass
import re


class Rejected(Exception):
    """Only static codes/key names; never include rejected values or chained errors."""


@dataclass(frozen=True)
class Field:
    scope: str
    target: str
    group: str


FIELDS = {
    "GOOGLE_CLIENT_ID": Field("ES", "GOOGLE_CLIENT_ID", "google"),
    "GOOGLE_CLIENT_SECRET": Field("ES", "GOOGLE_CLIENT_SECRET", "google"),
    "MAIL_HOST": Field("EV", "spring.mail.host", "mail"),
    "MAIL_PORT": Field("EV", "spring.mail.port", "mail"),
    "MAIL_USERNAME": Field("ES", "spring.mail.username", "mail"),
    "MAIL_PASSWORD": Field("ES", "spring.mail.password", "mail"),
    "MAIL_AUTH": Field("EV", "spring.mail.properties.mail.smtp.auth", "mail"),
    "MAIL_STARTTLS": Field("EV", "spring.mail.properties.mail.smtp.starttls.enable", "mail"),
    "MAIL_FROM": Field("EV", "app.contact-verification.mail-from", "mail"),
    "VNPAY_TMN_CODE": Field("ES", "vnpay.tmn-code", "vnpay"),
    "VNPAY_HASH_SECRET": Field("ES", "vnpay.hash-secret", "vnpay"),
    "VNPAY_PAY_URL": Field("EV", "vnpay.pay-url", "vnpay"),
    "VNPAY_RETURN_URL": Field("EV", "vnpay.return-url", "vnpay"),
    "VNPAY_API_URL": Field("EV", "vnpay.api-url", "vnpay"),
    "VNPAY_VERSION": Field("EV", "vnpay.version", "vnpay"),
    "VNPAY_COMMAND": Field("EV", "vnpay.command", "vnpay"),
    "VNPAY_ORDER_TYPE": Field("EV", "vnpay.order-type", "vnpay"),
    "VNPAY_EXPIRE_MINUTES": Field("EV", "vnpay.expire-minutes", "vnpay"),
    "TWILIO_ACCOUNT_SID": Field("ES", "app.contact-verification.twilio-account-sid", "twilio"),
    "TWILIO_AUTH_TOKEN": Field("ES", "app.contact-verification.twilio-auth-token", "twilio"),
    "TWILIO_FROM": Field("ES", "app.contact-verification.twilio-from", "twilio"),
    "R2_ENDPOINT": Field("EV", "app.r2.endpoint", "r2"),
    "R2_BUCKET": Field("EV", "app.r2.bucket", "r2"),
    "R2_UPLOAD_REQUESTS_PER_MINUTE": Field("EV", "app.r2.upload-requests-per-minute", "r2"),
    "R2_UPLOAD_MAX_CONCURRENT": Field("EV", "app.r2.upload-max-concurrent", "r2"),
}
RESERVED_SECRETS = {"R2_ACCESS_KEY_ID", "R2_SECRET_ACCESS_KEY"}
FLAGS = {"R2_ENABLED": "app.r2.enabled", "R2_UPLOAD_ENABLED": "app.r2.upload-enabled"}
GROUPS = {"google", "mail", "vnpay", "twilio"}
MAX_BYTES = 65536


def check(condition, code):
    if not condition:
        raise Rejected(code)


def text(value, key):
    check(isinstance(value, str) and 0 < len(value) <= 4096, "INVALID_TEXT:" + key)
    check("\0" not in value and chr(36) + "{" not in value and "#{" not in value,
          "UNSUPPORTED_INTERPOLATION_OR_NUL:" + key)
    try:
        value.encode("utf-16-be")
    except UnicodeError:
        raise Rejected("INVALID_UNICODE:" + key) from None
    return value


def https_url(value, key):
    from urllib.parse import urlsplit
    try:
        uri = urlsplit(value)
        valid = (uri.scheme == "https" and bool(uri.hostname) and not uri.username
                 and not uri.password and not uri.fragment and not uri.query
                 and not any(c.isspace() for c in value))
        uri.port  # Reject malformed ports without echoing input.
    except ValueError:
        raise Rejected("INVALID_HTTPS_URL:" + key) from None
    check(valid, "INVALID_HTTPS_URL:" + key)


def properties_escape(value):
    """ASCII Java-properties encoding, including UTF-16 pairs; no shell/YAML parsing."""
    out = []
    for ch in value:
        if ch in "\\ :=#!":
            out.append("\\" + ch)
        elif ch in "\t\n\r\f":
            out.append({"\t": "\\t", "\n": "\\n", "\r": "\\r", "\f": "\\f"}[ch])
        elif ord(ch) < 32 or ord(ch) > 126:
            units = ch.encode("utf-16-be")
            out.extend("\\u%04x" % int.from_bytes(units[i:i+2], "big")
                       for i in range(0, len(units), 2))
        else:
            out.append(ch)
    return "".join(out)


def render(values, enabled_groups=()):
    """All-or-nothing declared groups. No implicit provider/profile enablement."""
    check(isinstance(values, dict), "INVALID_INPUT")
    check(isinstance(enabled_groups, (tuple, list, set))
          and all(isinstance(g, str) for g in enabled_groups), "INVALID_GROUP")
    check(set(enabled_groups) <= GROUPS, "INVALID_GROUP")
    check(all(isinstance(k, str) for k in values), "INVALID_KEY")
    check(not (set(values) & RESERVED_SECRETS), "R2_CREDENTIAL_TRANSFER_BLOCKED")
    check(set(values) <= set(FIELDS) | set(FLAGS), "UNKNOWN_KEY")
    for key in FLAGS:
        check(values.get(key, "false") == "false", "R2_MUST_REMAIN_DISABLED:" + key)
    expected = {k for k, field in FIELDS.items() if field.group in enabled_groups}
    supplied = set(values) - set(FLAGS)
    check(expected <= supplied, "INCOMPLETE_PROVIDER_GROUP")
    check(all(FIELDS[k].group == "r2" or FIELDS[k].group in enabled_groups for k in supplied),
          "UNDECLARED_PROVIDER_GROUP")
    result = {target: "false" for target in FLAGS.values()}
    for key in sorted(supplied):
        value = text(values[key], key)
        if key.endswith("_URL") or key == "R2_ENDPOINT":
            https_url(value, key)
        if key in {"MAIL_AUTH", "MAIL_STARTTLS"}:
            check(value in {"true", "false"}, "INVALID_BOOLEAN:" + key)
        if key in {"MAIL_PORT", "VNPAY_EXPIRE_MINUTES", "R2_UPLOAD_REQUESTS_PER_MINUTE",
                   "R2_UPLOAD_MAX_CONCURRENT"}:
            check(bool(re.fullmatch("[1-9][0-9]{0,4}", value)), "INVALID_NUMBER:" + key)
            limit = 65535 if key == "MAIL_PORT" else 10000
            check(int(value) <= limit, "INVALID_NUMBER:" + key)
        if key == "MAIL_HOST":
            check(bool(re.fullmatch("[A-Za-z0-9.-]{1,253}", value)), "INVALID_MAIL_HOST")
        if key == "MAIL_FROM":
            check(bool(re.fullmatch(r"[A-Za-z0-9._+-]+@[A-Za-z0-9.-]+", value)), "INVALID_MAIL_FROM")
        if key == "TWILIO_ACCOUNT_SID":
            check(bool(re.fullmatch("AC[0-9a-fA-F]{32}", value)), "INVALID_TWILIO_SID")
        if key == "TWILIO_FROM":
            check(bool(re.fullmatch(r"\+[1-9][0-9]{7,14}", value)), "UNAPPROVED_TWILIO_SENDER_FORMAT")
        if key == "R2_ENDPOINT":
            check(bool(re.fullmatch(r"https://[0-9a-f]{32}\.r2\.cloudflarestorage\.com", value)),
                  "R2_ENDPOINT_POLICY")
        if key == "R2_BUCKET":
            check(value == "aureliabooks-staging-assets", "R2_BUCKET_POLICY")
        result[FIELDS[key].target] = value
    # Production provider defaults must not silently fall back to unauthenticated SMTP.
    if "mail" in enabled_groups:
        check(values["MAIL_AUTH"] == "true" and values["MAIL_STARTTLS"] == "true",
              "SMTP_POLICY_REQUIRES_AUTH_STARTTLS")
        result["spring.mail.properties.mail.smtp.starttls.required"] = "true"
    payload = "".join(k + "=" + properties_escape(v) + "\n" for k, v in sorted(result.items()))
    encoded = payload.encode("ascii")
    check(len(encoded) <= MAX_BYTES, "PAYLOAD_TOO_LARGE")
    return encoded


def validate_generation(generation, revision):
    check(isinstance(generation, str) and bool(re.fullmatch("[0-9a-f]{32}", generation)),
          "INVALID_GENERATION")
    check(isinstance(revision, str) and bool(re.fullmatch("[0-9a-f]{40}", revision)),
          "INVALID_REVISION")


def validate_precedence_contract(environment_names=(), jvm_options=(), command_args=()):
    """Metadata-only check: never receive or inspect existing environment values."""
    check(not jvm_options and not command_args, "UNVERIFIED_PROCESS_OVERRIDES")
    names = set(environment_names)
    allowed = {"DB_HOST", "DB_PORT", "DB_NAME", "DB_USER", "DB_PASS", "MYSQL_ROOT_PASSWORD",
               "MYSQL_USER", "MYSQL_PASSWORD", "APP_IMAGE", "PORT"}
    # A conservative contract: unknown entries require a reviewed extension.
    check(names <= allowed, "UNVERIFIED_ENVIRONMENT_KEYS")


def require_live_contract(_contract=None):
    # No truthy setting/metadata file can arm this implementation.
    raise Rejected("BLOCKED:VPS_ADAPTER_NOT_IMPLEMENTED_OR_VERIFIED")
