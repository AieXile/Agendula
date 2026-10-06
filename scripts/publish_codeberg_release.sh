#!/usr/bin/env bash
# Publishes TAG on Codeberg with the signed APK and its SHA-256 attached.
# Upserts, so re-runs are safe. A missing TOKEN skips a stable release but fails
# a pre-release, where Codeberg is the only channel.
#
# Env: TOKEN, API (.../api/v1/repos/<owner>/<repo>), TAG, SHA, PRERELEASE,
#      NOTES_FILE, APK
set -euo pipefail
if [ -z "${TOKEN:-}" ]; then
  if [ "${PRERELEASE:-}" = true ]; then
    echo "CODEBERG_RELEASE_TOKEN not set — a pre-release has nowhere else to go." >&2
    exit 1
  fi
  echo "CODEBERG_RELEASE_TOKEN not set — skipping Codeberg publish."
  exit 0
fi
: "${API:?}" "${TAG:?}" "${SHA:?}" "${PRERELEASE:?}" "${NOTES_FILE:?}" "${APK:?}"
if [ ! -f "$APK" ]; then echo "No release APK at $APK." >&2; exit 1; fi

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

ASSET_APK="agendula_${TAG}.apk"
ASSET_SUM="${ASSET_APK}.sha256"
cp "$APK" "$WORK/$ASSET_APK"
( cd "$WORK" && sha256sum "$ASSET_APK" > "$ASSET_SUM" )

# Push the tag first and create the release without target_commitish: a POST
# naming a commit Codeberg hasn't received yet 500s.
HOST=${API#https://}; HOST=${HOST%%/*}
REPO=${API#*/repos/}
git tag -f "$TAG" "$SHA"
git push -f "https://${REPO%%/*}:${TOKEN}@${HOST}/${REPO}.git" "refs/tags/$TAG"

python3 - "$TAG" "$PRERELEASE" "$NOTES_FILE" <<'PY' > "$WORK/payload.json"
import json, sys
tag, pre, notes = sys.argv[1:4]
print(json.dumps({
    "tag_name": tag,
    "name": tag,
    "body": open(notes).read(),
    "draft": False,
    "prerelease": pre == "true",
}))
PY

# Codeberg 500s on a freshly pushed tag for a few seconds, hence the retries.
ID=""
for attempt in 1 2 3 4 5 6; do
  EXIST=$(curl -s -H "Authorization: token $TOKEN" "$API/releases/tags/$TAG" | jq -r '.id // empty' 2>/dev/null || true)
  if [ -n "$EXIST" ]; then
    CODE=$(curl -s -o /dev/null -w '%{http_code}' -X PATCH \
      -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
      -d @"$WORK/payload.json" "$API/releases/$EXIST" || echo 000)
    echo "release PATCH HTTP $CODE"
    if [ "$CODE" = 200 ]; then ID="$EXIST"; break; fi
  else
    CODE=$(curl -s -o "$WORK/response.json" -w '%{http_code}' -X POST \
      -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
      -d @"$WORK/payload.json" "$API/releases" || echo 000)
    echo "release POST attempt $attempt HTTP $CODE"
    ID=$(jq -r '.id // empty' "$WORK/response.json" 2>/dev/null || true)
    [ -n "$ID" ] && break
  fi
  sleep $((attempt * 10))
done
if [ -z "$ID" ]; then echo "Could not create or update the Codeberg release." >&2; exit 1; fi

for A in "$ASSET_APK" "$ASSET_SUM"; do
  OLD=$(curl -s -H "Authorization: token $TOKEN" "$API/releases/$ID/assets" \
    | jq -r --arg n "$A" '.[]? | select(.name==$n) | .id' 2>/dev/null || true)
  for O in $OLD; do
    curl -s -o /dev/null -X DELETE -H "Authorization: token $TOKEN" "$API/releases/$ID/assets/$O" || true
  done
  CODE=$(curl -s -o /dev/null -w '%{http_code}' -X POST -H "Authorization: token $TOKEN" \
    -F "attachment=@$WORK/$A" "$API/releases/$ID/assets?name=$A" || echo 000)
  echo "asset $A HTTP $CODE"
  if [ "$CODE" != 201 ]; then echo "Uploading $A failed." >&2; exit 1; fi
done
echo "Published $TAG to Codeberg."
