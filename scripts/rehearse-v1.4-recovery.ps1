param(
    [string]$BaseUrl = "http://localhost:8080",
    [string]$ApiKey = "",
    [string[]]$FixtureFiles = @(),
    [int]$FixtureDocumentCount = 0,
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\artifacts\v1.4-recovery\rehearsal.json"),
    [int]$PollSeconds = 180,
    [switch]$KeepResources,
    [switch]$ValidateFixtureOnly,
    [switch]$ValidateManifestOnly,
    [string]$ManifestJson = "",
    [string]$ManifestBase64 = "",
    [switch]$ValidateCleanupOnly,
    [string]$ResourceName = "",
    [switch]$ValidateArtifactOnly,
    [scriptblock]$CorruptObject
)

$ErrorActionPreference = "Stop"
$createdPrefix = "v14-recovery-"

function Assert-Fixture([int]$Count) {
    if ($Count -lt 2) { throw "Recovery rehearsal requires at least two fixture documents" }
}

function Assert-Manifest($Manifest) {
    if (-not $Manifest -or $Manifest.schemaVersion -ne 1 -or $Manifest.itemCount -lt 2 `
        -or $Manifest.totalBytes -le 0 -or [string]::IsNullOrWhiteSpace($Manifest.manifestChecksum) `
        -or $Manifest.checksumValid -ne $true -or @($Manifest.items).Count -lt 2) {
        throw "Recovery manifest is incomplete or corrupt"
    }
    foreach ($item in @($Manifest.items)) {
        if ([string]::IsNullOrWhiteSpace($item.itemKey) -or [string]::IsNullOrWhiteSpace($item.sha256)) {
            throw "Recovery manifest item evidence is incomplete"
        }
    }
    if (-not (@($Manifest.items).itemKey -contains "vector:dense")) {
        throw "Recovery manifest does not contain dense vector evidence"
    }
}

function Assert-CleanupName([string]$Name) {
    if ([string]::IsNullOrWhiteSpace($Name) -or -not $Name.StartsWith($createdPrefix, [StringComparison]::Ordinal)) {
        throw "Cleanup is limited to resources created by this rehearsal"
    }
}

function New-Artifact([string]$RunId) {
    [ordered]@{
        schemaVersion = 1
        runId = $RunId
        generatedAt = (Get-Date).ToUniversalTime().ToString("o")
        evidence = [ordered]@{
            fixtureDocumentCount = 0; archiveItemCount = 0; archiveTotalBytes = 0
            manifestChecksum = $null; objectChecksumMatch = $false; vectorChecksumMatch = $false
            recordCountMatch = $false; retrievalEquivalent = $false; corruptionBlocked = $false
        }
        cleanup = [ordered]@{ createdResourcesOnly = $true; completed = $false }
        phases = @()
    }
}

function Invoke-Api([string]$Method, [string]$Path, $Body = $null) {
    $headers = @{}
    if ($ApiKey) { $headers["X-Dupi-API-Key"] = $ApiKey }
    $parameters = @{ Method = $Method; Uri = "$($BaseUrl.TrimEnd('/'))$Path"; Headers = $headers; UseBasicParsing = $true; TimeoutSec = 30 }
    if ($null -ne $Body) {
        $parameters.ContentType = "application/json"
        $parameters.Body = ($Body | ConvertTo-Json -Depth 20 -Compress)
    }
    Invoke-RestMethod @parameters
}

