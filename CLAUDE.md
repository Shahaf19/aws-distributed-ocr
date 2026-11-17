# CLAUDE.md

## Project
Distributed image-OCR system on AWS (Local App → Manager → Workers, via EC2 + S3 + SQS).
**Source of truth:** `Assignment 1.pdf` in the repo root (gitignored). Follow its requirements exactly;
where it is silent, propose options (see "Decisions" below). Deadline: 22/11.
**Whenever the spec mentions or recommends something, stick to it.** Never propose options that bend it
(e.g. exceeding `manager-clients-n` or the 19-instance cap). We only have design freedom where the spec is silent.

## Engineering standards
- **Clean code:** small focused classes, clear names, no duplicated logic.
- **Testing:** unit-test at the component level (e.g. OCR service, message parsing, HTML builder),
  not every tiny method. Each component should be runnable/verifiable locally before wiring it to AWS.
- **Git:** one feature branch per component (e.g. `feature/manager`), merged into `main`.
  Never work directly on `main`. Commit/merge only when the user asks; otherwise prepare the changes and
  suggest a commit message.

## Decisions: how to work with me
Whenever there is a design or implementation choice:
1. List the available options with their tradeoffs.
2. Give a clear recommendation.
3. Call out **AWS cost implications** (API call volume, e.g. SQS polling/long polling, S3 requests,
   EC2 instance count/type/runtime) whenever they influenced the choice.

After the user decides, record it in `DECISIONS.md`: decision, options considered, reason, cost notes.
This later feeds the README the assignment requires.
**Log only architecture decisions with a real tradeoff** (queues, state, concurrency, failure handling,
scaling, cost). Do not log tooling or process choices.

## Architecture and contracts
One Maven module and one shaded jar, with entry points `LocalApp`, `Manager` and `Worker`.

| Point | From → To | Contract |
|---|---|---|
| Shared Manager inbox | Local App → Managers | `NewTask { jobId, inputS3Key, replyQueueUrl, workerFilesN }` or `Terminate {}` (JSON with a `type` field) |
| Task queue (per Manager) | Manager → its Workers | `ImageTask { jobId, index, imageUrl }` |
| Results queue (per Manager) | Workers → Manager | `OcrResult { jobId, index, imageUrl, text \| error }` |
| Reply queue (per job, created by the Local App) | Manager → Local App | `JobDone { jobId, summaryS3Key }` or `JobDone { jobId, rejected: reason }`; summary = JSON list of `OcrResult` in S3 |
| Manager startup | Local App | `Ec2Helper.ensureManagerRunning()` (EC2 tags; re-run every ~1 min while waiting) |
| Names, bucket, timeouts | all | constants in `Config` |

`index` = the URL's line number in the input file. The Manager uses it to ignore SQS duplicates and to know when a
job is complete. Workers send the result **before** deleting the task. The Local App uses the first `JobDone` if two
arrive. Rationale for all of this is in `DECISIONS.md`.

Packages: `messages` (records + JSON), `aws` (`MessageQueue`/`ObjectStore` interfaces, SQS/S3 implementations,
`QueueAdmin`; in-memory fakes under `src/test`), `ocr` (`OcrService`, Tess4J), `manager`.
Components are coded against the interfaces and tested locally with the fakes: the Manager against a fake Worker that
replies "fake text", the Worker with hand-fed `ImageTask`s, the Local App against a fake Manager that answers with a
canned summary. Real AWS is exercised by `AwsSmokeTest` (opt-in: `-Dsmoke=true`).

## Status and next steps
Follows the spec's "Getting Started" order.
- **Done:** Maven project; message contract; AWS wrappers (verified against real SQS/S3); OCR service (verified
  locally, the spec's first coding step); `JobState` (per-job result tracking).
- **Next, in spec order:**
  1. Local App: S3 upload, `NewTask`, wait for `JobDone`, download the summary, HTML output, terminate.
     Verified against a fake Manager.
  2. Manager: inbox loop, `StartJob`, results loop, `FinishJob`, heartbeat. Verified locally with a fake Worker.
  3. Worker: receive → download → OCR → result → delete, with the receive-count limit.
  4. All three together on the local machine (real SQS/S3).
  5. On EC2: `Ec2Helper` (manager lookup, scaling `m − k` ≤ 19), bootstrapping (user-data/AMI, Tesseract on Linux),
     terminate flow. Do an early skeleton EC2 run (one instance running the jar) to surface Academy/IAM/Tesseract
     problems well before the deadline.
- **Open decisions:** see "Open" in `DECISIONS.md`.

## Local environment notes
- OCR needs `tessdata/eng.traineddata` (gitignored, from `tesseract-ocr/tessdata_fast`).
- On Windows, the JDK's own `bin\msvcp140.dll` (older than the system one) crashes Tesseract ("Invalid memory
  access"); it must be renamed so Java loads the newer copy from `System32`.
- AWS Academy credentials go in `~/.aws/credentials` and expire with each lab session.
