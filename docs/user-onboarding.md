# Add an application user

A person needs an identity-provider account and personal Job Hunter preferences.
The API creates its own user row lazily when that person first saves preferences
or imports a candidate profile. Do not create users with database scripts.

## Identity and access

1. Confirm the exact Google account email with the person; the CV contact address
   may be different. For a new identity, use the confirmed login email. For an
   existing profile, preserve its canonical email and bind a different confirmed
   Google email with the admin-only `google_login_email` attribute. Use the
   infrastructure repository's `scripts/job-hunter-member.py` (dry-run, then
   `--apply`); its procedure is in `terraform/authentik/config/applications/README.md`.
   It provisions only the `job-hunter-member` group. The existing `job-hunter-user` group
   is reserved for the owner/operator; it includes `manage:jobs` and automation.
   Do not add infrastructure, database, monitoring, or machine-client groups.
2. The person opens Job Hunter and selects sign-in. For an existing Gmail account,
   the Google sign-in button links the pre-provisioned account by verified email
   or its approved alias, without requiring a local password. Unknown Google
   accounts cannot self-register. Email/password access is also available
   through the existing password recovery flow after provisioning.
   A newly provisioned account has no local password. For email/password access,
   let the person set one through recovery before treating that login path as ready.
   Entering an email alone does not authenticate the person: Google proves account
   ownership, while the local login path requires a password.
3. Verify the resulting token has personal job/preferences/settings permissions,
   with no `manage:jobs`, `read:criteria`, `read:proxies`, or automation permissions.
   Check that personal preferences are empty and the automation screen is absent.
   Verify a real first sign-in through the selected login method. Administrative
   impersonation checks authorization and data access, but does not verify Google
   account matching or password authentication.

The current SPA provider uses email as its OIDC subject. Changing the account email
changes the application's identity, so treat an email change as a migration rather
than silently relinking personal data. Provider sessions and refresh tokens are
configured for 30 days in both password and Google authentication flows; explicit
logout or provider revocation can end them sooner. Short sessions issued before
the configuration fix need a fresh login to receive the longer lifetime.

## Search and matching

Save About and Search Criteria in Settings. For a LinkedIn-only user, enable
`linkedin` and disable `djinni`, `dou`, `euremotejobs`, `justjoinit`, `landingjobs`,
`nofluffjobs`, and `web3career`. Search categories are literal LinkedIn query terms,
so nontechnical roles can use terms such as `administrative assistant`,
`operations assistant`, `virtual assistant`, and `customer support`.
Generate Preferences uses target role titles for nontechnical profiles and core
technologies for software profiles. General office and communication tools remain
profile skills rather than search categories unless a specialist role is requested.
Review generated suggestions before saving them.

The scraper combines search criteria from users that enable the requested source.
Collection and vacancies are shared; personal matches, review statuses, candidate
profiles, provider configuration, and notifications belong to each user. Adding a
LinkedIn-only user does not disable any source for the owner. A posting marked
remote can still restrict the countries where the applicant may work; search
locations do not establish eligibility.

A new user has no AI provider chain. Disable AI matching until they configure a
provider, otherwise matching cannot produce results for that user. Cold matching
checks source, categories, explicit on-site flags, and exclusions; it does not
score eligibility or infer remote work from the description. Unknown remote flags
can pass the cold filter, so inspect the posting before applying. Cold-only scores
are zero: the dashboard's minimum-score filter must include zero.

Fresh collection uses a one-hour window. Previously processed vacancies are not
automatically replayed for a new user. An operator may call
`POST /jobs/rematch?since=<timestamp>` with `manage:jobs` for at most the last three
days. This re-evaluates all users and can consume their configured AI capacity;
do not present it as a personal backfill or reset review statuses. Otherwise the
new user's dashboard fills from subsequent successful scraping runs.

## Acceptance checks

- Both subjects retain their own preferences, candidate profiles, and job statuses.
- The new user's criteria appear for LinkedIn and are absent from disabled sources.
- The new user's job list does not contain the owner's private matches.
- Personal `write:jobs` cannot call ingest/check, scraping mutations, or global rematch.
- The machine client retains both `write:jobs` and `read:criteria` for ingestion and leases.
- Initial expired access tokens renew once from the saved refresh credential;
  a temporary failure retains it, and a rejected refresh grant requires sign-in.