function Wait-Terminal([string]$Path, [string[]]$Terminal) {
    $deadline = (Get-Date).AddSeconds($PollSeconds)
    do {
        $value = Invoke-Api "GET" $Path
        if ($Terminal -contains $value.status) { return $value }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    throw "Timed out waiting for recovery operation: $Path"
}

function Add-Phase($Artifact, [string]$Name, [scriptblock]$Action) {
    $started = Get-Date
    Write-Host "Starting recovery rehearsal phase: $Name"
    $result = & $Action
    $Artifact.phases += [ordered]@{ name = $Name; success = $true; durationMs = [math]::Round(((Get-Date) - $started).TotalMilliseconds) }
    $result
}

function Get-DocumentEvidence($Values) {
    @($Values | ForEach-Object {
        "$($_.fileName)|$($_.mimeType)|$($_.fileSize)|$($_.status)"
    } | Sort-Object)
}

function Get-RetrievalEvidence($Response) {
    @($Response.hits | ForEach-Object {
        "$($_.fileName)|$($_.content)|$([math]::Round([double]$_.score, 6))"
    } | Sort-Object)
}

try {
    if ($ValidateFixtureOnly) { Assert-Fixture $FixtureDocumentCount; exit 0 }
    if ($ValidateManifestOnly) {
        $json = if ($ManifestBase64) { [Text.Encoding]::UTF8.GetString([Convert]::FromBase64String($ManifestBase64)) } else { $ManifestJson }
        Assert-Manifest ($json | ConvertFrom-Json)
        exit 0
    }
    if ($ValidateCleanupOnly) { Assert-CleanupName $ResourceName; exit 0 }
    if ($ValidateArtifactOnly) { New-Artifact "validation" | ConvertTo-Json -Depth 10 -Compress; exit 0 }

    Assert-Fixture $FixtureFiles.Count
    foreach ($file in $FixtureFiles) { if (-not (Test-Path -LiteralPath $file -PathType Leaf)) { throw "Fixture file not found: $file" } }
    if (-not (Get-Command curl.exe -ErrorAction SilentlyContinue)) { throw "curl.exe is required for multipart fixture uploads" }

    $runId = "$createdPrefix$([DateTimeOffset]::UtcNow.ToUnixTimeMilliseconds())"
    $artifact = New-Artifact $runId
    $artifact.evidence.fixtureDocumentCount = $FixtureFiles.Count
    $kb = Add-Phase $artifact "create-fixture" { Invoke-Api "POST" "/api/v1/knowledge-bases" @{ name = $runId; retrievalMode = "HYBRID" } }
    $kbId = $kb.id
    Assert-CleanupName $kb.name

    foreach ($file in $FixtureFiles) {
        $arguments = @("--fail", "--silent", "--show-error", "-X", "POST")
        if ($ApiKey) { $arguments += @("-H", "X-Dupi-API-Key: $ApiKey") }
        $arguments += @("-F", "file=@$file", "$($BaseUrl.TrimEnd('/'))/api/v1/knowledge-bases/$kbId/documents")
        & curl.exe @arguments | Out-Null
        if ($LASTEXITCODE -ne 0) { throw "Fixture upload failed: $file" }
    }

    $documents = @()
    $deadline = (Get-Date).AddSeconds($PollSeconds)
    do {
        $documents = @(Invoke-Api "GET" "/api/v1/knowledge-bases/$kbId/documents")
        $documentStatuses = @($documents.status)
        Write-Host "Waiting for fixture ingestion: $($documentStatuses -join ',')"
        if ($documentStatuses.Count -ge 2 -and @($documentStatuses | Where-Object { $_ -ne "COMPLETED" }).Count -eq 0) { break }
        if (@($documentStatuses | Where-Object { $_ -eq "FAILED" }).Count -gt 0) { throw "Fixture ingestion failed" }
        Start-Sleep -Seconds 2
    } while ((Get-Date) -lt $deadline)
    Assert-Fixture @($documents.status).Count
    if (@($documents.status | Where-Object { $_ -ne "COMPLETED" }).Count -gt 0) {
        throw "Timed out waiting for fixture ingestion to complete"
    }

    $sourceRetrieval = Add-Phase $artifact "source-retrieval" { Invoke-Api "POST" "/api/v1/knowledge-bases/$kbId/retrieve" @{ query = "recovery rehearsal"; topK = 5 } }
    $archive = Add-Phase $artifact "archive" { Invoke-Api "POST" "/api/v1/knowledge-bases/$kbId/recovery/archives" }
    $archive = Wait-Terminal "/api/v1/knowledge-bases/$kbId/recovery/archives/$($archive.id)" @("COMPLETED", "FAILED")
    if ($archive.status -ne "COMPLETED") { throw "Recovery archive failed: $($archive.errorCode)" }
    $artifact.evidence.archiveItemCount = $archive.itemCount
    $artifact.evidence.archiveTotalBytes = $archive.totalBytes
    $artifact.evidence.manifestChecksum = $archive.manifestChecksum

    $downloadDir = Join-Path ([IO.Path]::GetTempPath()) $runId
    $zipPath = "$downloadDir.zip"
    $downloadArgs = @("--fail", "--silent", "--show-error", "-o", $zipPath)
    if ($ApiKey) { $downloadArgs += @("-H", "X-Dupi-API-Key: $ApiKey") }
    $downloadArgs += "$($BaseUrl.TrimEnd('/'))/api/v1/knowledge-bases/$kbId/recovery/archives/$($archive.id)/download"
    & curl.exe @downloadArgs
    if ($LASTEXITCODE -ne 0) { throw "Recovery archive download failed" }
    Expand-Archive -LiteralPath $zipPath -DestinationPath $downloadDir -Force
    $manifest = Get-Content -Raw (Join-Path $downloadDir "manifest.json") | ConvertFrom-Json
    $objectChecks = @()
    $vectorChecks = @()
    foreach ($item in @($manifest.items)) {
        $marker = "/$($archive.id)/"
        $index = $item.objectKey.IndexOf($marker, [StringComparison]::Ordinal)
        if ($index -lt 0) { throw "Archive item escaped its prefix" }
        $relative = $item.objectKey.Substring($index + $marker.Length).Replace('/', [IO.Path]::DirectorySeparatorChar)
        $itemPath = Join-Path $downloadDir $relative
        if (-not (Test-Path -LiteralPath $itemPath -PathType Leaf)) { throw "Archive item is missing from download: $($item.itemKey)" }
        $matches = ((Get-FileHash -Algorithm SHA256 -LiteralPath $itemPath).Hash.ToLowerInvariant() -eq $item.sha256.ToLowerInvariant())
        if ($item.itemKey.StartsWith("object:")) { $objectChecks += $matches }
        if ($item.itemKey.StartsWith("vector:")) { $vectorChecks += $matches }
    }
    $artifact.evidence.objectChecksumMatch = ($objectChecks.Count -ge 2 -and -not ($objectChecks -contains $false))
    $artifact.evidence.vectorChecksumMatch = ($vectorChecks.Count -ge 1 -and -not ($vectorChecks -contains $false))
    Remove-Item -Recurse -Force -LiteralPath $downloadDir
    Remove-Item -Force -LiteralPath $zipPath

    $restore = Add-Phase $artifact "restore" { Invoke-Api "POST" "/api/v1/knowledge-bases/$kbId/recovery/restores" @{ archiveId = $archive.id } }
    $restore = Wait-Terminal "/api/v1/knowledge-bases/$kbId/recovery/restores/$($restore.id)" @("COMPLETED", "FAILED")
    if ($restore.status -ne "COMPLETED") { throw "Recovery restore failed: $($restore.errorCode)" }
    $targetRetrieval = Add-Phase $artifact "target-retrieval" { Invoke-Api "POST" "/api/v1/knowledge-bases/$($restore.targetKnowledgeBaseId)/retrieve" @{ query = "recovery rehearsal"; topK = 5 } }
    $targetDocuments = @(Invoke-Api "GET" "/api/v1/knowledge-bases/$($restore.targetKnowledgeBaseId)/documents")

    $artifact.evidence.recordCountMatch = ((Get-DocumentEvidence $documents) -join "`n") -ceq ((Get-DocumentEvidence $targetDocuments) -join "`n")
    $artifact.evidence.retrievalEquivalent = ((Get-RetrievalEvidence $sourceRetrieval) -join "`n") -ceq ((Get-RetrievalEvidence $targetRetrieval) -join "`n")
    if ($CorruptObject) {
        $corruptionTarget = @($manifest.items | Where-Object { $_.itemKey.StartsWith("object:") }) | Select-Object -First 1
        if (-not $corruptionTarget) { throw "Recovery archive has no object available for corruption proof" }
        $corrupted = & $CorruptObject $corruptionTarget.objectKey
        if ($corrupted -ne $true) { throw "Recovery object corruption hook failed" }
        $blocked = $false
        try {
            Invoke-Api "POST" "/api/v1/knowledge-bases/$kbId/recovery/restores" @{ archiveId = $archive.id } | Out-Null
        } catch {
            $status = $_.Exception.Response.StatusCode.value__
            if ($status -in @(400, 409)) { $blocked = $true } else { throw }
        }
        if (-not $blocked) { throw "Corrupt recovery archive was accepted for restore" }
        $artifact.evidence.corruptionBlocked = $true
    }
    $artifact.cleanup.completed = $false

    if (-not $KeepResources) {
        Invoke-Api "DELETE" "/api/v1/knowledge-bases/$kbId/recovery/archives/$($archive.id)" | Out-Null
        Invoke-Api "DELETE" "/api/v1/knowledge-bases/$($restore.targetKnowledgeBaseId)" | Out-Null
        Invoke-Api "DELETE" "/api/v1/knowledge-bases/$kbId" | Out-Null
        $artifact.cleanup.completed = $true
    }
    $resolved = [IO.Path]::GetFullPath($OutputPath)
    New-Item -ItemType Directory -Force -Path (Split-Path $resolved -Parent) | Out-Null
    $artifact | ConvertTo-Json -Depth 10 | Set-Content -Encoding UTF8 $resolved
    $requiredEvidence = @(
        $artifact.evidence.objectChecksumMatch,
        $artifact.evidence.vectorChecksumMatch,
        $artifact.evidence.recordCountMatch,
        $artifact.evidence.retrievalEquivalent,
        $artifact.evidence.corruptionBlocked,
        $artifact.cleanup.createdResourcesOnly,
        $artifact.cleanup.completed
    )
    if ($requiredEvidence -contains $false) {
        throw "Recovery rehearsal evidence gate failed; inspect $resolved"
    }
    Write-Host "Recovery rehearsal completed: $resolved"
    exit 0
} catch {
    Write-Error $_
    exit 1
}
