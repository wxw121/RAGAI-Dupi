# Security and Governance

Interactive sessions use an HttpOnly cookie with SameSite=Lax. State-changing browser requests require a CSRF token. API tokens are hashed with PBKDF2 and can be revoked by increasing the account token version.

ADMIN manages accounts, roles, password resets, and quality policies. OPS_ADMIN can inspect governance summaries, run recovery operations, and review audit evidence. MEMBER can use only assigned knowledge bases and cannot manage accounts or recovery.

Audit logs are retained for 180 days. Security webhook payloads are signed with HMAC-SHA256. Login failures are rate-limited by tenant, user, and source address.

Release scanning includes dependency vulnerabilities, container vulnerabilities, an SBOM, and license checks. An unfixed CRITICAL vulnerability blocks release. A HIGH vulnerability requires a documented exception that expires within 14 days.

Secrets must be supplied through environment or secret-manager integration. Default database, object-store, and session secrets are rejected in production mode.
