# Builds notebooklm/project-code.md: all the source code in one Markdown file, grouped by component in the order the
# system was built, with a short intro per part. Upload it to NotebookLM together with the spec, README.md and
# DECISIONS.md. Run from anywhere:  powershell -File scripts/export-for-notebooklm.ps1
$repo = Split-Path -Parent $PSScriptRoot
$main = "src/main/java/com/bgu/ocr"
$test = "src/test/java/com/bgu/ocr"

$parts = @(
    @{ Title = "Part 1 - The message contract (built first)"
       Intro = "Every message that travels through SQS is a Java record converted to JSON. Agreeing on these formats first let the Local App, Manager and Worker be built and tested separately. Key ideas: NewTask and Terminate share the inbox, so they carry a 'type' field (InboxMessage); every ImageTask/OcrResult carries an 'index' (the URL's position among the non-blank input lines) used for de-duplication, completion and output order; an OcrResult has exactly one of text or error; a broken message raises MalformedMessageException."
       Files = @("messages/InboxMessage.java","messages/NewTask.java","messages/Terminate.java","messages/ImageTask.java","messages/OcrResult.java","messages/JobDone.java","messages/Summary.java","messages/Json.java","messages/MalformedMessageException.java") },
    @{ Title = "Part 2 - Settings and AWS wrappers (SQS and S3)"
       Intro = "Thin wrappers over the AWS SDK behind small interfaces (MessageQueue, QueueAdmin, ObjectStore). The rest of the code never imports AWS classes, so it can be tested with in-memory fakes (Part 9). SqsQueue does long polling (20 s), batching (10 per call) and returns ReceivedMessage with the receipt handle, SentTimestamp and ApproximateReceiveCount. Config holds all names, timeouts and limits (bucket, AMI, 19-instance cap, visibility timeouts)."
       Files = @("Config.java","QueueNames.java","aws/MessageQueue.java","aws/ReceivedMessage.java","aws/SqsQueue.java","aws/QueueAdmin.java","aws/SqsQueueAdmin.java","aws/QueueGoneException.java","aws/ObjectStore.java","aws/S3ObjectStore.java","aws/ObjectMissingException.java") },
    @{ Title = "Part 3 - OCR service"
       Intro = "Tesseract (through Tess4J) turns an image into text. The image is handed to Tesseract as raw grayscale pixels: Tess4J's usual path needs a newer Leptonica than Ubuntu 24.04 has, which the first EC2 test revealed."
       Files = @("ocr/OcrService.java") },
    @{ Title = "Part 4 - Local App (the client)"
       Intro = "Runs on the user's computer: parses the command line, makes sure a Manager is available, uploads the input to S3, creates its own reply queue, sends NewTask to the shared inbox, long-polls the reply queue for JobDone (re-checking for a Manager every minute), downloads the summary, writes the HTML, and optionally sends Terminate."
       Files = @("localapp/LocalAppArgs.java","localapp/HtmlReport.java","localapp/ManagerStarter.java","localapp/LocalApp.java") },
    @{ Title = "Part 5 - Manager core (concurrency and job tracking)"
       Intro = "The Manager serves many clients at once. Threads: an inbox loop (accepts jobs only while it has room for manager-clients-n), a results loop, a Start pool (StartJob: download input, send one task per URL, then ask for Workers), a Finish pool (FinishJob: summary to S3, JobDone to the client, then delete the NewTask), and a scheduler for the heartbeat and lifecycle checks. JobState tracks one job's results by index; JobTable is the shared table and the room check. Crash recovery: the NewTask stays hidden in the inbox until the job finishes; the heartbeat extends it every 60 s, so if the Manager dies it reappears for another Manager."
       Files = @("manager/JobState.java","manager/JobTable.java","manager/ImageTasks.java","manager/WorkerScaler.java","manager/StartJob.java","manager/FinishJob.java","manager/ResultsLoop.java","manager/InboxLoop.java","manager/Heartbeat.java","manager/Manager.java") },
    @{ Title = "Part 6 - Worker"
       Intro = "Takes one task at a time (so work spreads evenly), downloads the image with time limits, runs OCR, sends the result (or a short error) and only then deletes the task. A task delivered more than 3 times is reported as an error instead of being retried forever (poison message). Any failure, even a Java Error, concerns only that one image."
       Files = @("worker/ImageSource.java","worker/ImageDownloader.java","worker/TaskProcessor.java","worker/Worker.java") },
    @{ Title = "Part 7 - EC2: launching, scaling, terminating, cleaning up (built last)"
       Intro = "Instances boot from our AMI and fetch the jar from S3 (UserData). EC2 tags mark roles (role=manager/worker), a Manager's load (activeClients), its Workers (managerId) and shutdown (status=terminating, terminateSentAt). The Local App starts a Manager if none is available (ManagerAvailability) and removes leftovers of dead Managers (Orphans). The Manager launches m - k Workers within the 19-instance cap (ScalingPolicy) and handles terminate and idleness through Fleet: stop taking jobs, reject jobs sent before the terminate (InboxDrainer), finish in-flight jobs, terminate Workers, delete its queues, terminate itself."
       Files = @("aws/UserData.java","aws/InstanceInfo.java","aws/Ec2Tags.java","aws/Ec2Helper.java","localapp/ManagerAvailability.java","localapp/Orphans.java","localapp/Ec2ManagerStarter.java","manager/ScalingPolicy.java","manager/Ec2WorkerScaler.java","manager/ActiveClientsPublisher.java","manager/Fleet.java","manager/Ec2Fleet.java","manager/InboxDrainer.java") }
)

