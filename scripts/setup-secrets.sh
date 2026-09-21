#!/usr/bin/env bash
# Loads the four release secrets into the GitHub repo.
#
# Run this on the machine that holds your GPG signing key. Values are read without
# echoing and piped straight to `gh`: nothing lands in your shell history, in a file,
# or on screen.
#
#   ./scripts/setup-secrets.sh
set -euo pipefail

REPO="${REPO:-luigivis/jev-sdk-java}"

die() { printf '\n  error: %s\n\n' "$1" >&2; exit 1; }
step() { printf '\n\033[1m%s\033[0m\n' "$1"; }

command -v gh  >/dev/null || die "gh is not installed: https://cli.github.com"
command -v gpg >/dev/null || die "gpg is not installed"
gh auth status >/dev/null 2>&1 || die "not logged in: run 'gh auth login'"

printf '\nLoading release secrets into \033[1m%s\033[0m\n' "$REPO"

# --- 1. the signing key ------------------------------------------------------------
step "1/4  GPG signing key"

mapfile -t KEY_IDS < <(gpg --list-secret-keys --with-colons 2>/dev/null \
    | awk -F: '$1 == "sec" { print $5 }')

[ "${#KEY_IDS[@]}" -gt 0 ] || die "no secret GPG key on this machine.
  Maven Central requires signed artifacts. Run this script on the machine that
  published the other com.luigivismara packages, where the key already lives."

if [ "${#KEY_IDS[@]}" -eq 1 ]; then
    KEY_ID="${KEY_IDS[0]}"
    printf '  using the only secret key: %s\n' "$KEY_ID"
else
    printf '  several secret keys found:\n'
    gpg --list-secret-keys --keyid-format=long | sed 's/^/    /'
    read -rp "  key id to sign with: " KEY_ID
fi

# Never written to disk: the armoured key goes straight down the pipe.
gpg --armor --export-secret-keys "$KEY_ID" \
    | gh secret set MAVEN_GPG_PRIVATE_KEY --repo "$REPO" \
    || die "could not export or upload the key"
printf '  ✓ MAVEN_GPG_PRIVATE_KEY\n'

printf '  reminder: the PUBLIC half must be on a keyserver for Central to verify it.\n'
printf '            if it is not:  gpg --keyserver keyserver.ubuntu.com --send-keys %s\n' "$KEY_ID"

# --- 2. its passphrase -------------------------------------------------------------
step "2/4  GPG passphrase"
read -rsp "  passphrase for $KEY_ID (empty if the key has none): " GPG_PASS; echo
printf '%s' "$GPG_PASS" | gh secret set MAVEN_GPG_PASSPHRASE --repo "$REPO"
unset GPG_PASS
printf '  ✓ MAVEN_GPG_PASSPHRASE\n'

# --- 3 & 4. the Central Portal token ----------------------------------------------
step "3/4  Central Portal token"
printf '  Generate one at https://central.sonatype.com -> your account -> Generate User Token\n'
printf '  It prints a username/password pair once.\n\n'
read -rp  "  token username: " CENTRAL_USER
printf '%s' "$CENTRAL_USER" | gh secret set CENTRAL_TOKEN_USERNAME --repo "$REPO"
unset CENTRAL_USER
printf '  ✓ CENTRAL_TOKEN_USERNAME\n'

step "4/4  Central Portal token password"
read -rsp "  token password: " CENTRAL_PASS; echo
printf '%s' "$CENTRAL_PASS" | gh secret set CENTRAL_TOKEN_PASSWORD --repo "$REPO"
unset CENTRAL_PASS
printf '  ✓ CENTRAL_TOKEN_PASSWORD\n'

# --- verify ------------------------------------------------------------------------
step "Configured secrets"
gh secret list --repo "$REPO" | sed 's/^/  /'

VERSION=$(grep -m1 -oP '(?<=<version>)[^<]+' pom.xml 2>/dev/null || echo "<version>")
cat <<EOF

Done. To cut the release:

  git tag v$VERSION && git push origin v$VERSION

The workflow signs and uploads a deployment; autoPublish is false, so it waits in the
Central Portal under Publish -> Deployments for you to release it by hand.

EOF
