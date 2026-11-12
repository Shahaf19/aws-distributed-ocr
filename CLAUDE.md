# CLAUDE.md

## Project
Distributed image-OCR system on AWS (Local App → Manager → Workers, via EC2 + S3 + SQS).
**Source of truth:** `Assignment 1.pdf` in the repo root (gitignored). Follow its requirements exactly;
where it is silent, propose options (see "Decisions" below).

## Engineering standards
- **Clean code:** small focused classes, clear names, no duplicated logic.
- **Testing:** unit-test at the component level (e.g. OCR service, message parsing, HTML builder),
  not every tiny method. Each component should be runnable/verifiable locally before wiring it to AWS.
- **Git:** one feature branch per component (e.g. `feature/manager`), merged into `main`.
  Never work directly on `main`.
  **The user makes all commits/merges themselves**: prepare changes and suggest a commit message, don't commit.

## Decisions: how to work with me
Whenever there is a design or implementation choice:
1. List the available options with their tradeoffs.
2. Give a clear recommendation.
3. Call out **AWS cost implications** (API call volume, e.g. SQS polling/long polling, S3 requests,
   EC2 instance count/type/runtime) whenever they influenced the choice.

After the user decides, record it in `DECISIONS.md` (gitignored): date, decision, options considered,
reason, cost notes. This later feeds the README the assignment requires.

## Team split (2 partners)
- **Shahaf:** Manager (concurrent clients, per-client aggregation, termination), scaling
  (launching workers, ≤19 instances), AWS infra helpers, including `Ec2Helper.ensureManagerRunning()`
  (EC2 tag lookup + RunInstances), which the Local App calls.
- **Partner:** Worker end to end (image download, Tess4J OCR, SQS loop, visibility timeout, error messages,
  crash recovery) and Local App (S3 upload, SQS new-task/done flow, download summary, HTML output, terminate).
- **Together:** the `common` package (day 1), EC2 bootstrapping (AMI / user-data / IAM / Tesseract on Linux)
  at the end, and the README. Do an **early skeleton run on EC2** (one instance runs the jar via user-data)
  around day 2–3 to surface Academy/IAM/Tesseract problems before the 22/11 deadline.
- Each partner reviews the other's branch before merge, because both are graded individually in a face-to-face session.
- Focus on the current user's own parts. If a change touches the other partner's code or the shared contract, flag it first.

### Integration contract (build together first, in package `com.bgu.ocr.common`)
| Point | From → To | Contract |
|---|---|---|
| Image-task queue | Manager → Worker | `ImageTask { jobId, imageUrl }` (JSON) |
| Results queue | Worker → Manager | `OcrResult { jobId, imageUrl, text \| error }` (JSON) |
| Done message + summary file | Manager → Local App | `JobDone { jobId, summaryS3Key }`; summary = list of `OcrResult` (JSON) |
| Manager startup | Local App calls Shahaf's helper | `Ec2Helper.ensureManagerRunning()` |
| Queue names / bucket | both | constants in `Config` |

- One Maven module and one shaded jar, with entry points `LocalApp`, `Manager` and `Worker`. The shared classes can't drift apart.
- Each side tests against fakes: the Manager against a fake worker that replies "fake text"; the Worker with hand-fed
  `ImageTask`s; the Local App against a fake Manager that answers new-task with a canned summary.
  Integration should then need only configuration changes.

## Environment (Shahaf's Windows machine; paths may differ for the partner)
- JDK 17 at `C:\Program Files\Java\jdk-17`, `JAVA_HOME` set at user level.
- No global `mvn`; build with the VS Code Maven wrapper, run from its own directory:
  `%USERPROFILE%\.vscode\extensions\vscjava.vscode-maven-0.46.0\resources\maven-wrapper\mvnw.cmd -f <repo>\pom.xml package`
- `mvn package` produces runnable fat jar `target/aws-distributed-ocr.jar` (maven-shade).
- Never commit credentials, `.pem` files, or the assignment PDF.