$out = New-Object System.Text.StringBuilder
[void]$out.AppendLine("# OCR in the Cloud - all the source code")
[void]$out.AppendLine("")
[void]$out.AppendLine("Java 17, one Maven module, one jar with three entry points: com.bgu.ocr.localapp.LocalApp (java -jar), com.bgu.ocr.manager.Manager and com.bgu.ocr.worker.Worker (started on EC2). Packages: messages (JSON contract), aws (SQS/S3/EC2 wrappers), ocr, localapp, manager, worker. Parts appear in the order they were built. Design reasons are in DECISIONS.md; the overview is in README.md.")
[void]$out.AppendLine("")

function Add-File($label, $path, $lang) {
    [void]$out.AppendLine("### $label")
    [void]$out.AppendLine("")
    [void]$out.AppendLine('```' + $lang)
    [void]$out.AppendLine((Get-Content -Raw -Path $path).TrimEnd())
    [void]$out.AppendLine('```')
    [void]$out.AppendLine("")
}

foreach ($part in $parts) {
    [void]$out.AppendLine("## $($part.Title)")
    [void]$out.AppendLine("")
    [void]$out.AppendLine($part.Intro)
    [void]$out.AppendLine("")
    foreach ($f in $part.Files) { Add-File $f (Join-Path $repo "$main/$f") "java" }
}

[void]$out.AppendLine("## Part 8 - The machine image (AMI) recipe")
[void]$out.AppendLine("")
[void]$out.AppendLine("The build (Maven) makes one jar with the AWS SDK 2.57.0 and Tess4J 5.20.0, the versions the spec lists. This script is the recipe of our AMI: Ubuntu 24.04 + Java 17 + Tesseract 5 + AWS CLI. The jar itself is not in the AMI; every instance downloads it from S3 when it starts.")
[void]$out.AppendLine("")
Add-File "scripts/ami-setup.sh" (Join-Path $repo "scripts/ami-setup.sh") "bash"

# Only the tests that help understand the system: the fakes that show how SQS behaves, and the scenario tests that
# read like stories. Narrow unit tests (argument parsing, HTML escaping, ...) are left out.
$keyTests = @(
    @("aws/InMemoryQueue.java", "Imitates SQS: a received message is hidden, not removed, until deleted; expireInFlight() simulates a receiver that crashed."),
    @("manager/FakeWorker.java", "Stands in for a real Worker: answers every task with fake text (result first, then delete)."),
    @("manager/JobStateTest.java", "Results arriving in any order, duplicates, completion firing exactly once."),
    @("manager/InboxDrainerTest.java", "The terminate rule: jobs sent before the terminate are rejected, later ones are left for a new Manager."),
    @("manager/ManagerTest.java", "Scenarios: one job, two clients at once, a Manager that dies and another that recovers its job, retries, rejection, fail-fast, terminate, idle shutdown."),
    @("SystemTest.java", "The real Local App and Manager running together with a fake Worker, on in-memory queues.")
)
[void]$out.AppendLine("## Part 9 - Key tests and fakes")
[void]$out.AppendLine("")
[void]$out.AppendLine("Every component was tested locally before touching AWS, using in-memory fakes instead of SQS, S3 and EC2. Only the tests that explain the system's behavior are included here.")
[void]$out.AppendLine("")
foreach ($t in $keyTests) {
    [void]$out.AppendLine("$($t[0]): $($t[1])")
    [void]$out.AppendLine("")
    Add-File "test: $($t[0])" (Join-Path $repo "$test/$($t[0])") "java"
}

$dir = Join-Path $repo "notebooklm"
New-Item -ItemType Directory -Force $dir | Out-Null
$target = Join-Path $dir "project-code.md"
[System.IO.File]::WriteAllText($target, $out.ToString(), (New-Object System.Text.UTF8Encoding($false)))
"wrote $target ($([math]::Round((Get-Item $target).Length / 1KB)) KB)"
