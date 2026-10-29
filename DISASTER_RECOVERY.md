# Disaster recovery: the data bucket

The bands live in the S3 bucket `jacekgajek-art-split-data` (eu-central-1): `bands/<bandId>/state.json`, its
attachments and invites, and the users' records under `users/`. The history (DynamoDB) is less critical: it can be
lost without losing a band, and it isn't backed up. Infrastructure: `my-aws`, `terraform/modules/kapelusz/data.tf` and
`backup.tf`.

## What protects it

| Layer | Covers | How far back |
|---|---|---|
| **Versions in the bucket** | a bug writing a wrong state, a deleted band or file | 30 days |
| **Daily AWS Backup** in the locked vault `art-split-data` | the above noticed late; a mistake with admin access that destroys versions (deleting versions, suspending versioning, a bad lifecycle); once the vault is in compliance mode, also someone with your credentials deleting the backups | a backup each day at 03:00 UTC, kept a year |
| The API's permissions | a server bug can delete objects but not versions (no `s3:DeleteObjectVersion`) | |
| The bucket policy and `prevent_destroy` | deleting the bucket | |
| The members' devices | the last resort: each one holds its bands in full | as last synced |

The privacy policy (§6) states both: deleted data kept 30 days, backups a year.

## Restoring one band (or a few objects) to a moment in the last 30 days

With your own admin credentials (the API's role and CI can't):

```
AWS_PROFILE=... scripts/restore-s3.sh "2026-10-29 10:00 UTC" bands/<bandId>/            # what it would do
AWS_PROFILE=... scripts/restore-s3.sh "2026-10-29 10:00 UTC" bands/<bandId>/ --apply    # do it
```

- Pick a moment just before the damage. The band's History screen shows when changes were made;
  `aws s3api list-object-versions --bucket jacekgajek-art-split-data --prefix bands/<bandId>/state.json` lists each
  saved state's time.
- Each object becomes the version it had then (copied back as a new version); one that didn't exist then is deleted
  (a delete marker). Both are undone the same way: run the script again with a moment from before the restore. It
  refuses a moment older than the versions kept (it can't tell an expired state from one that didn't exist yet): use
  AWS Backup then.
- The band's id: `"id"` in "Copy raw JSON data" (Settings' secret menu, for leaders), or by its name:
  `for p in $(aws s3api list-objects-v2 --bucket jacekgajek-art-split-data --prefix bands/ --delimiter / --query 'CommonPrefixes[].Prefix' --output text); do echo "$p $(aws s3 cp s3://jacekgajek-art-split-data/${p}state.json - 2>/dev/null | jq -r .name)"; done`
- **Afterwards:** phones and the web app see a state that differs from theirs and load it on their next sync; changes
  they haven't sent yet are replayed on it (ones about something that no longer exists are refused, as any change
  is). Changes the server accepted after the chosen moment are gone from the band: they are still listed in its
  History, to enter again by hand. **Don't undo those History entries**: the history isn't rolled back, and undoing
  a change the band no longer has gives odd results.

### A deleted band

Deleting a band (Settings › Delete band) deletes `bands/<bandId>/` except `deleted.json`, its **history** (so there
is no History to read or undo from), its invite codes (`invites/<code>.json`), its event links (`events/<eventId>.json`)
and the band from its members' records. `deleted.json` lists their user ids (`userIds`).

1. Restore the band's folder to a moment before the deletion, as above: `deleted.json` is deleted, the state and files
   come back. Pick the moment from the versions of `state.json`.
2. Each member's `users/<userId>.json` lost the band id from `bandIds`. Add it back by hand (`aws s3 cp` the file
   down, add the id to `bandIds`, `aws s3 cp` it back). Don't restore these files with the script: they also hold
   bands joined or left since and the time of a "Sign out on all devices", which a restore would undo.
3. Invite codes and event links stay gone: leaders make new invites; old event links show "not found".

## Restoring from AWS Backup (older than 30 days, or the versions are gone)

1. AWS Console › AWS Backup › Backup vaults › `art-split-data`: pick the recovery point (a day) before the damage.
2. **Restore** to a **new bucket** (e.g. `art-split-restore-20261029`), with the IAM role `art-split-backup`. Never
   restore over the live bucket: everything else in it would be rolled back too.
3. Copy what's needed back, e.g. one band:
   `aws s3 cp --recursive s3://art-split-restore-20261029/bands/<bandId>/ s3://jacekgajek-art-split-data/bands/<bandId>/`
   (`cp`, not `sync`, which may skip files of the same size). Files that weren't there then stay; to remove them too,
   `aws s3 sync --delete --dryrun` first, read the list, then without `--dryrun`. A deleted band also needs its
   members' records, as above.
4. Delete the temporary bucket when done.

## Checks

- **After applying the Terraform:** the next morning, AWS Backup › Jobs shows a completed backup job for the bucket.
- **Once, now:** restore the Test band to an hour earlier with the script (and back); restore one recovery point to a
  temporary bucket and look into it.
- **Then, optionally, lock the vault for good:** add `changeable_for_days = 3` to
  `aws_backup_vault_lock_configuration.data` and apply. After those 3 days nobody can delete a backup before its year
  or change the lock (closing the whole AWS account is still possible). Until then it is in governance mode: a backup
  can't be deleted early either, but removing the lock first is allowed.
- **Don't add an `aws_s3_bucket_notification` to the data bucket without `eventbridge = true`**: AWS Backup needs the
  bucket's EventBridge notifications, which it turned on itself.
