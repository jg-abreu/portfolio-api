# Java dependency monitoring

## Resolved dependency advisory check — 2026-11-13

Maven Dependency Plugin 3.10.0 generated the resolved dependency tree, including test dependencies. An explicit OSV batch query checked 206 distinct Maven name/version pairs. The first result identified `com.google.guava:guava:33.4.0-android`, pulled through `google-genai:2.2.0` → `google-auth-library-oauth2-http:1.33.0`.

The vendor's [GHSA-xxph-c9ww-hj94 / CVE-2026-102554](https://github.com/google/guava/security/advisories/GHSA-xxph-c9ww-hj94) concerns excessive memory allocation during native Java deserialization. A targeted source search found no application `ObjectInputStream`/`readObject` entry point; this does not prove that every framework or runtime path is unreachable. No exploit or out-of-memory payload was run.

Dependency management now selects `33.7.2-android`, retaining the SDK's existing variant and using the [vendor's patched release](https://github.com/google/guava/releases/tag/v33.7.2). The regenerated tree confirmed this exact version. All 133 API tests passed with zero failures/errors/skips. A second complete query checked 206 pairs and returned zero matching advisories at that time.

These are known-advisory matches, not source analysis, reachability analysis or a clean CodeQL result. Build-plugin dependencies, JDK, operating system and container images are outside this tree. Provider responses in automated tests remain simulated; real Gemini verification is pending. The serving local JAR/process was not replaced in this stage; no VM or production change was made.

### Repeat locally

From the API repository, with Java 21 configured:

```powershell
.\mvnw.cmd -B org.apache.maven.plugins:maven-dependency-plugin:3.10.0:tree '-DoutputType=json' '-DoutputFile=target/dependency-tree-security.json'
python scripts/audit-maven-osv.py
```

Generate the tree successfully before querying it. Python uses only its standard library. The script sends public Maven names/versions to `api.osv.dev`, handles batch pagination and writes the timestamp, tree SHA-256 and matching IDs into ignored `target/dependency-osv-security.json`. It exits nonzero for findings or query failures; no secrets or source code are submitted. The report is a dated observation and should be regenerated after dependency changes. See [OSV batch API](https://google.github.io/osv.dev/post-v1-querybatch/) and [Maven tree format](https://maven.apache.org/plugins/maven-dependency-plugin/examples/tree-mojo.html).

## Prepared configuration — 2026-11-13

`.github/dependabot.yml` prepares weekly Maven version checks for the root `pom.xml`, targeting `feat/v2`. Minor/patch updates are grouped, major updates remain separate and at most three version-update PRs may be open. No automatic merge is configured. Spring Boot dependency management remains unchanged; each proposed update needs compatibility review and the applicable build/tests.

The file currently exists only on `feat/v2`. GitHub reads Dependabot configuration from the default branch, so this is prepared configuration, not active monitoring or a completed vulnerability scan. Do not copy it to `main` during V2 development. At the authorized joint release, review the maintenance target branch, repository security settings and first actual Dependabot run before declaring the monitoring active.

`target-branch` applies to version updates. Security-update PRs use the default branch and separate repository settings; those settings have not been changed. A PR limit is not a vulnerability or spending limit.

The initial monitoring-preparation stage did not scan Java dependencies. The later resolved-dependency check and Guava remediation are recorded above. The frontend npm audit does not cover Maven artifacts. CodeQL/source scanning, activation of monitoring and actual GitHub scan results remain pending. Passing behavioral tests alone is not a security scan.

References: [configuration location](https://github.com/github/docs/blob/main/content/code-security/concepts/supply-chain-security/about-the-dependabot-yml-file.md), [options including Maven](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference), [version and security update branches](https://docs.github.com/en/code-security/tutorials/secure-your-dependencies/customizing-dependabot-prs).

## CodeQL V2 results — 2026-11-13

The initial Java analysis completed in [run 43508393301](https://github.com/jg-abreu/portfolio-api/actions/runs/43508393301), identifying the recommendation image read path. Keys were already restricted to a SHA-256 filename and symbolic links/hash mismatches rejected. An additional normalized-path/direct-parent check now makes the directory boundary explicit. Four image tests and the isolated backup/restore test passed after this change (five focused tests, not a rerun of all 133).

The [final run 43509166794](https://github.com/jg-abreu/portfolio-api/actions/runs/43509166794) completed successfully for commit 470dad6, with Java analysis error empty and results_count zero. The open-alert query for refs/heads/feat/v2 was empty. The workflow uses Java 21/manual compilation and security-extended queries, with pinned official actions, no application secrets and no deploy step. It runs only on V2 pushes/PRs; maintenance/release triggers need review at the joint launch. This is a source-analysis result for that commit, not proof that all security risks are absent.
