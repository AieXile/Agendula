#!/usr/bin/env bash
# Publish TAG on Codeberg, which is canonical for tags and releases, with the
# signed APK and its SHA-256 checksum attached: the direct-download channel
# that Obtainium follows. Shared by .gitea/workflows/release.yaml (stable) and
# beta.yaml (pre-release). Run from the repo checkout: it pushes the tag.
#
# Codeberg push-mirrors branches and tags to Gitea, but releases aren't git
# objects and don't sync in either direction, so this pushes the tag straight
# to Codeberg and creates the release over the API. Skips cleanly when TOKEN is
# unset; fails loudly on anything else (see release.yaml for why).
#
# Env:
#   TOKEN          CODEBERG_RELEASE_TOKEN (write:repository); empty = skip
#   API            https://codeberg.org/api/v1/repos/<owner>/<repo>
#   TAG, SHA       vX.Y.Z[-beta.N] and the commit it marks
#   PRERELEASE     true | false
#   NOTES_FILE     release notes (scripts/release_notes.sh)
#   APK            the signed release APK
set -euo pipefail
if [ -z "${TOKEN:-}" ]; then
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

# Push the tag to Codeberg ourselves. Under Codeberg-canonical the mirror runs
# Codeberg -> Gitea, so waiting for a tag to arrive from Gitea (what 0.3.2 did)
# would wait forever. The tag minted on Gitea is in fact *deleted* by the next
# mirror sync until Codeberg has it, so pushing it here is what makes it
# durable on both forges.
#
# Pushing the ref first and creating the release with NO target_commitish is
# deliberate: a release POST carrying a target_commitish for a commit or tag
# Codeberg hasn't received yet is what produced the empty-bodied 500s.
# Attaching to a ref that already exists doesn't need the API to write one.
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

# Create (or update) the release. Codeberg 500s on a POST/GET against a tag it
# has only just received (the release request outruns the indexing of the ref
# pushed a moment ago), so a single attempt can fail even though the very same
# call succeeds seconds later. Retry with backoff, and PATCH in place if a
# release already exists (re-run safe). A 5xx body still exits curl 0, so the
# loop, not `set -e`, controls the flow.
ID=""
for attempt in 1 2 3 4 5 6; do
  EXIST=$(curl -s -H "Authorization: token $TOKEN" "$API/releases/tags/$TAG" | jq -r '.id // empty' 2>/dev/null || true)
  if [ -n "$EXIST" ]; then
    curl -s -o /dev/null -w "release PATCH HTTP %{http_code}\n" -X PATCH \
      -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
      -d @"$WORK/payload.json" "$API/releases/$EXIST"
    ID="$EXIST"; break
  fi
  CODE=$(curl -s -o "$WORK/response.json" -w "%{http_code}" -X POST \
    -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
    -d @"$WORK/payload.json" "$API/releases")
  echo "release POST attempt $attempt HTTP $CODE"
  ID=$(jq -r '.id // empty' "$WORK/response.json" 2>/dev/null || true)
  [ -n "$ID" ] && break
  sleep $((attempt * 10))
done
if [ -z "$ID" ]; then echo "Could not resolve Codeberg release id after retries." >&2; exit 1; fi

# Attach APK + checksum, replacing any prior asset of the same name.
for A in "$ASSET_APK" "$ASSET_SUM"; do
  OLD=$(curl -s -H "Authorization: token $TOKEN" "$API/releases/$ID/assets" \
    | jq -r --arg n "$A" '.[] | select(.name==$n) | .id')
  [ -n "$OLD" ] && curl -s -X DELETE -H "Authorization: token $TOKEN" "$API/releases/$ID/assets/$OLD" >/dev/null || true
  curl -s -X POST -H "Authorization: token $TOKEN" \
    -F "attachment=@$WORK/$A" \
    "$API/releases/$ID/assets?name=$A" -o /dev/null -w "asset $A HTTP %{http_code}\n"
done
echo "Published $TAG to Codeberg."
