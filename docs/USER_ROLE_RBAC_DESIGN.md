# User Management, 3-Tier RBAC & Single-Use Invites

**Implemented:** 2026-11-26  
**Branch:** `feat/user-rbac-and-invites`  
**Scope:** `com.currencyanalyzer.auth`, `com.currencyanalyzer.web`, `AiConversationController`, `UserAlertRuleController`, `db/migration/V9__user_roles_and_invites.sql`, `static/`

---

## 1. Problem Statement

When deploying to Cloud Run with `AUTH_REGISTRATION_ENABLED=false` (to prevent arbitrary anonymous signups), `react_login.js` hides the **Register** tab and `POST /api/auth/register` returns `403 Forbidden`. Previously, the `users` table only held `(id, username, password, created_at)` with no role column or admin user-management endpoints, leaving no supported API/UI workflow to onboard non-admin accounts.

---

## 2. 3-Tier RBAC Permission Matrix

| Capability | `ADMIN` | `MEMBER` | `VIEWER` |
|---|---|---|---|
| Markets, Live Rates, Historical Charts, Forecast, Knowledge Search (read) | Allowed | Allowed | Allowed |
| Self-service Password Change (`POST /api/auth/change-password`) | Allowed | Allowed | Allowed |
| Personal Alerts 2.0 Rules & Inbox Read (`GET /api/alert-rules`, `/api/alert-inbox`) | Allowed | Allowed | Allowed |
| Personal Alerts 2.0 Mutations (`POST`/`PUT`/`DELETE /api/alert-rules`, `/api/alert-inbox/*`) | Allowed | Allowed | **403 Forbidden** |
| Ask AI Assistant (`/api/ai/conversations/**`, DeepSeek + RAG + Tools) | Allowed | Allowed | **403 Forbidden** |
| Global Monitoring Controls & System Settings (`/api/monitoring/start|stop`, `POST /api/settings`, `POST /api/test-email`, `POST /api/alerts/preferences`, `DELETE /api/alerts/clear`) | Allowed | **403 Forbidden** | **403 Forbidden** |
| Shared RAG Corpus Mutations (`POST`/`DELETE /api/rag/documents*`, `POST /api/rag/sync`) | Allowed | **403 Forbidden** | **403 Forbidden** |
| Users & Invites Administration (`/api/admin/users/**`, `/api/admin/invites/**`) | Allowed | **403 Forbidden** | **403 Forbidden** |

---

## 3. Schema Design (`V9__user_roles_and_invites.sql`)

- **`users` extensions:**
  - `role VARCHAR(16) NOT NULL DEFAULT 'MEMBER'` (`ADMIN`, `MEMBER`, `VIEWER`)
  - `status VARCHAR(16) NOT NULL DEFAULT 'ACTIVE'` (`ACTIVE`, `DISABLED`)
  - `display_name VARCHAR(120)`
  - `last_login_at TIMESTAMP WITH TIME ZONE`
  - Existing deployments automatically promote `LOWER(username) = 'admin'` or the oldest user (`MIN(id)`) to `ADMIN` during migration.
- **`user_invites` table:**
  - Stores single-use onboarding invites with `token_hash VARCHAR(64) NOT NULL UNIQUE` (SHA-256 hex of the normalized token), `code_prefix VARCHAR(16)` for admin display (`CA-XXXX-...`), `role`, `note`, `created_by`, `expires_at`, `used_by`, `used_at`, and `revoked_at`.
  - Raw invite codes are returned **once** at creation time and are never stored in plaintext.

---

## 4. Onboarding Flows & Security Invariants

1. **Admin Direct Provisioning (`POST /api/admin/users`):**
   - Admins create accounts directly inside **Settings → Users & Access Management**, assigning `ADMIN`, `MEMBER`, or `VIEWER`, an optional display name, and an initial password (`>= 6` characters).
2. **Single-Use Expiring Invite Codes (`POST /api/admin/invites` → `POST /api/auth/register`):**
   - Admins generate an expiring single-use code (`CA-XXXX-XXXX-XXXX`, configurable `1–720` hours, default `72h`) and share either the code or `/login.html?invite=...` URL.
   - Invited users redeem it on `login.html` (`Have an invite code? Create account`) even when `AUTH_REGISTRATION_ENABLED=false`.
   - Redemption uses an atomic conditional SQL update (`WHERE id = :id AND used_at IS NULL AND revoked_at IS NULL AND expires_at > :now`) inside `@Transactional` so two concurrent signups cannot reuse one invite.
3. **Live Session Kill-Switch & Anti-Lockout (`AccountStatusFilter`, `UserManagementService`):**
   - `AccountStatusFilter` checks active DB status and effective role on `/api/**` requests: disabling an account immediately invalidates its active session (`403 Forbidden`), and role promotions/demotions apply on the very next request.
   - Admins cannot demote or disable their own account, and the system refuses any mutation that would leave zero active `ADMIN` users.

---

## 5. Operator Runbook

1. Sign in as an `ADMIN` account (your existing Cloud SQL owner or any account listed in `RAG_ADMIN_USERS`).
2. Open **Settings** from the left navigation bar:
   - **To add a user immediately:** Use **Create Account**, pick `MEMBER` or `VIEWER` (or `ADMIN`), enter username and password, and click **Create User**.
   - **To let a teammate choose their own password:** Use **Generate Invite Code**, pick the target role and expiry, and share the copied code or `/login.html?invite=...` link.
   - **To change roles, disable access, or reset a forgotten password:** Edit the user row in the **Users Directory** table.
   - **To update your own password:** Use the **My Account & Password** card (available to every role).
