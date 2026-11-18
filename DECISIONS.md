# Decisions log

Architecture decisions with a real tradeoff only (queues, state, concurrency, failure handling, scaling, cost).
Where the spec states something, we follow it; these are the points where the spec leaves us a choice.


## One shared Manager inbox queue, pulled only when there's room
- **Decision:** how new-task messages reach Managers when several Managers can exist.
- **Options and tradeoffs:**
  - A: one inbox queue per Manager; the Local App addresses the Manager it picked. A job is stuck if that Manager
    dies, the queues must be created and deleted, and the Local App's choice is fixed even if that Manager becomes busy.
  - B: one shared queue with messages addressed by Manager ID. SQS can't filter by recipient, so messages bounce
    between Managers: wasted receives and extra delay.
  - C: one shared queue, not addressed. Each Manager pulls only while `activeClients` < `manager-clients-n`.
    Balances load automatically, and if a Manager dies another one takes its waiting jobs. Downside: there's no
    channel to "the" Manager, which makes the terminate message ambiguous (resolved in "Terminate shuts down all Managers").
- **Choice and reason:** C. It's simplest and most robust, and it fits the spec ("sends a message to an SQS queue").
- **AWS cost impact:** the same long-polling cost as per-Manager queues, with no CreateQueue/DeleteQueue calls.

## Each Manager owns its own Workers
- **Decision:** with several Managers, whether Workers are one shared pool or belong to one Manager.
- **Options and tradeoffs:**
  - A: one shared pool and task queue. Better use of instances (an idle Worker helps any Manager), but each task
    must carry its Manager's results queue, "k active Workers" counts every Manager's Workers, and on terminate a
    Manager can't kill Workers that others still use.
  - B: each Manager has its own Workers plus its own task and results queues (created at startup, deleted on terminate).
    The spec's "k active, create m−k" and "wait for the workers, then terminate them" apply literally.
    Slightly worse use of instances: one Manager's Workers can sit idle while another's are busy.
- **Choice and reason:** B. Clear ownership, and it matches the spec's scaling and termination wording exactly.
  Several Managers only exist when more than `manager-clients-n` clients run at once.
- **AWS cost impact:** possibly a few more Worker-hours when several Managers run. 2 CreateQueue + 2 DeleteQueue
  calls per Manager lifetime (negligible).

## Each Local App has its own reply queue
- **Decision:** how each Local App receives *its own* done message. The spec requires SQS for this
  ("checks an SQS queue for a message indicating the process is done").
- **Options and tradeoffs:**
  - A: one shared done queue; Local Apps filter by job ID and put back messages that aren't theirs. Messages
    bounce between clients, so delay and wasted receives grow with the number of clients. Doesn't scale.
  - B: the Local App creates its own `done-{jobId}` queue, sends its URL inside `NewTask`, and deletes the queue
    when finished. Each client has its own channel and nothing needs filtering. If a Local App crashes, its queue is
    left behind and must be cleaned up manually.
- **Choice and reason:** B. It scales with the number of clients and matches the reply-queue pattern of the
  Manager owning its Worker queues.
- **AWS cost impact:** 2 extra calls per client (CreateQueue + DeleteQueue). Negligible.

## Manager threading model: 2 loop threads + 2 pools
- **Decision:** how the Manager handles many clients at once ("must process requests ... simultaneously").
- **Design:**
  - **Inbox loop** (1 thread): pulls `NewTask` only while `jobs.size()` < `manager-clients-n`, creates the job's
    entry in the jobs table, submits `StartJob`. Also notices terminate.
  - **Results loop** (1 thread): pulls `OcrResult`s, fills `results[index]` (duplicates ignored), and when a job
    reaches K results, submits `FinishJob`.
  - **Start pool:** `StartJob` = download input, count K, send ImageTasks in batches of 10, then launch Workers.
    This is the spec's order ("creates an SQS message for each URL … checks the SQS message count and starts
    Workers"). Launching first would only save the few seconds of sending.
  - **Finish pool:** `FinishJob` = build summary, upload to S3, send `JobDone`, remove the job from the table.
  - Shared state: the jobs table (`ConcurrentHashMap`). **Scaling is one synchronized method**, so two concurrent
    `StartJob`s can't both launch Workers and exceed what's needed or the 19-instance cap.
- **Options and tradeoffs:**
  - A: several threads each polling SQS directly. Simplest, but every idle thread polls, so more API calls.
  - B: one poller per queue plus pools for slow work. Only 2 SQS pollers no matter the load.
  - Pools: one shared pool (a burst of large new jobs delays finishing ones) vs two pools (finishing never waits
    behind starting; about two extra lines of code).
