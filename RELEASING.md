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
   gpg --full-generate-key                       # RSA 4096 or ed25519; set a passphrase
   gpg --list-secret-keys --keyid-format long    # note the key id
   gpg --keyserver keys.openpgp.org --send-keys <KEYID>
   gpg --keyserver keyserver.ubuntu.com --send-keys <KEYID>
   ```

   keys.openpgp.org emails you a link to confirm the address on the key. Click it, so the key is
   published with its user id.
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
