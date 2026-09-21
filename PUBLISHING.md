# Publishing

Releases are cut by GitHub Actions, not from a laptop. Nothing here needs a `settings.xml` or a
GPG key on your machine — [`.github/workflows/release.yml`](.github/workflows/release.yml) builds
one inside the runner from four repository secrets.

## One-time setup

Run this once, **on the machine that holds your GPG signing key**:

```bash
./scripts/setup-secrets.sh
```

It finds the key, exports it straight down a pipe to `gh` without writing it to disk, prompts for
the three remaining values without echoing them, and prints what ended up configured. Nothing lands
in your shell history or on screen.

If you would rather do it by hand, the four secrets go under **Settings → Secrets and variables →
Actions → New repository secret**:

| Secret | What it is |
|---|---|
| `MAVEN_GPG_PRIVATE_KEY` | The armoured private signing key, `-----BEGIN PGP PRIVATE KEY BLOCK-----` and all |
| `MAVEN_GPG_PASSPHRASE` | That key's passphrase |
| `CENTRAL_TOKEN_USERNAME` | User token name from the Central Portal |
| `CENTRAL_TOKEN_PASSWORD` | User token password from the Central Portal |

### The signing key — already done

A dedicated RSA 4096 key was generated for this project and is already loaded:

```
D7D2D7C2A13E3D8D
6AE0F072A240AD6EA7EB8905D7D2D7C2A13E3D8D
Luigi Vismara (Maven Central signing key) <luigi@envi.lat>
```

`MAVEN_GPG_PRIVATE_KEY` and `MAVEN_GPG_PASSPHRASE` are set, and the public half is on
keyserver.ubuntu.com, keys.openpgp.org and pgp.mit.edu so Central can verify the signatures.

The key has **no passphrase**, which is the usual shape for a key that only ever signs in CI:
its security is the GitHub secret, not a phrase, and there is no passphrase to leak or lose.
`MAVEN_GPG_PASSPHRASE` is set to an empty string to match.

It is a project-specific key, separate from whatever signed the earlier `com.luigivismara`
packages. If it is ever exposed, revoke it and generate a new one — nothing else depends on it:

```bash
gpg --gen-revoke D7D2D7C2A13E3D8D | gpg --keyserver keyserver.ubuntu.com --send-keys
```

The revocation certificate generated alongside the key lives in
`~/.gnupg/openpgp-revocs.d/6AE0F072A240AD6EA7EB8905D7D2D7C2A13E3D8D.rev`. Keep it somewhere you
can still reach if the private key is lost.

To recreate the same setup elsewhere, `./scripts/setup-secrets.sh` finds the key and loads it.

The public half must be on a keyserver for Central to verify the signature. It already is if the
other packages published successfully; if not:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

### The Portal token — the only step left

Generate it at [central.sonatype.com](https://central.sonatype.com) → your account → **Generate
User Token**. It prints a `<username>` and `<password>` pair once.

```bash
./scripts/set-central-token.sh
```

It prompts for both halves, checks them against the Portal, and only writes them to GitHub if
they authenticate — so a bad token fails in two seconds here instead of after a full release
build.

**Both halves must come from the same token.** Generating a new one changes the username *and*
the password; replacing only one leaves a pair that can never authenticate, which looks exactly
like a revoked token. The Portal prints a short username and a long password — if yours are the
other way round, they got swapped.

A token pasted into a chat, an issue or a commit is a leaked token: revoke it in the Portal and
generate a new one rather than reusing it.

## Cutting a release

The tag must match the version in `pom.xml` — the workflow refuses to publish otherwise, because
**Maven Central is immutable**: a published version can never be deleted, overwritten or fixed.

```bash
git tag v0.1.0
git push origin v0.1.0
```

The workflow then runs the full test suite, signs the artifacts and uploads a deployment to the
Central Portal. `autoPublish` is `false` in `pom.xml`, so the deployment waits in **Publish** →
**Deployments** for you to review and release it by hand. Once you trust the pipeline, flip
`autoPublish` to `true` and the tag becomes the whole release.

## Bumping the version

```bash
mvn versions:set -DnewVersion=0.2.0 -DgenerateBackupPoms=false
```

Commit that, then tag `v0.2.0`.

## What the live tests need

`mvn test` skips the tests in `JevLiveApiTest` unless `JEV_API_KEY` is set, which is why CI needs
no key and costs nothing. To run them locally:

```bash
JEV_API_KEY=... mvn test
```

Do not add that key to CI secrets unless you want every push billed against it.
