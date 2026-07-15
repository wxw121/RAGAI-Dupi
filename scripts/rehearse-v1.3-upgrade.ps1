param(
    [ValidateSet("rehearsal", "staging", "production")][string]$Environment = "rehearsal",
    [switch]$AcknowledgeProduction,
    [switch]$ValidateTargetOnly,
    [switch]$ValidateBackupOnly,
    [switch]$ValidatePlanOnly,
    [switch]$BackupSucceeded,
    [string]$ComposeFile = (Join-Path $PSScriptRoot "..\deploy\docker-compose.yml"),
    [string]$ProjectName = "dupi-v13-rehearsal",
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\artifacts\v1.3-upgrade-rehearsal"),
    [string]$KbId = "",
    [string]$HybridProfileId = "",
    [string]$RerankProfileId = "",
    [string]$RollbackProfileId = "",
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey = ""
)

$ErrorActionPreference = "Stop"
$rehearsalPlan = @(
    "backup-postgres", "backup-etcd", "backup-minio", "backup-milvus",
    "restore-milvus", "restore-etcd", "restore-postgres", "restore-minio",
    "start-milvus-2.5.4", "benchmark", "rollback"
)

function Assert-Target {
    if ($Environment -eq "production" -and -not $AcknowledgeProduction) {
        throw "Production rehearsal requires -AcknowledgeProduction"
    }
}

function Invoke-Checked([string]$Phase, [scriptblock]$Command) {
    $started = Get-Date
    & $Command
    if ($LASTEXITCODE -ne 0) { throw "$Phase failed with exit code $LASTEXITCODE" }
    [ordered]@{ phase = $Phase; success = $true; durationMs = [math]::Round(((Get-Date) - $started).TotalMilliseconds) }
}

function Assert-BackupVerified([bool]$Verified) {
    if (-not $Verified) { throw "Backup verification must succeed before upgrade" }
}

