# Lite-MC mobile product boundary

The Android product uses its own `com.litemc.launcher.LiteActivity` and bundled
`assets/litemc/` interface. Amethyst/Pojav remains an attributed runtime dependency,
not the launcher UI. Do not present the old rebranded APK as this implementation.

## Service contract

All methods run on a native worker, never the UI thread. Use org.json. Never return
tokens, raw authentication responses, secrets, or exception stacks to JavaScript.

`LiteAccounts(Context)` exposes `JSONObject handle(String action, JSONObject args)`,
`JSONObject snapshot()`, and `MinecraftAccount launchAccount()` (native only).

- `accounts.offline {name}`: validate 3–16 ASCII letters/digits/underscore; deterministic UUID, no network.
- `accounts.config {clientId}`: own registered Microsoft public client ID; no borrowed upstream ID.
- `accounts.login.start {}`: device authorization; return flowId, userCode, verificationUri, interval.
- `accounts.login.poll {flowId}`: pending or successful sanitized account snapshot.
- `accounts.logout {}`: revoke local persistent session.
- `accounts.skin {base64,model}`: upload 64x64 or legacy 64x32 PNG, classic/slim.
- snapshot: mode, name, uuid, skinUrl, model, hasSession, clientId. Optional extra safe fields allowed.

`LiteMods(Context)` exposes `JSONObject handle(String action, JSONObject args)`.

- `mods.search {provider,query,version,loader}` returns `{items:[{id,title,description,downloads}]}`.
- `mods.install {provider,projectId,version,loader,instanceId}` returns `{installed:[filename]}`.
- `mods.list {instanceId}` returns `{items:[{name,size}]}`.
- `mods.config {curseforgeKey}`: persist privately encrypted, never return key.

Providers are modrinth/curseforge, loaders vanilla/fabric initially. Fail clearly
for vanilla incompatible mods. Instances live under
`new File(Tools.DIR_GAME_NEW, "lite-instances/" + instanceId)`; instanceId must
match `[A-Za-z0-9._-]{1,100}` and must not contain `..`. Validate canonical containment.
Downloads must check compatibility, required dependencies, safe filenames, hashes,
and use a temporary file before replacing the final path.

`LiteNetwork`: implementation may select static HTTPS APIs, root adapts as needed.
Use finite connect/read timeouts, bounded responses, allowlisted URLs and redirects.

Root owns runtime account handoff in private Keystore-encrypted storage. It adapts
PojavProfile internally without calling upstream plaintext account.save(). All
account secrets remain native. A read from the game process must not refresh tokens.

## Verification

Compilation is not successful gameplay. Microsoft login needs an own registered
client and an interactive user; CurseForge requires a permitted API key. Report
those live tests as unverified when credentials/user interaction are unavailable.
