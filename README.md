# Fleet Worker for Android

Separate package `local.hwak.fleetworker`; no Activity, launcher replacement,
media commands or Quiet TV modifications. It runs an owner-enabled foreground
service (`specialUse`) and restarts through BOOT_COMPLETED/MY_PACKAGE_REPLACED.
The APK contains no native libraries and runs on the verified ARM32 TV.

## Build

Use a **full** JDK 21 (the system JRE alone lacks javac), Android SDK 36 and the
included Gradle wrapper:

```
JAVA_HOME=/path/to/jdk21 ./gradlew :app:assembleRelease :app:testReleaseUnitTest :app:lintRelease \
  -PworkerVersionCode=7 -PworkerVersionName=0.3.1
```

Builds require no production configuration or secrets. `preparePublicAssets`
creates the packaged assets from the tracked safe template and the public RSA
key. The legacy ignored `app/src/main/assets/release-sources.json` is explicitly
excluded, and environment configuration is not a build input. Run
`:app:verifyPublicApk` to check that the APK contains exactly the public template.

Provision private release endpoints after installation with the DUMP-protected
`CONFIGURE_RELEASES` action. An operator may keep its JSON locally in ignored
`.local/release-sources.json`; that directory is never packaged. Runtime override
values stay in the application's private preferences and are not returned by
status. Per-device worker credentials are provisioned separately.

The output `app/build/outputs/apk/release/app-release-unsigned.apk` must be signed
with the same application certificate for every release. The pinned RSA release
public key in `assets/release-public.der` signs the release manifest separately.
Private signing material and per-device credentials stay outside this repository.
`dist/` is an ignored local handoff directory for local unsigned APK handoff builds.

## Bootstrap and control

The owner approves the first installation manually. Deployment is handled by the
main operator; this project does not silently initiate a first install.

`ControlReceiver` is exported **under android.permission.DUMP**, available to an
authorized ADB shell. It accepts explicit broadcasts with these actions:

- `local.hwak.fleetworker.CONFIGURE`: extras `base_url` (HTTPS including optional
  prefix), `worker_id`, `token`. Credentials are written to private preferences;
  neither status nor logs returns the token.
- `local.hwak.fleetworker.CONFIGURE_RELEASES`: extra `release_sources` containing
  the public source array below. The entire chain is validated and saved locally;
  credentials, request headers, HTTP URLs and a fourth source are refused. This
  control is protected by the same DUMP permission.
- `local.hwak.fleetworker.STATUS`: ordered-broadcast result data and `status_json`
  extras contain sanitized state/version/current job/outbox/update status.
- `local.hwak.fleetworker.RELOAD`: reloads the saved identity and wakes polling.
- `local.hwak.fleetworker.UPDATE_CHECK`: requests a release check at the next safe
  idle point, after durable results have been delivered.
- `local.hwak.fleetworker.CANCEL`: explicitly cancels the current script.

`WorkerService` is also DUMP-protected for an explicit shell foreground-service
start if Android restricts a start from the configuration receiver. No Activity
or permission prompt is launched. POST_NOTIFICATIONS denial does not stop the
worker; the mandatory foreground notification is still supplied to Android.

## Jobs and durability

API paths follow `../docs/api-contract.md`. Each accepted claim is committed to
SQLite (`synchronous=FULL`, WAL) before execution. The result and removal of the
current job are committed together. Results remain in an outbox until a matching
`job_id`/`attempt_id` receipt confirms `committed` or `candidate_saved`. The next
claim is not taken while an outbox result is unacknowledged. Replacing worker ID
or API base is refused while work remains; token rotation is allowed.

Operations:

- `sha256`: UTF-8 `input.text` → `{ "sha256": "hex" }`.
- `text.normalize`: Unicode NFC, collapse Unicode whitespace, trim → `{ "text": "..." }`.
- `javascript`: Rhino **1.9.1**, interpreter-only, `run(input) -> JSON`.
  The optional second argument `run(input, resume)` receives the last local
  checkpoint. `checkpoint(value)` synchronously persists JSON; `progress(value)`
  updates heartbeat progress. Checkpoints are also sent in heartbeat progress.

Rhino safe standard objects, a deny-all ClassShutter and a rejecting Java object
wrapper prevent Java reflection/packages/worker credential access. Inputs and
results cross the boundary through JSON, not Java wrappers. No Java object, HTTP
client, filesystem or credential is exposed to scripts. Instruction observation
checks explicit cancellation; it does not impose a total execution deadline.
Stack/heap exhaustion produces a failed completion when the runtime can recover.
Leases are renewed separately from execution; lost lease cancels further work,
and a completed stale result is still delivered for server-side candidate storage.

New claims pause on Android's actual low-memory/thermal-pressure signals. The
worker has one execution slot and background thread priority. A physical TV's
sleep/process-kill policy still controls availability; durable work is resumed
rather than claiming uninterrupted platform uptime.

The worker heartbeat may set `update_poll_seconds` live (30 for pilot, 900 for
normal polling). Job heartbeat may return `lease_until` and `cancel_requested`.
No APK restart is necessary to apply the polling policy.

