# Builds dist/submission.zip as the spec's "Submission" section asks: all sources and class files without the
# libraries, the output of running the system on the sample file, and the README.
# Run `mvn package` first (it compiles target/classes), then:  powershell -File scripts/make-submission.ps1
$ErrorActionPreference = "Stop"
$repo = Split-Path -Parent $PSScriptRoot
$classes = Join-Path $repo "target/classes"

if (-not (Test-Path $classes)) {
    throw "target/classes is missing: run 'mvn package' first"
}
$newestSource = (Get-ChildItem (Join-Path $repo "src/main") -Recurse -File | Measure-Object LastWriteTime -Maximum).Maximum
$newestClass = (Get-ChildItem $classes -Recurse -File | Measure-Object LastWriteTime -Maximum).Maximum
if ($newestSource -gt $newestClass) {
    throw "target/classes is older than the sources: run 'mvn package' again"
}

$stage = Join-Path ([System.IO.Path]::GetTempPath()) ("ocr-submission-" + [guid]::NewGuid())
New-Item -ItemType Directory $stage | Out-Null
try {
    foreach ($item in "README.md", "pom.xml", "src", "docs", "sample") {
        Copy-Item (Join-Path $repo $item) (Join-Path $stage $item) -Recurse
    }
    New-Item -ItemType Directory (Join-Path $stage "scripts") | Out-Null
    Copy-Item (Join-Path $repo "scripts/ami-setup.sh") (Join-Path $stage "scripts")
    New-Item -ItemType Directory (Join-Path $stage "target") | Out-Null
    Copy-Item $classes (Join-Path $stage "target/classes") -Recurse   # class files only: no jar, no libraries

    $dist = Join-Path $repo "dist"
    New-Item -ItemType Directory -Force $dist | Out-Null
    $zip = Join-Path $dist "submission.zip"
    Remove-Item $zip -ErrorAction SilentlyContinue
    Compress-Archive -Path (Join-Path $stage "*") -DestinationPath $zip
    "wrote $zip ($([math]::Round((Get-Item $zip).Length / 1KB)) KB)"
} finally {
    Remove-Item $stage -Recurse -Force
}
