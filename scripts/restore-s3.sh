#!/usr/bin/env bash
# Puts objects of the data bucket back as they were at a moment in the past, from the bucket's versions (kept as long as
# the bucket's lifecycle says: 30 days). See DISASTER_RECOVERY.md.
#   AWS_PROFILE=... scripts/restore-s3.sh "2026-10-29 10:00 UTC" bands/<bandId>/ [more...]          # shows what it would do
#   AWS_PROFILE=... scripts/restore-s3.sh "2026-10-29 10:00 UTC" bands/<bandId>/ [more...] --apply  # does it
# Each argument is a folder (ending in /) or one object's key. The time is anything `date -d` reads, in local time
# unless it says otherwise ("... UTC"); it must be within the versions' lifetime, or older states would look like
# objects that didn't exist yet. For each object: the version current at that time is copied back as the newest (so a
# restore can itself be undone the same way); one that didn't exist then is deleted (a delete marker, also undoable).
# Needs s3:GetLifecycleConfiguration, s3:ListBucketVersions, s3:GetObjectVersion, s3:PutObject and s3:DeleteObject:
# your own admin credentials.
set -euo pipefail

BUCKET="${DATA_BUCKET:-jacekgajek-art-split-data}"
APPLY=0
ARGS=()
for arg in "$@"; do
  if [[ "$arg" == --apply ]]; then APPLY=1; else ARGS+=("$arg"); fi
done
if (( ${#ARGS[@]} < 2 )); then
  sed -n '2,11p' "$0" >&2
  exit 2
fi
AT=$(date -d "${ARGS[0]}" +%s)

# Older than the versions kept, a state can't be told from "didn't exist yet": refused rather than deleting live data.
KEPT_DAYS=$(aws s3api get-bucket-lifecycle-configuration --bucket "$BUCKET" --output json |
  jq '[.Rules[] | select(.Status == "Enabled") | .NoncurrentVersionExpiration.NoncurrentDays // empty] | min // empty')
if [[ -z "$KEPT_DAYS" ]]; then
  echo "The bucket's lifecycle keeps no old versions for a known time; restore from AWS Backup instead." >&2
  exit 1
fi
OLDEST=$(( $(date +%s) - (KEPT_DAYS - 1) * 86400 ))
if (( AT < OLDEST )); then
  echo "Old versions are kept $KEPT_DAYS days: pick a time after $(date -u -d "@$OLDEST" '+%Y-%m-%d %H:%M UTC'), or restore from AWS Backup." >&2
  exit 1
fi
echo "Bucket $BUCKET, as at $(date -u -d "@$AT" '+%Y-%m-%d %H:%M:%S UTC')$( ((APPLY)) || echo ', dry run (--apply to do it)')"

for target in "${ARGS[@]:1}"; do
  # A folder is everything under it; anything else exactly that key (bands/abc must not take bands/abcd... too).
  aws s3api list-object-versions --bucket "$BUCKET" --prefix "$target" --output json |
    jq -c --argjson at "$AT" --arg target "$target" '
      def epoch: sub("\\.[0-9]+"; "") | sub("\\+00:00$"; "Z") | fromdateiso8601;
      # S3 lists each key newest first; the position breaks ties within a second (times are whole seconds).
      [((.Versions // []) | to_entries[] | .value + {order: .key, deleted: false}),
       ((.DeleteMarkers // []) | to_entries[] | .value + {order: .key, deleted: true})]
      | map(select(($target | endswith("/")) or .Key == $target))
      | map(. + {time: (.LastModified | epoch)})
      | group_by(.Key)[]
      | {
          key: .[0].Key,
          current: (map(select(.IsLatest)) | first),
          past: (map(select(.time <= $at)) | sort_by(-.time, .order) | first)
        }
      | if .past != null and (.past.deleted | not) then
          if .current.VersionId == .past.VersionId then empty
          else {action: "restore", key, version: .past.VersionId, from: .past.LastModified} end
        elif .current != null and (.current.deleted | not) then {action: "delete", key}
        else empty end' |
    while read -r step; do
      action=$(jq -r .action <<<"$step")
      key=$(jq -r .key <<<"$step")
      if [[ "$action" == restore ]]; then
        version=$(jq -r .version <<<"$step")
        echo "restore $key  (version of $(jq -r .from <<<"$step"))"
        if ((APPLY)); then
          # Keys are plain ids (StorageKeys.safe): nothing to escape in the copy source.
          aws s3api copy-object --bucket "$BUCKET" --key "$key" --copy-source "$BUCKET/$key?versionId=$version" >/dev/null
        fi
      else
        echo "delete  $key  (didn't exist then)"
        if ((APPLY)); then aws s3api delete-object --bucket "$BUCKET" --key "$key" >/dev/null; fi
      fi
    done
done
((APPLY)) && echo "Done. Phones and the web app load the restored state on their next sync." || true
