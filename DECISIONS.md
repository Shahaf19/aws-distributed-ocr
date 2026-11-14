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
    channel to "the" Manager, which makes the terminate message ambiguous (see Open).
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
  - **Start pool:** `StartJob` = download input, count K, launch Workers (before sending, so boot overlaps sending),
    send ImageTasks in batches of 10.
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
    `ScheduledExecutorService` thread that extends visibility for all in-flight jobs every few minutes.
    SQS limit: visibility can be extended up to 12 h after the first receive, far longer than any job.
  - Local App (partner): while waiting, re-run "ensure an available Manager exists" every ~1 min, in case the dead
    Manager was the only one. If two `JobDone`s arrive (from a stalled Manager), use the first and ignore the second.
  - Not covered here: the dead Manager's Workers and queues (see the idle Workers question in Open).
- **AWS cost impact:** one ChangeMessageVisibility per in-flight job every few minutes, plus free EC2 checks
  from the Local App. OCR is redone only after a crash.

## Open
- Worker failure handling: visibility timeout vs heartbeat; poison messages.
- Scaling policy, including scaling down idle Workers.
- Idle Manager: keep running until terminate, or shut itself down after an idle timeout.
- Bootstrapping: custom AMI vs user-data; instance type.

<!-- Template
## <title>
- **Decision:** what had to be decided.
- **Options and tradeoffs:**
  - A: pros / cons
  - B: pros / cons
- **Choice and reason:**
- **AWS cost impact:** API calls / instance-hours affected.
-->