## Release discovery and failover

The public template defines this ordered chain:

1. Unconfigured Gitea placeholder; skipped without a network request. After
   private provisioning this slot uses the operator's Gitea latest-release API.
2. GitFlic public `stable.json` channel on the `release-channel` branch.
3. GitHub public `releases/latest/download` asset links (no REST API polling).

Gitea selects the exact release assets `android-manifest.json` and
`fleet-worker-android.apk`; drafts, prereleases, missing or duplicate assets are
refused. GitHub uses its latest-release asset redirects directly, avoiding the
anonymous REST API rate limit shared by devices behind one public IP. GitFlic's public channel contains `manifest_url` and `apk_url`, pointing
to anonymous release downloads; the REST API's authentication token is never
needed on a device. All requests, including redirects, remain HTTPS. Public
release transport is separate from the authenticated worker API and never adds
its Bearer token. No repository PAT, device token or private signing key is
included in the APK.

A source fails over on discovery/network/HTTP errors, invalid metadata or RSA
signature, missing assets, transfer/hash failure, or APK identity/certificate
failure. The worker tries the next source in the same check; if all fail, it
records `sources_unavailable` and tries again on the next scheduled check.
Status exposes only source ID and failure stage/class, without response bodies,
credentials or URL query strings. A mirror publishing the installed version does
not end the check: later mirrors are checked for a newer release. If a newer
signed release is found but its artifact is unavailable, the check reports
unavailable rather than current, even if another mirror still publishes the
installed version.

Every mirror publishes the **same signed manifest bytes and APK**. The manifest's
signed canonical URL remains unchanged; a mirror's APK locator is only a transport
address. Its downloaded bytes must match the signed hash and installed certificate.
The highest RSA-authenticated version and manifest identity are persisted before
transfer. An older version or a different signed artifact with the same version
cannot be substituted by fallback, including after a restart. A broken signed
release therefore requires a corrected release with a new version code.

The old gateway endpoint is only used once by the installed 0.1.2 bootstrap to
deliver a release-chain-enabled worker. It is not a fourth source in the new
worker.

GitFlic channel example (locator metadata itself is untrusted):

```json
{
  "manifest_url": "https://gitflic.ru/project/OWNER/REPO/release/RELEASE/FILE/download",
  "apk_url": "https://gitflic.ru/project/OWNER/REPO/release/RELEASE/FILE/download"
}
```

Source array entries have `id`, `type`, `url` and optional `manifest_asset` /
`apk_asset`. A `manifest` source may supply an HTTPS `apk_url` transport locator
without changing the signed canonical URL; IDs must be exactly `gitea`, `gitflic`, `github` in that order.
Supported public adapters are `gitea`, `github`, `channel`, `manifest` and
`gitflic-api` (only for an endpoint actually offering anonymous metadata).
The template GitFlic adapter is `channel`. Reserved `.invalid` sources are
reported as `not_configured` and skipped without making an HTTP request. A
private runtime override restores the configured Gitea primary while preserving
the same GitFlic/GitHub fallback and signature checks.

Release API references: [Gitea latest release](https://docs.gitea.com/api/next/operations/repo-get-latest-release/),
[GitHub latest-release links](https://docs.github.com/en/repositories/releasing-projects-on-github/linking-to-releases),
[GitFlic API authentication](https://docs.gitflic.ru/latest/en/api/intro/).

## Silent self-update

Only a valid SHA256withRSA manifest under the pinned release key is accepted.
The APK SHA-256, package, versionCode, versionName and **same installed signing
certificate** are checked before the session is committed. Version downgrades
and foreign update owners are refused. The current pure-Java APK has no native
ABI payload; arbitrary future native-ABI compatibility is not asserted here.

Unattended installation requires Android 12/API31+ and a one-time owner-approved
`canRequestPackageInstalls()` permission/app-op. On older APIs or missing
permission the worker reports `blocked_*`, without opening Settings or an
installer screen. The API34 TV is the deployment target for the real proof.

PackageInstaller is explicitly set to USER_ACTION_NOT_REQUIRED. Session ID,
expected version and hash are persisted before commit. No new job starts during
an update session. Result callbacks must match the saved session and version.
STATUS_PENDING_USER_ACTION is recorded as blocked and the owned session is
abandoned; **EXTRA_INTENT is never launched**. Retry respects Android's documented
silent-install throttle. Boot/package replacement reconciles installed versions,
and successful updates remove only their own obsolete cached APKs.

Tests cover real SHA/Unicode processing, JavaScript JSON/checkpoint semantics,
blocked Java access, explicit cancellation of an otherwise unbounded script and
RSA signature tampering, ordered mirror failover at every transfer stage,
manifest/version conflicts and durable rollback refusal. Full PackageInstaller/SQLite acceptance is verified on
the owner's device after their initial-install approval, not inferred from JVM
unit tests.

Upstream runtime: https://github.com/mozilla/rhino (MPL-2.0).
