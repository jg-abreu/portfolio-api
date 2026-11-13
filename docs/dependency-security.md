# Java dependency monitoring

## Prepared configuration — 2026-11-13

`.github/dependabot.yml` prepares weekly Maven version checks for the root `pom.xml`, targeting `feat/v2`. Minor/patch updates are grouped, major updates remain separate and at most three version-update PRs may be open. No automatic merge is configured. Spring Boot dependency management remains unchanged; each proposed update needs compatibility review and the applicable build/tests.

The file currently exists only on `feat/v2`. GitHub reads Dependabot configuration from the default branch, so this is prepared configuration, not active monitoring or a completed vulnerability scan. Do not copy it to `main` during V2 development. At the authorized joint release, review the maintenance target branch, repository security settings and first actual Dependabot run before declaring the monitoring active.

`target-branch` applies to version updates. Security-update PRs use the default branch and separate repository settings; those settings have not been changed. A PR limit is not a vulnerability or spending limit.

No Java advisory scan was run in this stage, no dependency version changed and no clean vulnerability result is claimed. The frontend npm audit does not cover Maven artifacts. CodeQL/source scanning, Java advisory results and review of any findings remain release work. The existing 133-test result is behavioral evidence recorded elsewhere, not a security scan.

References: [configuration location](https://github.com/github/docs/blob/main/content/code-security/concepts/supply-chain-security/about-the-dependabot-yml-file.md), [options including Maven](https://docs.github.com/en/code-security/reference/supply-chain-security/dependabot-options-reference), [version and security update branches](https://docs.github.com/en/code-security/tutorials/secure-your-dependencies/customizing-dependabot-prs).
