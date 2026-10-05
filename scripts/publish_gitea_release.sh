#!/usr/bin/env bash
# Create (or update) the release for TAG on the Gitea build instance, the
# changelog of record there, and attach the R8 mapping. Shared by
# .gitea/workflows/release.yaml and beta.yaml.
#
# Creating it with target_commitish makes Gitea create the tag at SHA, so the
# tag only ever marks a fully-shipped version. Upsert, so re-runs are safe.
#
# Env:
#   TOKEN, API     Gitea token and .../api/v1/repos/<owner>/<repo>
#   TAG, SHA       vX.Y.Z[-beta.N] and the commit it marks
#   PRERELEASE     true | false
#   NOTES_FILE     release notes (scripts/release_notes.sh)
#   MAPPING        optional path to R8's mapping.txt
set -euo pipefail
: "${TOKEN:?}" "${API:?}" "${TAG:?}" "${SHA:?}" "${PRERELEASE:?}" "${NOTES_FILE:?}"

WORK=$(mktemp -d)
trap 'rm -rf "$WORK"' EXIT

python3 - "$TAG" "$SHA" "$PRERELEASE" "$NOTES_FILE" <<'PY' > "$WORK/payload.json"
import json, sys
tag, sha, pre, notes = sys.argv[1:5]
print(json.dumps({
    "tag_name": tag,
    "target_commitish": sha,
    "name": tag,
    "body": open(notes).read(),
    "draft": False,
    "prerelease": pre == "true",
}))
PY

# PATCH if a release for the tag already exists, else POST a new one (which
# also creates the tag at target_commitish).
ID=$(curl -s -H "Authorization: token $TOKEN" "$API/releases/tags/$TAG" | jq -r '.id // empty' 2>/dev/null || true)
if [ -n "$ID" ]; then
  CODE=$(curl -s -o "$WORK/response.json" -w '%{http_code}' -X PATCH \
    -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
    -d @"$WORK/payload.json" "$API/releases/$ID")
  OK=200
else
  CODE=$(curl -s -o "$WORK/response.json" -w '%{http_code}' -X POST \
    -H "Authorization: token $TOKEN" -H "Content-Type: application/json" \
    -d @"$WORK/payload.json" "$API/releases")
  OK=201
fi
cat "$WORK/response.json"; echo
if [ "$CODE" != "$OK" ]; then
  echo "Gitea release upsert failed with HTTP $CODE (expected $OK)" >&2
  exit 1
fi
ID=$(jq -r '.id' "$WORK/response.json")
echo "Created/updated Gitea release $TAG at $SHA"

# The R8 mapping keeps user crash stacktraces deobfuscatable. It's not an APK,
# so it fits the no-binaries rule for this release. Best-effort: never fail a
# release over it.
attach_mapping() {
  local asset="mapping-${TAG#v}.txt.gz" old
  gzip -c "$MAPPING" > "$WORK/$asset"
  # Replace any prior asset of the same name (re-run safe).
  old=$(curl -s -H "Authorization: token $TOKEN" "$API/releases/$ID/assets" \
    | jq -r --arg n "$asset" '.[] | select(.name==$n) | .id')
  if [ -n "$old" ]; then
    curl -s -X DELETE -H "Authorization: token $TOKEN" "$API/releases/$ID/assets/$old" >/dev/null
  fi
  curl -s -X POST -H "Authorization: token $TOKEN" \
    -F "attachment=@$WORK/$asset" \
    "$API/releases/$ID/assets?name=$asset" -o /dev/null -w "asset $asset HTTP %{http_code}\n"
}
if [ -z "${MAPPING:-}" ] || [ ! -f "$MAPPING" ]; then
  echo "No mapping.txt (R8 off?) — skipping."
else
  attach_mapping || echo "warning: could not attach the R8 mapping to $TAG" >&2
fi
