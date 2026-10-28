# Password reset

Forgotten passwords can be reset with or without email. There is one reset-token mechanism
and two ways to deliver the link:

| `EMAIL_ENABLED` | How the user gets a reset link |
|-----------------|--------------------------------|
| `true` | **Self-service.** The login page shows "Forgot password?" and emails a link. |
| `false` | **Operator-delivered.** An admin creates a link in the dashboard (or via the API) and passes it to the user. A locked-out admin can use `ADMIN_RECOVERY`. |

The admin-issued link and `ADMIN_RECOVERY` work in both modes. The login page decides which
hint to show from `features.email` in [`GET /api/config`](api.md#get-apiconfig).

## Redeeming a link

Every link looks like `{APP_SERVER_URL}/dashboard?reset_token=<token>`. The dashboard moves the
token out of the address bar, shows a "choose a new password" form, and calls
`POST /api/auth/reset-password`. A successful reset:

- sets the new password (minimum 8 characters, stored as a BCrypt hash);
- consumes the token and removes any other outstanding token for the user;
- **signs the user out everywhere**: every JWT issued before the reset is rejected from then
  on (see "Session invalidation");
- leaves API keys untouched;
- marks the email as verified if the link was delivered by email (the user just proved they own
  the mailbox). Admin-issued links do not.

## Self-service reset (`EMAIL_ENABLED=true`)

`POST /api/auth/forgot-password` with `{ "email": "..." }`:

- always answers `202`, whether or not an active account has that address, so it can't be used
  to discover registered emails;
- sends the email on a separate thread, so response time doesn't give the answer away either;
- is rate-limited per IP (`EMAIL_PASSWORD_RESET_IP_RATE_LIMIT_RPM`, `429` with `Retry-After`) and
  per account (`EMAIL_PASSWORD_RESET_RATE_LIMIT_PER_MINUTE`, extra requests are silently ignored).
  nginx additionally limits the endpoint per IP (see `docs/configuration.md`).

Deactivated accounts get no email.

## Operator-delivered reset (any mode)

### Admin creates a link

Dashboard → **Users** → **Reset password** next to a user shows a one-time link with a copy
button. The API equivalent is `POST /api/admin/users/{id}/reset-password`, which returns
`{ "reset_link": "...", "expires_at": "..." }`. Creating a new link replaces the previous one.
Admins can create links for any active user. They cannot see the user's password, only hand
over a way to choose a new one.

### Locked-out admin (`ADMIN_RECOVERY`)

If no admin can sign in and there is no email, start the service with `ADMIN_RECOVERY=true`.
At startup it logs a one-time reset link for every active admin:

```
ADMIN_RECOVERY is enabled. Remove it once you have regained access.
Password reset link for admin@pushpage.link (expires 2026-10-28T12:00:00Z):
https://pushpage.example/dashboard?reset_token=...
```

Open the link, set a password, then **remove `ADMIN_RECOVERY`** and restart: every start with the
flag issues fresh links. This has the same trust model as the bootstrap credentials: anyone who
can read the service logs can already control the deployment.

## Session invalidation

JWTs carry a `pwd_v` claim: the user's `password_changed_at` in epoch milliseconds (`0` if the
password never changed). The auth filter rejects a JWT whose `pwd_v` differs from the user's
current value, so a reset (or any future password change) ends all existing sessions, with no
timing window. JWTs issued before this claim existed count as version `0` and stay valid until
the user's password changes.

## Security properties

- Tokens are 256 bits from `SecureRandom`; only their SHA-256 hash is stored.
- Single use (redeemed atomically with `DELETE … RETURNING`), expire after
  `PASSWORD_RESET_TOKEN_TTL_MINUTES` (default 60), and at most one is valid per user.
- Expired tokens are purged by a scheduled job (same schedule as page cleanup).
- Deactivated users cannot redeem a token.
- Tokens are never logged, except the deliberate `ADMIN_RECOVERY` startup log.

## Configuration

| Variable | Description | Default |
|----------|-------------|---------|
| `PASSWORD_RESET_TOKEN_TTL_MINUTES` | How long a reset link stays valid (emailed and admin-issued). | `60` |
| `EMAIL_PASSWORD_RESET_RATE_LIMIT_PER_MINUTE` | Max reset emails per account per minute (`EMAIL_ENABLED` only). | `1` |
| `EMAIL_PASSWORD_RESET_IP_RATE_LIMIT_RPM` | Max `forgot-password` requests per IP per minute (`EMAIL_ENABLED` only). | `5` |
| `ADMIN_RECOVERY` | Log a reset link for every active admin at startup. Temporary break-glass. | `false` |

SMTP settings are shared with sign-up verification; see [`email-verification.md`](email-verification.md).

## Code map

| Piece | Location |
|-------|----------|
| Token issue/redeem, admin link | `service/PasswordResetService`, `repository/PasswordResetRepository` |
| `POST /auth/reset-password` | `controller/AuthController` |
| `POST /admin/users/{id}/reset-password` | `controller/AdminController` |
| Emailed flow (`EMAIL_ENABLED`) | `email/service/PasswordResetEmailService`, `email/controller/ForgotPasswordController`, `email/sender/*`, `resources/email/password-reset-email.html` |
| Startup recovery | `service/AdminRecoveryRunner` |
| Session invalidation | `service/JwtClaims`, `service/JwtService`, `filter/ApiKeyAuthFilter` |
| Schema | `V1002__add_password_reset.sql` |