try {
    Assert-Target
    if ($ValidateTargetOnly) { Write-Host "Target accepted: $Environment"; exit 0 }
    if ($ValidateBackupOnly) { Assert-BackupVerified ([bool]$BackupSucceeded); exit 0 }
    if ($ValidatePlanOnly) { $rehearsalPlan | ConvertTo-Json -Compress; exit 0 }

    foreach ($name in @("POSTGRES_PASSWORD", "MINIO_ACCESS_KEY", "MINIO_SECRET_KEY")) {
        if (-not [Environment]::GetEnvironmentVariable($name)) { throw "Missing required credential: $name" }
    }
    foreach ($command in @("docker", "Get-FileHash")) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Missing required command: $command" }
    }
    $resolvedOutput = [IO.Path]::GetFullPath($OutputPath)
    $repoRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot ".."))
    if ($resolvedOutput.StartsWith((Join-Path $repoRoot "deploy"), [StringComparison]::OrdinalIgnoreCase)) {
        throw "Backup output must be outside deployment data paths"
    }
    $drive = Get-PSDrive -Name ([IO.Path]::GetPathRoot($resolvedOutput).TrimEnd('\').TrimEnd(':'))
    if ($drive.Free -lt 5GB) { throw "At least 5 GB free space is required for rehearsal artifacts" }
    New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null
    $phases = @()
    $phases += Invoke-Checked "compose-config" { docker compose -f $ComposeFile -p $ProjectName config --quiet }
    $phases += Invoke-Checked "dependency-health" { docker compose -f $ComposeFile -p $ProjectName ps --status running --quiet }

    $postgresPath = Join-Path $resolvedOutput "postgres.sql"
    $etcdPath = Join-Path $resolvedOutput "etcd.snapshot"
    $minioPath = Join-Path $resolvedOutput "minio"
    $milvusPath = Join-Path $resolvedOutput "milvus-data.tgz"
    $postgresUser = if ($env:POSTGRES_USER) { $env:POSTGRES_USER } else { "dupi" }
    $postgresDb = if ($env:POSTGRES_DB) { $env:POSTGRES_DB } else { "dupi_rag" }
    $phases += Invoke-Checked "backup-postgres" { docker compose -f $ComposeFile -p $ProjectName exec -T postgres pg_dump -U $postgresUser -d $postgresDb -f /tmp/v13.sql; docker compose -f $ComposeFile -p $ProjectName cp postgres:/tmp/v13.sql $postgresPath }
    $phases += Invoke-Checked "backup-etcd" { docker compose -f $ComposeFile -p $ProjectName exec -T etcd etcdctl snapshot save /tmp/v13.snapshot; docker compose -f $ComposeFile -p $ProjectName cp etcd:/tmp/v13.snapshot $etcdPath }
    New-Item -ItemType Directory -Force -Path $minioPath | Out-Null
    $phases += Invoke-Checked "backup-minio" { docker run --rm --network "${ProjectName}_default" -v "${minioPath}:/backup" minio/mc sh -c "mc alias set source http://minio:9000 '$env:MINIO_ACCESS_KEY' '$env:MINIO_SECRET_KEY' && mc mirror --overwrite source/dupi-documents /backup" }
    $phases += Invoke-Checked "backup-milvus" { docker run --rm -v "${ProjectName}_milvus_data:/data:ro" -v "${resolvedOutput}:/backup" alpine tar czf /backup/milvus-data.tgz -C /data . }

    $artifacts = @(Get-ChildItem -File -Recurse $resolvedOutput | ForEach-Object {
        [ordered]@{ path = $_.FullName.Substring($resolvedOutput.Length).TrimStart('\'); bytes = $_.Length; sha256 = (Get-FileHash -Algorithm SHA256 $_.FullName).Hash }
    })
    if ($artifacts.Count -lt 4) { throw "Backup verification found fewer than four artifacts" }
    Assert-BackupVerified $true

    foreach ($artifact in $artifacts) {
        $actual = (Get-FileHash -Algorithm SHA256 (Join-Path $resolvedOutput $artifact.path)).Hash
        if ($actual -ne $artifact.sha256) { throw "Backup checksum changed before restore: $($artifact.path)" }
    }
    $restoreProject = "${ProjectName}-restore"
    $restoreMilvusVolume = "${restoreProject}_milvus_data"
    $restoreEtcdVolume = "${restoreProject}_etcd_data"
    $phases += Invoke-Checked "restore-milvus" { docker volume create $restoreMilvusVolume | Out-Null; docker run --rm -v "${restoreMilvusVolume}:/data" -v "${resolvedOutput}:/backup:ro" alpine sh -c "rm -rf /data/* && tar xzf /backup/milvus-data.tgz -C /data" }
    $phases += Invoke-Checked "restore-etcd" { docker volume create $restoreEtcdVolume | Out-Null; docker run --rm --entrypoint etcdctl -v "${restoreEtcdVolume}:/etcd" -v "${resolvedOutput}:/backup:ro" quay.io/coreos/etcd:v3.5.5 snapshot restore /backup/etcd.snapshot --data-dir /etcd }
    $phases += Invoke-Checked "start-restore-dependencies" { docker compose -f $ComposeFile -p $restoreProject up -d --wait postgres redis etcd minio }
    $phases += Invoke-Checked "restore-postgres" { docker compose -f $ComposeFile -p $restoreProject cp $postgresPath postgres:/tmp/v13.sql; docker compose -f $ComposeFile -p $restoreProject exec -T postgres psql -U $postgresUser -d $postgresDb -f /tmp/v13.sql }
    $phases += Invoke-Checked "restore-minio" { docker run --rm --network "${restoreProject}_default" -v "${minioPath}:/backup:ro" minio/mc sh -c "mc alias set target http://minio:9000 '$env:MINIO_ACCESS_KEY' '$env:MINIO_SECRET_KEY' && mc mb --ignore-existing target/dupi-documents && mc mirror --overwrite /backup target/dupi-documents" }
    $phases += Invoke-Checked "start-milvus-2.5.4" { docker compose -f $ComposeFile -p $restoreProject up -d --wait milvus api worker }
    if ($KbId -and $HybridProfileId -and $RerankProfileId) {
        $benchmark = Join-Path $resolvedOutput "benchmark.json"
        $phases += Invoke-Checked "benchmark" { powershell -NoProfile -ExecutionPolicy Bypass -File (Join-Path $PSScriptRoot "rag-retrieval-benchmark.ps1") -KbId $KbId -HybridProfileId $HybridProfileId -RerankProfileId $RerankProfileId -BaseUrl $BaseUrl -ApiKey $ApiKey -OutputPath $benchmark }
    }
    if ($KbId -and $RollbackProfileId) {
        $headers = @{}; if ($ApiKey) { $headers["X-Dupi-API-Key"] = $ApiKey }
        Invoke-RestMethod -Method Post -Uri "$BaseUrl/api/v1/knowledge-bases/$KbId/retrieval-profiles/$RollbackProfileId/rollback" -Headers $headers | Out-Null
        $phases += [ordered]@{ phase = "rollback"; success = $true; durationMs = 0 }
    }
    $manifest = [ordered]@{
        schemaVersion = 1; environment = $Environment; project = $ProjectName
        sourceMilvus = "2.4.1"; targetMilvus = "2.5.4"; generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        backupVerified = $true; artifacts = $artifacts; phases = $phases
    }
    $manifest | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 (Join-Path $resolvedOutput "manifest.json")
    Write-Host "Upgrade rehearsal completed: $resolvedOutput"
    exit 0
} catch {
    Write-Error $_
    exit 1
}
