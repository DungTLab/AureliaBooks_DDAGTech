# Account registration and recovery

Confirmed on 12 October 2026. This supersedes the earlier current-contact OTP design.

| Operation | Required proof | Result |
| --- | --- | --- |
| Local registration | Unique phone, compliant password, OTP to registration phone | One active CUSTOMER, verified phone, no email |
| Google registration | Server-validated identity and verified email | Existing sub uses existing account; a new identity cannot reuse another account's email |
| Add/change email or phone | Current local password, or new Google authentication for the same linked identity when Google-only; then OTP to destination | Unique destination saved and verified; sign in again |
| Verify an old unverified contact | Reauthenticate, enter that same contact as destination and verify its OTP | Contact becomes eligible for recovery |
| Link Google | Customer confirms local password and explicitly authenticates Google | Same account/role supports password and Google; no automatic merge by email |
| Change password | Current password and compliant matching new password | Existing behavior retained; forgot-password link added |
| Forgot password | OTP through an already verified email or phone | New password; registered sessions expired; sign in again |

DOB is optional, editable once when missing and immutable afterward; past dates only and no age threshold. Email is removed from the local registration form. Both contact values remain unique when non-null. An email added to Profile is a contact value, not an implicit Google link. Google-only recovery stays with Google; local reset does not create a password for Google-only accounts.

## Recovery channel selection

The user supplies their registered phone/email. Two verified channels provide a choice; one is selected automatically. Unknown/inactive/Google-only accounts receive a generic response and no reset authorization. The public screen does not display actual destinations; its selector still exposes whether two channels are available, a limited metadata disclosure inherent in this UI requirement. Stronger concealment would require changing the selection flow.

## Source flow

- AuthController starts registration OTP through AccountOtpService; no account is inserted until proof succeeds.
- AccountVerificationController serves shared OTP/reset screens and binds IDs to the server session. Submitted owner/flow IDs cannot select the account.
- AccountSecurityService handles transactional registration, reset and Google linking. Reset checks the current password/contact snapshot and expires registered sessions after commit.
- ContactChangeController reauthenticates, then ContactChangeService.startDestination sends OTP to the proposed contact. ContactChangeWriter locks the owner and rechecks uniqueness before writing.
- GoogleProfileController starts a five-minute session-bound intent. GoogleAccountAction consumes validated claims for that owner; wrong/expired identity is rejected. Profile actions open Google's account chooser. Google may reuse its own SSO session; this does not promise it always asks for the Google password again.
- provider_id is a globally unique Google sub. LOCAL can have both password_hash and provider_id without changing account or role.

## OTP controls

Six-digit SecureRandom codes; keyed HMAC retained rather than raw code; five-minute expiry; five wrong entries; sixty-second minimum resend; bounded target/owner/IP quotas. Resends invalidate previous code. A successful new request invalidates prior flows for the same purpose/target or recovery owner. Contact OTP cannot extend its five-minute reauthentication grant. Successful requests are consumed after persistence completes.

Pending requests, HMAC keys and quota counters use **single-JVM memory**. Restart invalidates pending flows and resets quotas. Contact and registration/recovery services enforce their own quotas. Shared durable storage is needed for multiple app instances or restart-proof limits. Contact verification flags are durable database columns.

## Database and existing accounts

Both canonical SQL copies now have nullable email, email_verified/phone_verified default FALSE and UNIQUE(provider_id). There are still 21 physical tables; no OTP table was added.

Fresh empty databases use the canonical schema. Existing local data uses docker/migrations/20261012_account_contact_verification.sql. Do not run schema.sql against populated data: its existing DROP DATABASE recreates the database. Local users/roles were backed up and the schema upgraded without changing the account count.

Old contacts are not automatically trusted. Users can verify current contacts through Profile. Google sign-in can verify a matching email claim without overwriting a different contact email. An account without a verified channel cannot reset through an unverified destination.

## Delivery configuration

Email uses Spring Mail SMTP; SMS uses Twilio Programmable Messaging. Set private environment variables MAIL_HOST, MAIL_PORT, MAIL_USERNAME, MAIL_PASSWORD, MAIL_AUTH, MAIL_STARTTLS, MAIL_FROM, TWILIO_ACCOUNT_SID, TWILIO_AUTH_TOKEN and TWILIO_FROM. Use the provider's authorized sender and required auth/TLS. Do not commit credentials.

Without SMS configuration, phone registration and destination-phone verification fail closed. There is no production OTP bypass.

Local capture command: `docker compose --profile mail up -d mailpit`. SMTP localhost:1025, inbox http://localhost:8025. Mailpit captures rather than sends external email. Browser QA used a separate SMS-to-Mailpit interceptor outside project source; it is not deployed.

## Verification

Workspace docs/account-flow-v2-20261012/QA_QC.md records checks. External Google/SMS and production SMTP were not exercised. The existing authenticated navbar overflows at 375 px. RDS account text and users dictionary were updated with backups; original UI prototypes, full-document layout and SDS diagrams were not regenerated in this task.
