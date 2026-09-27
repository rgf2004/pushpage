# Sign-up email verification (optional, `cloud` profile)

Gated behind the `cloud` Spring profile (`me.projects.pushpage.cloud.*`). Deployments
that don't set `SPRING_PROFILES_ACTIVE=cloud` are unaffected — signup/login behaves
exactly as without this feature.

## Flow

1. `POST /api/auth/signup` creates the account as usual (unchanged, base logic).
2. `EmailVerificationHooks.afterSignUp` (a `@Primary` override of the base
   `UserLifecycleHooks` no-op) generates a UUID token, stores it with a 24h expiry, and
   emails a confirmation link: `{APP_SERVER_URL}/api/auth/verify-email?token=<uuid>`.
3. `GET /api/auth/verify-email?token=` marks the account verified and redirects (302) to
   `/dashboard?verified=true` (or `/dashboard?verify_error=invalid` if the token is
   unknown/expired).
4. `POST /api/auth/login` is blocked with `403` until the account is verified
   (`EmailVerificationHooks.beforeLogin`). The dashboard shows an inline banner with a
   resend button when it sees a `403` from login.
5. `POST /api/auth/resend-verification` — body `{ "email": "..." }` — regenerates the
   token and resends the email if the account exists and isn't verified yet. Always
   returns `204` regardless of whether the email exists, to avoid account enumeration.
6. `PATCH /api/admin/users/{id}/verify-email` — admin-only — marks a user verified
   manually, bypassing the email link. Wired into the dashboard Users tab: a "Verify"
   button appears next to any unverified user (see "Admin Users tab" below).

## Abuse / DoS protection around `sendVerificationEmail`

Both callers of `EmailVerificationService.sendVerificationEmail` — the post-signup hook
and the resend endpoint — funnel through that one method, so it's the single choke
point guarded:

- **Per-account cooldown** (`app.email.verification-rate-limit-per-minute` /
  `EMAIL_VERIFICATION_RATE_LIMIT_PER_MINUTE`, default `1`): caps how often *any single
  account* can receive a verification email per minute, regardless of which caller
  triggers it. Over the limit, the send is silently skipped (logged at `WARN`) —
  `resendVerification` still returns `204` either way, so this doesn't create a new way
  to enumerate accounts. Note this only throttles repeated sends to the *same* account —
  it does nothing against an attacker who creates many distinct accounts (each gets its
  own fresh bucket), since account creation itself has no rate limit yet. That's a
  signup-abuse problem, not an email-flooding one, and belongs on `POST /api/auth/signup`
  rather than here.
- **Per-IP throttle on `POST /api/auth/resend-verification`**
  (`app.email.resend-ip-rate-limit-per-minute` / `EMAIL_RESEND_IP_RATE_LIMIT_RPM`,
  default `5`): since the endpoint is unauthenticated, without this an attacker could
  still mass-spam many *different* mailboxes (each capped at 1/minute individually) or
  run up the SMTP provider's bill. Returns `429` with `Retry-After` when exceeded.

Both live in `EmailVerificationService` (via `ResendOutcome`, which the controller just
translates into `204`/`429` — no rate-limit decisions in `EmailVerificationController`
itself) and reuse the existing `RateLimitService` (bucket4j-backed, already used by
`RateLimitFilter` for `/pages`) rather than introducing a new mechanism.

Not currently covered: `POST /api/auth/signup` itself has no IP throttle, so an
attacker could still register many new accounts, each getting exactly one initial
email.

## Schema (`V1000__add_email_verification_to_users.sql`)

Adds to `users`:

| Column | Type | Notes |
|--------|------|-------|
| `email_verified` | BOOLEAN NOT NULL | Backfilled `true` for pre-existing rows (so no one already deployed gets locked out); defaults to `false` for new sign-ups. |
| `verification_token` | TEXT, nullable | Cleared once verified. |
| `verification_token_expires_at` | TIMESTAMP, nullable | `EMAIL_VERIFICATION_TOKEN_TTL_HOURS` (default 24h) from generation. |

