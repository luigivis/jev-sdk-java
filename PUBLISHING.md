# Publishing

Releases are cut by GitHub Actions, not from a laptop. Nothing here needs a `settings.xml` or a
GPG key on your machine — [`.github/workflows/release.yml`](.github/workflows/release.yml) builds
one inside the runner from four repository secrets.

## One-time setup

Add four secrets under **Settings → Secrets and variables → Actions → New repository secret**, or
with `gh` from the machine that already holds your signing key. Piping from a file keeps the values
out of your shell history:

| Secret | What it is |
|---|---|
| `MAVEN_GPG_PRIVATE_KEY` | The armoured private signing key, `-----BEGIN PGP PRIVATE KEY BLOCK-----` and all |
| `MAVEN_GPG_PASSPHRASE` | That key's passphrase |
| `CENTRAL_TOKEN_USERNAME` | User token name from the Central Portal |
| `CENTRAL_TOKEN_PASSWORD` | User token password from the Central Portal |

### The signing key

You already have one — it signed the other packages under
[`com.luigivismara`](https://central.sonatype.com/namespace/com.luigivismara). On that machine:

```bash
gpg --list-secret-keys --keyid-format=long          # find the key id
gpg --armor --export-secret-keys <KEY_ID> > jev-signing-key.asc
gh secret set MAVEN_GPG_PRIVATE_KEY --repo luigivis/jev-sdk-java < jev-signing-key.asc
rm jev-signing-key.asc                               # do not leave it lying around
```

The public half must be on a keyserver for Central to verify the signature. It already is if the
other packages published successfully; if not:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys <KEY_ID>
```

### The Portal token

Generate it at [central.sonatype.com](https://central.sonatype.com) → your account → **Generate
User Token**. It prints a `<username>` and `<password>` pair once.

```bash
gh secret set CENTRAL_TOKEN_USERNAME --repo luigivis/jev-sdk-java
gh secret set CENTRAL_TOKEN_PASSWORD --repo luigivis/jev-sdk-java
gh secret set MAVEN_GPG_PASSPHRASE   --repo luigivis/jev-sdk-java
```

Each prompts for the value and reads it without echoing.

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
