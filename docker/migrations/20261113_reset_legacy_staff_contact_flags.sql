-- Migration: 20261113_reset_legacy_staff_contact_flags.sql
-- Purpose: Ensure legacy internal staff accounts created before UC28 PR 2 do not keep
--          auto-assigned verified flags without recorded self-OTP verification proof bound
--          to their current email address in audit_logs.
-- Safe for repeated execution (idempotent). Apply before enabling internal self-recovery.

UPDATE users u
JOIN roles r ON u.role_id = r.id
SET
    u.email_verified = CASE
        WHEN u.email_verified = TRUE
             AND u.email IS NOT NULL
             AND TRIM(u.email) <> ''
             AND EXISTS (
                 SELECT 1
                 FROM audit_logs al
                 WHERE al.target_table = 'users'
                   AND al.target_id = u.id
                   AND al.user_id = u.id
                   AND al.action = 'INTERNAL_EMAIL_VERIFIED'
                   AND JSON_VALID(al.details_json)
                   AND JSON_EXTRACT(al.details_json, '$.email_verified') = CAST('true' AS JSON)
                   AND JSON_TYPE(JSON_EXTRACT(al.details_json, '$.verified_email')) = 'STRING'
                   AND LOWER(TRIM(JSON_UNQUOTE(JSON_EXTRACT(al.details_json, '$.verified_email')))) = LOWER(TRIM(u.email))
                   AND NOT EXISTS (
                       SELECT 1
                       FROM audit_logs rev
                       WHERE rev.target_table = 'users'
                         AND rev.target_id = u.id
                         AND (
                             rev.action IN ('ADMIN_STAFF_EMAIL_REVOKED', 'USER_CREATE')
                             OR (
                                 rev.action = 'ADMIN_STAFF_CONTACT_UPDATED'
                                 AND JSON_VALID(rev.details_json)
                                 AND (
                                     JSON_EXTRACT(rev.details_json, '$.email_changed') = CAST('true' AS JSON)
                                     OR JSON_EXTRACT(rev.details_json, '$.email_verification_reset') = CAST('true' AS JSON)
                                     OR JSON_EXTRACT(rev.details_json, '$.email_verified') = CAST('false' AS JSON)
                                 )
                             )
                         )
                         AND rev.id > al.id
                   )
             ) THEN TRUE
        ELSE FALSE
    END,
    u.phone_verified = FALSE
WHERE r.role_name IN ('ROLE_ADMIN', 'ROLE_MANAGER', 'ROLE_SALE_STAFF');