Indexed on `verification_token` for the `verify-email` lookup.

Note this migration always runs (it's part of the app's classpath regardless of which
profile is active) — it just adds unused columns when the `cloud` profile is off.

## Admin Users tab

`GET /api/admin/users` surfaces verification status via `UserSummary.emailVerified`
(nullable `Boolean` on the base `UserSummary` record — `null`/absent when the `cloud`
profile is off, always `true`/`false` when it's on). `CloudUserSummaryEnricher`
populates it with a bulk lookup (`EmailVerificationRepository.findVerifiedStatusByUserIds`),
the same pattern used for `plan` via `PlanRepository.findPlansByUserIds`.

`nginx/dashboard.html`'s Users tab shows a "Verified" badge per user, and a "Verify"
button next to any unverified account, wired to `PATCH /admin/users/{id}/verify-email`
(item 6 above). The button doesn't use a confirm modal, unlike Promote/Deactivate —
marking an email verified isn't destructive or privilege-granting, so the extra click
adds friction without a matching safety benefit.

## Email delivery — provider-agnostic by design

`EmailService` (`me.projects.pushpage.cloud.email`) is a one-method interface
(`sendVerificationEmail(toEmail, link)`). The only implementation, `SmtpEmailService`,
sends over SMTP via `spring-boot-starter-mail`/`JavaMailSender`. SMTP is supported by
every mainstream transactional email provider (Mailtrap, Resend, SES, Sendgrid,
Postmark, ...), so switching providers is a matter of changing env vars — no new code.

**Not 100% provider-neutral in one respect:** every send sets an `X-MT-Category:
email-verification` header, which is Mailtrap-specific — it groups sends under a named
category in Mailtrap's stats (open/click/bounce rate per category, useful for spotting a
regression in one template). It's sent as a plain `X-` extension header, so any other
SMTP provider just ignores it; nothing else about the integration depends on Mailtrap.

### Env vars (only used when `SPRING_PROFILES_ACTIVE=cloud`)

| Variable | Description | Default |
|----------|--------------|---------|
| `EMAIL_SMTP_HOST` | SMTP hostname | — |
| `EMAIL_SMTP_PORT` | SMTP port | `587` |
| `EMAIL_SMTP_USERNAME` | SMTP auth username | — |
| `EMAIL_SMTP_PASSWORD` | SMTP auth password | — |
| `EMAIL_FROM` | From address on the confirmation email | `no-reply@pushpage.link` |
| `EMAIL_VERIFICATION_TOKEN_TTL_HOURS` | How long a verification link stays valid | `24` |
| `EMAIL_VERIFICATION_RATE_LIMIT_PER_MINUTE` | Max verification emails per account per minute (signup + resend combined) | `1` |
| `EMAIL_RESEND_IP_RATE_LIMIT_RPM` | Max `POST /api/auth/resend-verification` requests per IP per minute | `5` |

The verification link's base URL reuses the existing `APP_SERVER_URL` (`app.server-url`)
rather than introducing a separate `APP_BASE_URL`.

### Mailtrap example

```env
EMAIL_SMTP_HOST=sandbox.smtp.mailtrap.io
EMAIL_SMTP_PORT=2525
EMAIL_SMTP_USERNAME=<mailtrap inbox username>
EMAIL_SMTP_PASSWORD=<mailtrap inbox password>
EMAIL_FROM=no-reply@pushpage.link
```

Swap to a live sending domain (Mailtrap Sending, Resend, SES, ...) later by changing
only these values.

## Activating

Set `SPRING_PROFILES_ACTIVE=cloud` on the `publisher` service (see `docker-compose.yml`)
and pass the `EMAIL_*` vars through. Without the `cloud` profile active, none of this
code runs — `UserLifecycleHooks` falls back to the no-op and signup/login behavior is
exactly as without this feature.