- **Choice and reason:** B with two pools. Slow work never blocks polling, and nearly finished clients aren't delayed by new jobs.
  Within one job, download → send is a chain, so it stays in one task. Parallelism is across jobs.
- **AWS cost impact:** 2 long-polling receivers per Manager regardless of load. Batched sends: ⌈K/10⌉ calls per job.

## Terminate shuts down all Managers
- **Decision:** with a shared inbox, a terminate message reaches whichever Manager pulls it. What should it mean
  when several Managers exist? (The spec: "Be sure all is closed once requested!")
- **Options and tradeoffs:**
  - A: only the Manager that pulls it terminates. Effectively random; the other Managers run forever, which keeps billing.
  - B: the receiving Manager tags **every** Manager `status=terminating` (one CreateTags call). Each Manager checks
    its own tag in the inbox loop and runs the spec's shutdown steps: stop accepting jobs, finish in-flight jobs,
    wait for its Workers, terminate them, terminate itself. Downside: one client's terminate also stops Managers
    serving others, but their in-flight jobs still complete.
  - C: terminate only the Manager that served this client, through a per-Manager control queue. More queues and code,
    and Managers whose clients never send terminate keep running.
- **Choice and reason:** B. It's the only option that satisfies "all is closed once requested", and nothing is left billing.
- **Jobs still waiting in the inbox at shutdown:** the spec says "creates response messages for the jobs, if needed".
  Terminating Managers keep draining the inbox and compare each `NewTask`'s SQS `SentTimestamp` with the terminate
  message's `SentTimestamp`. Both are SQS server times, so they're comparable.
  - Sent **before** terminate: reply "rejected: system is terminating" to its reply queue (`NewTask` carries the URL).
  - Sent **after** terminate: don't touch it (make it visible again). It comes from a Local App that saw only
    terminating Managers and therefore started a new Manager, which will pick it up.
