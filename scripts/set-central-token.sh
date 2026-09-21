#!/usr/bin/env bash
# Loads the Central Portal token into the GitHub repo, but only after checking it works.
#
# Both halves must come from the SAME token: generating a new one changes both, so
# replacing just one leaves a pair that will never authenticate.
#
#   ./scripts/set-central-token.sh
set -euo pipefail

REPO="${REPO:-luigivis/jev-sdk-java}"
NAMESPACE="com.luigivismara"
ARTIFACT="jev-sdk-java"

die() { printf '\n  \033[31merror:\033[0m %s\n\n' "$1" >&2; exit 1; }

command -v gh >/dev/null || die "gh is not installed: https://cli.github.com"
gh auth status >/dev/null 2>&1 || die "not logged in: run 'gh auth login'"

cat <<'INTRO'

Central Portal token
--------------------
Generate one at https://central.sonatype.com -> your account -> Generate User Token.
It prints an XML block once:

    <username>IGPNf2</username>                              <- short
    <password>NzanJns...</password>                          <- long

Copy each half into the matching prompt. Both must come from the same generation.

INTRO

read -rp  "  username: " USERNAME
read -rsp "  password: " PASSWORD; echo

[ -n "$USERNAME" ] || die "username is empty"
[ -n "$PASSWORD" ] || die "password is empty"

if [ "${#USERNAME}" -gt "${#PASSWORD}" ]; then
    printf '\n  \033[33mwarning:\033[0m the username is longer than the password, which is the\n'
    printf '           other way round from what the Portal prints. Swapped?\n'
fi

printf '\n  checking against the Portal... '
AUTH=$(printf '%s:%s' "$USERNAME" "$PASSWORD" | base64 -w0)
STATUS=$(curl -s -o /dev/null -w '%{http_code}' \
    -H "Authorization: Bearer $AUTH" \
    "https://central.sonatype.com/api/v1/publisher/published?namespace=$NAMESPACE&name=$ARTIFACT&version=0.1.0")
unset AUTH

case "$STATUS" in
    401|403)
        printf 'HTTP %s\n' "$STATUS"
        unset USERNAME PASSWORD
        die "Central rejected this pair. Nothing was written to GitHub.
  Generate a fresh token and use BOTH halves it prints - replacing only one
  half of an older token can never work."
        ;;
    200|404)
        printf 'HTTP %s, accepted\n' "$STATUS"
        ;;
    *)
        printf 'HTTP %s\n' "$STATUS"
        die "unexpected response from the Portal; nothing was written to GitHub"
        ;;
esac

printf '%s' "$USERNAME" | gh secret set CENTRAL_TOKEN_USERNAME --repo "$REPO"
printf '%s' "$PASSWORD" | gh secret set CENTRAL_TOKEN_PASSWORD --repo "$REPO"
unset USERNAME PASSWORD
printf '  ✓ CENTRAL_TOKEN_USERNAME\n  ✓ CENTRAL_TOKEN_PASSWORD\n\n'

gh secret list --repo "$REPO" | sed 's/^/  /'

cat <<EOF

Both halves verified and stored. Retry the release with:

  gh run rerun --failed --repo $REPO

EOF
