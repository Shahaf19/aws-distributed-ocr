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

`index` = the URL's position among the input file's non-blank lines, dense 0…K−1 (it sizes and fills the job's
result array; blank lines must not leave gaps, or the job never completes). The Manager uses it to ignore SQS
duplicates, to keep input order, and to know when a
job is complete. Workers send the result **before** deleting the task. The Local App uses the first `JobDone` if two
arrive. Rationale for all of this is in `DECISIONS.md`.

Packages: `messages` (records + JSON), `aws` (`MessageQueue`/`ObjectStore` interfaces, SQS/S3 implementations,
`QueueAdmin`; in-memory fakes under `src/test`), `ocr` (`OcrService`, Tess4J), `manager`.
Components are coded against the interfaces and tested locally with the fakes: the Manager against a fake Worker that
replies "fake text", the Worker with hand-fed `ImageTask`s, the Local App against a fake Manager that answers with a
canned summary. Real AWS is exercised by `AwsSmokeTest` (opt-in: `-Dsmoke=true`).

## Status and next steps
Follows the spec's "Getting Started" order.
- **Done** (all verified locally on the in-memory fakes; `SystemTest` runs Local App + Manager + fake Worker):
  message contract; AWS wrappers (verified against real SQS/S3); OCR service; Local App; Manager core (inbox and
  results loops, Start/Finish pools, heartbeat, crash recovery, retry vs reject of unstartable jobs); Worker
  (download with timeouts, OCR, error results, poison-task limit, one task per receive).
- **Not yet:** `ManagerStarter` (Local App) and `WorkerScaler` (Manager) are stubs; the terminate flow and idle
  timeouts are not implemented.
- **Next, in spec order:**
  1. All three together on the local machine with real SQS/S3 (needs a lab session).
  2. On EC2: `Ec2Helper` (manager lookup and launch, scaling `m − k` ≤ 19), bootstrapping (user-data/AMI,
     Tesseract on Linux), terminate flow, idle timeouts. Start with a skeleton EC2 run (one instance running the
     jar) to surface Academy/IAM/Tesseract problems well before the deadline.
- **Open decisions:** see "Open" in `DECISIONS.md`.

## Local environment notes
- OCR needs `tessdata/eng.traineddata` (gitignored, from `tesseract-ocr/tessdata_fast`).
- On Windows, the JDK's own `bin\msvcp140.dll` (older than the system one) crashes Tesseract ("Invalid memory
  access"); it must be renamed so Java loads the newer copy from `System32`.
- AWS Academy credentials go in `~/.aws/credentials` and expire with each lab session.
