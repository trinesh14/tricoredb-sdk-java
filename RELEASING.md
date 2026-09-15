# Releasing to Maven Central

How a maintainer publishes `io.github.trinesh14:tricoredb` to Maven Central.
Run every command in **Git Bash** from the repository root: it provides both
`gpg` and a shell for `./mvnw`, so one keyring is used throughout.

## One-time setup

### 1. Central Portal account and namespace

1. Sign up at <https://central.sonatype.com> **with the GitHub account
   `trinesh14`**. Signing up with GitHub verifies the namespace
   `io.github.trinesh14` automatically; an email sign-up does not.
2. Confirm that **Namespaces** lists `io.github.trinesh14` as verified.

### 2. Publishing token

1. In the Central Portal, open your account menu → **View Account** →
   **Generate User Token**.
2. Add the token to `~/.m2/settings.xml` (`C:\Users\<you>\.m2\settings.xml`),
   creating the file if needed:

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

   The `id` must be `central`; it matches `publishingServerId` in `pom.xml`.
   Never commit this file.

### 3. GPG signing key

Maven Central requires every file to be signed.

```bash
gpg --full-generate-key                 # RSA and RSA, 4096 bits, your name and email, a strong passphrase
gpg --list-secret-keys --keyid-format LONG
```

The key id is the part after `rsa4096/` on the `sec` line (16 hex characters).
Publish the public key so Central can verify signatures:

```bash
gpg --keyserver keyserver.ubuntu.com --send-keys KEYID
```

Back up the private key and its passphrase somewhere safe. Losing them means
future releases are signed with a different key.

### 4. Public source repository

The POM points to <https://github.com/trinesh14/tricoredb-sdk-java>. Make the
repository **public** before publishing, so the links on Maven Central work.

## Each release

1. Make sure the version in `pom.xml`, `CHANGELOG.md` and `README.md` is the
   version you are releasing, and that the tree is committed.
2. Run the full test suite against a server (see README → Building from source):

   ```bash
   ./mvnw -B verify -Dtricore.server.bin=/path/to/tricore-server
   ```

3. Build, sign and upload. The passphrase is read from the environment, never
   from the command line:

   ```bash
   read -rs MAVEN_GPG_PASSPHRASE && export MAVEN_GPG_PASSPHRASE
   ./mvnw -B -Prelease -Dgpg.keyname=KEYID -DskipTests deploy
   unset MAVEN_GPG_PASSPHRASE
   ```

   `deploy` uploads the jar, sources jar, javadoc jar, POM and their `.asc`
   signatures. Checksums are added by the publishing plugin.
4. In the Central Portal, open **Deployments**. When validation passes, click
   **Publish**. (`autoPublish` is off, so nothing goes public until you do.)
   If validation fails, click **Drop**, fix the problem and deploy again.
5. Tag the release and push the tag:

   ```bash
   git tag -a v0.1.0 -m "tricoredb 0.1.0"
   git push origin v0.1.0
   ```

6. After a short delay the artifact appears on
   <https://central.sonatype.com/artifact/io.github.trinesh14/tricoredb>; it can
   take longer to reach `repo1.maven.org` and search.

A version published to Maven Central can never be changed or removed. If
something is wrong, release a new version.
