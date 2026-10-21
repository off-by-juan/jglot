# Releasing jglot

Releases go to Maven Central as `io.github.off-by-juan:jglot` (plus its parent POM,
`jglot-parent`). The benchmarks module is never published.

## One-time setup

1. **Central Portal account.** Sign in at <https://central.sonatype.com> with the `off-by-juan`
   GitHub account. That verifies the `io.github.off-by-juan` namespace automatically. Then
   generate a user token under *View Account → Generate User Token*.
2. **Signing key.** Central requires every artifact to be signed with a GPG key whose public
   half is on a public keyserver:

   ```
   gpg --full-generate-key                       # ECC/Curve 25519; set a passphrase
   gpg --list-secret-keys --keyid-format long    # note the key id
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
   ```

   Use the GitHub noreply address
   (`38688162+off-by-juan@users.noreply.github.com`) as the key's email, since it is public.
   Publish to keyserver.ubuntu.com, which publishes the user id as is. keys.openpgp.org only
   publishes it after emailing a confirmation link, and a noreply address can't receive that.

   The current release key is `EFD6F7D87A63B1CF4CA53709FC4962B5810E1020` and expires on
   2028-10-20. Extend it before then with `gpg --edit-key <KEYID>` → `expire`, and re-send it.
   Back up the revocation certificate in `~/.gnupg/openpgp-revocs.d/`.
3. **Repository secrets**, for the release workflow. Add these under *Settings → Environments →
   release → Environment secrets*. Scoping them to the `release` environment means only the
   release workflow can read them.

   | Secret | Value |
   |---|---|
   | `MAVEN_CENTRAL_USERNAME` | the token's username |
   | `MAVEN_CENTRAL_PASSWORD` | the token's password |
   | `GPG_PRIVATE_KEY` | `gpg --armor --export-secret-keys <KEYID>` |
   | `MAVEN_GPG_PASSPHRASE` | the key's passphrase |

   Optionally, add yourself as a required reviewer on the environment, so every publish waits
   for your approval.

## Cutting a release

1. Open a PR that:
   * sets the version in all three POMs to the release version, e.g.
     `mvn versions:set -DnewVersion=0.1.0 -DgenerateBackupPoms=false`;
   * moves the `CHANGELOG.md` entry from "unreleased" to today's date;
   * updates the version in the README's installation snippets.
2. Once it's merged, tag the merge commit and push the tag:

   ```
   git switch main && git pull
   git tag -a v0.1.0 -m "jglot 0.1.0"
   git push origin v0.1.0
   ```

   The `Release` workflow checks that the tag matches the POM version, builds, signs and uploads
   to Central, waits until the release is published, then creates the GitHub release. Central
   artifacts can't be deleted or replaced, so make sure the version is right before you tag.
3. Open a follow-up PR that bumps the version to the next snapshot (e.g. `0.2.0-SNAPSHOT`) and
   adds an empty "unreleased" section to the changelog.

New versions show up on <https://central.sonatype.com/artifact/io.github.off-by-juan/jglot>
within minutes, and in Maven Central search after a few hours.

## Publishing from a local machine

If you don't want to use the workflow, put the token in `~/.m2/settings.xml`:

```xml
<settings>
  <servers>
    <server>
      <id>central</id>
      <username>TOKEN_USERNAME</username>
      <password>TOKEN_PASSWORD</password>
    </server>
  </servers>
</settings>
```

Then, from a clean checkout of the release tag, run
`MAVEN_GPG_PASSPHRASE=... mvn -P release deploy`.