- **Implementation:** the terminate's SentTimestamp travels in a second tag (`terminateSentAt`), so every Manager
  applies the same cutoff. Managers check their own tag every 30 s; a full Manager (which doesn't read the inbox)
  learns about the terminate this way. Shutdown order: stop the inbox loop → drain the inbox (reject jobs sent
  before the cutoff, hold later ones) until in-flight jobs finish → terminate its Workers → stop its threads → delete
  its two queues → release the held jobs → terminate its instance. The boot script restarts the process only after
  a crash (non-zero exit), never after this deliberate shutdown.
- **AWS cost impact:** checking tags is free. Every instance is terminated, so there are no leftover instance-hours.

## Manager crash recovery: state in memory, the inbox message is the backup
- **Decision:** where the Manager keeps job state, and what happens if it dies or stalls (the spec asks:
  "What if a node dies? What if a node stalls?").
- **Options and tradeoffs:**
  - A: memory only; the Local App notices the Manager is dead and resubmits. Simple Manager, but recovery depends
    on the client, and the whole job is redone.
  - B: save state to S3 (job info + collected results). Continues where it stopped, but it's complex: a new Manager
    must find orphaned jobs and take over the dead Manager's queues, and results must be saved before they're
    deleted from SQS. About one S3 write per result.
  - C: memory only, and `NewTask` is **not deleted from the inbox until the job is finished**. A heartbeat keeps
    extending its visibility. If the Manager dies or stalls, the message reappears in the shared inbox and another
    Manager redoes the job.
- **Choice and reason:** C. Recovery is automatic through SQS, it builds on the shared inbox, and the jobs table stays a simple map.
- **Details:**
  - Manager: `NewTask` is deleted in `FinishJob`, after `JobDone` is sent. The heartbeat is one
    `ScheduledExecutorService` thread that extends visibility for all in-flight jobs.
    SQS limit: visibility can be extended up to 12 h after the first receive, far longer than any job.
  - Numbers: the inbox queue is created with a **180 s** visibility timeout (not the 30 s default, which would let
    the `NewTask` reappear before the first beat and start the job twice). Every **60 s** the heartbeat resets each
    job's deadline to now + 180 s. Recovery after a crash takes ≤ 3 min. A fixed beat was chosen over a growing one
    (fewer calls, slower recovery) and a progress-based one (also catches a stuck Manager, but falsely abandons jobs
    while Workers boot). Calls are negligible either way.
  - Limit: the heartbeat detects a **dead** Manager (silence), not a **stuck** one whose heartbeat thread still runs.
    Mitigations: timeouts on every network call, and **fail-fast**: if any Manager thread dies of an uncaught error,
    the whole process exits, so its heartbeat stops and recovery takes over (found in the first real-AWS run, where
    a dead results thread left a "zombie" Manager holding its job). To be explained in the README.
  - Local App: while waiting, re-run "ensure an available Manager exists" every ~1 min, in case the dead
    Manager was the only one. If two `JobDone`s arrive (from a stalled Manager), use the first and ignore the second.
  - Not covered here: the dead Manager's Workers and queues (see the idle Workers question in Open).
- **AWS cost impact:** one ChangeMessageVisibility per in-flight job every few minutes, plus free EC2 checks
  from the Local App. OCR is redone only after a crash.

## Idle timeouts: idle Workers, then the idle Manager, shut down
- **Decision:** the spec fixes scale-up (`m − k`, ≤ 19 instances) and shutdown on terminate, but says nothing about
  instances that sit idle when no client sends terminate.
- **Options and tradeoffs:**
  - A: keep everything until terminate. The next job starts instantly, but idle instances may bill for hours.
  - B: the Manager terminates its Workers after X minutes with no active jobs, and terminates itself after ~10 minutes
    with no jobs. Deciding is local (the jobs table is empty), and only the Manager kills Workers, so its count `k` stays right.
    The next job waits 1–3 minutes for new instances to boot.
  - C: Workers shut themselves down when their queue stays empty. Race: the Manager counts them as active, launches
    fewer than needed, and then they disappear. It also spreads scaling logic into the Worker.
- **Choice and reason:** B. Pay only while there's work, with all scaling decisions in one place.
  A Manager shutting down while idle loses nothing: a `NewTask` that arrives in the meantime stays in the shared inbox,
  and the waiting Local App's periodic "ensure an available Manager exists" starts a new one.
- **AWS cost impact:** saves idle instance-hours, which is the main cost. EC2 API calls (Describe/Run/Terminate)
  are not billed.
- **Numbers and implementation:** checked every 30 s; Workers are terminated after **5 min** without jobs (the next
  job relaunches them, ~1 min boot), the Manager after **10 min**. An idle Manager first tags itself `terminating`
  (so clients start a new one if needed), then runs the terminate shutdown without rejecting anything: jobs that
  arrive meanwhile are held and released for the next Manager.

## Worker failures: fixed visibility timeout + receive-count limit for poison images
- **Decision:** how an `ImageTask` survives a Worker that crashes or stalls ("some other worker should be able to
  handle that message"), and how an image that breaks *every* Worker (a poison message) is stopped from circling forever.
- **Options and tradeoffs:**
  - Visibility of an `ImageTask`:
    - A: a heartbeat in the Worker. Fits work of unknown length, but more code and calls, and a stuck Worker
      whose heartbeat still runs holds the image forever.
    - B: a fixed timeout safely above the worst case for one image (download + OCR take seconds). No extra code or calls.
      A crashed *or* stalled Worker simply stops holding the image when the timeout expires.
  - Poison images:
    - A: an SQS dead-letter queue (redrive policy, `maxReceiveCount`). SQS moves the message after N receives
      automatically, but then no `OcrResult` is ever sent, and the job never completes unless the Manager also
      watches the DLQ: one more queue and one more poller.
    - B: the Worker checks `ApproximateReceiveCount` (returned free with each receive). Above the limit it skips OCR,
      sends an error `OcrResult` ("failed after N attempts") and deletes the task. The job completes, and the spec's
      error line appears in the output.
- **Choice and reason:** B + B. Simplest, no extra queues, and every image always ends in exactly one result.
  A late result from a stalled Worker that wakes up is a duplicate the Manager ignores (by `index`).
  Images that merely *fail* (404, timeout, unreadable) need no counting: the exception becomes an error result at once.
- **Numbers:** the task queue is created by the Manager with a **120 s** visibility timeout. The limit is **3 receives**.
  Downloads need an explicit timeout so a stuck download can't outlast the visibility.
- **AWS cost impact:** no extra calls (the receive count comes with the receive). A crashed or poison image is
  processed at most 3 times.

## Workers take one task per receive
- **Decision:** how many `ImageTask`s a Worker takes per receive (SQS allows 1–10).
- **Options and tradeoffs:**
  - A: 1 per receive. A free Worker always gets the next image, so work spreads evenly, and the 120 s visibility
    covers exactly one image.
  - B: up to 10 per receive. 10× fewer receive calls, but one Worker holds 10 images while others may sit idle
    (the spec: "Are all your workers working hard? Or some are slacking?"), and all 10 share one visibility clock,
    so the last ones can time out while waiting and be processed twice.
- **Choice and reason:** A. Even load and an accurate per-image timeout, for a negligible cost.
- **AWS cost impact:** one receive per image instead of one per 10: 1,000 images = 1,000 vs 100 calls,
  about $0.0004 apart.

## Bootstrapping: a custom AMI with the software, the jar fetched from S3 at boot
- **Decision:** how Manager and Worker instances get Linux, Java, Tesseract and our jar. The spec recommends "an AMI
  composed of any data needed … Linux, Java JDK, Amazon AWS cli, the jar file …", with user-data that downloads
  everything at boot as the fallback "in case AWS disable the option for creating new AMI".
- **Options and tradeoffs:**
  - A: everything in the AMI, including the jar; user-data only starts it. Exactly the spec and the fastest boot,
    but every code change means a new AMI (launch, copy jar, create image ~5–10 min, update the ID, delete the old one).
  - B: an AMI with Ubuntu 24.04 + Java 17 + Tesseract 5; user-data downloads the current jar from our S3 bucket and
    starts the right class with its arguments. Same fast boot (+ a few seconds of download); a code change is just
    a jar upload. Bends the spec's "jar in the AMI".
  - C: no custom AMI; user-data installs everything on every boot. Spec's fallback only; 2–4 min of installs per
    instance, paid by every Worker.
- **Choice and reason:** B. The software that rarely changes is baked in (fast boot, as the spec intends); our code,
  which changes constantly, stays a single S3 upload. For the final submission, consider baking the final jar in
  (A) to match the spec literally.
- **Settled by platform/spec:** Ubuntu 24.04 (the only common base with a Tesseract 5 package, which Tess4J 5.20
  needs); credentials from the Academy `LabInstanceProfile`, never in the jar or user-data; `t3.micro` to start
  (move Workers to `t3.small` if OCR runs out of memory). The AMI's install steps live in the repo as a script.
- **AWS cost impact:** AMI snapshot storage (~8 GB, about $0.40/month); one S3 GET of the jar (~72 MB) per boot,
  free in-region; boots stay short, so less idle billed time than C.

## Orphan Workers and queues: cleaned up by the Local Apps' periodic Manager check
- **Decision:** a Manager that crashes (rather than shutting down) leaves Workers running and billing, plus its two
  queues. Who removes them?
- **Options and tradeoffs:**
  - A: nobody; they go away on the next terminate. Simplest, but orphans bill until then.
  - B: the Local App's "ensure an available Manager" step, which already lists the Managers at startup and every
    minute while waiting: terminate running Workers whose `managerId` isn't an alive Manager, and delete
    `ocr-tasks-*` / `ocr-results-*` queues of non-alive Managers. One extra Describe/ListQueues per check.
  - C: each Worker checks that its Manager is alive. Worker code plus EC2 calls from every Worker.
- **Choice and reason:** B. Central, cheap, and it runs exactly when it matters (clients are waiting). A booting
  Manager (`pending`) counts as alive, so its new Workers and queues are never touched. Failures are logged and never
  block the client's own job.
- **AWS cost impact:** EC2 calls are free; one ListQueues per check. Saves the orphans' instance-hours.
- **Found in the final run:** the per-minute check first used the spec's "available" rule (a Manager with room).
  With two clients filling the only Manager, a waiting client saw "no room" and launched a second Manager nobody
  needed. While waiting, the check now only requires *some* Manager to be alive; the "available" rule stays for the
  check before a job is sent.

## Open
- Scaling down while smaller jobs are still running.
- Fairness between jobs on one task queue (head-of-line blocking): interleaved sending in `StartJob` (Manager-only,
  no contract change). Per-job task queues are out: the spec says "all worker nodes take their messages from the
  same SQS queue".

## Settled by the spec (no choice left)
- **Two Managers scaling at the same moment** could together exceed 19 instances (the synchronized scaling method
  only locks within one Manager). The spec says "you may assume there will not be any race conditions" (e.g. two Local
  Apps starting a Manager at once), so there's no cross-Manager lock. A `RunInstances` failure is treated as "cap reached".

<!-- Template
## <title>
- **Decision:** what had to be decided.
- **Options and tradeoffs:**
  - A: pros / cons
  - B: pros / cons
- **Choice and reason:**
- **AWS cost impact:** API calls / instance-hours affected.
-->
