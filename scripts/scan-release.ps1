param(
    [switch]$PolicyOnly,
    [string]$PipAuditJson = "",
    [string]$TrivyJson = "",
    [string]$SbomJson = "",
    [long]$ImageSizeBytes = 0,
    [long]$ImageBudgetBytes = 3GB,
    [string]$Image = "dupi-rag-worker:v1.4",
    [string]$OutputPath = (Join-Path $PSScriptRoot "..\artifacts\v1.4-release-scan"),
    [string]$HighExceptionPath = "",
    [string]$PipAuditIndexUrl = "https://pypi.tuna.tsinghua.edu.cn/simple",
    [string]$PipAuditExtraIndexUrl = "https://mirrors.aliyun.com/pytorch-wheels/cpu",
    [ValidateSet("osv", "pypi", "esms")]
    [string]$PipAuditService = "osv",
    [int]$PipAuditTimeout = 60,
    [ValidateRange(1, 5)]
    [int]$PipAuditAttempts = 3,
    [string]$PipAuditContainerImage = "dupi-rag-pip-audit:2.10.1",
    [switch]$DisablePipAuditContainerFallback,
    [string[]]$TrivyDbRepository = @("ghcr.io/aquasecurity/trivy-db:2", "public.ecr.aws/aquasecurity/trivy-db:2"),
    [string]$TrivyTimeout = "15m",
    [switch]$TrivySkipDbUpdate
)

$ErrorActionPreference = "Stop"
$denylistPath = Join-Path $PSScriptRoot "..\deploy\license-denylist.txt"

function Read-Json([string]$Path) {
    if (-not $Path) { return $null }
    [System.IO.File]::ReadAllText((Resolve-Path $Path)) | ConvertFrom-Json
}

function Get-PipAuditDependencies($Audit) {
    if (-not $Audit) { return @() }
    if ($Audit.PSObject.Properties.Name -contains "dependencies") {
        return @($Audit.dependencies | Where-Object { $null -ne $_ })
    }
    return @($Audit | Where-Object { $null -ne $_ })
}

function Get-FindingKey($Finding) {
    "$($Finding.source)|$($Finding.package)|$($Finding.id)".ToLowerInvariant()
}

function Get-ImageRelease {
    if ($Image -notmatch ":(v\d+\.\d+(?:\.\d+)?)$") { return $null }
    $release = $Matches[1].ToLowerInvariant()
    if ($release -match "^v\d+\.\d+$") { return "$release.0" }
    $release
}

function Get-ReleaseException {
    if (-not $HighExceptionPath) { return $null }
    if (-not (Test-Path $HighExceptionPath)) { throw "Release exception file does not exist" }

    $exception = Read-Json $HighExceptionPath
    if (-not $exception.release) { throw "Release exception must declare a release" }
    $expectedRelease = Get-ImageRelease
    if (-not $expectedRelease) { throw "Release exceptions require an image tag formatted as vMAJOR.MINOR or vMAJOR.MINOR.PATCH" }
    if (([string]$exception.release) -ine $expectedRelease) {
        throw "Release exception is for $($exception.release), expected $expectedRelease"
    }
    if (-not $exception.expiresAt) { throw "Release exception must declare expiresAt" }
    try {
        $expiresAt = [DateTimeOffset]::Parse($exception.expiresAt).ToUniversalTime()
    } catch {
        throw "Release exception expiresAt is invalid"
    }
    if ($expiresAt -le [DateTimeOffset]::UtcNow) { throw "Release exception expired at $expiresAt" }

    $findings = @($exception.findings | Where-Object { $null -ne $_ })
    if ($findings.Count -eq 0) { throw "Release exception must list at least one finding" }
    foreach ($finding in $findings) {
        if (-not $finding.source -or -not $finding.package -or -not $finding.id -or -not $finding.reason) {
            throw "Each release exception finding requires source, package, id, and reason"
        }
    }
    $exception
}

function Assert-ExceptionCoverage($Findings) {
    $actualFindings = @($Findings | Where-Object { $null -ne $_ })
    if ($actualFindings.Count -eq 0) {
        if ($HighExceptionPath) { throw "Release exception contains no active findings" }
        return
    }

    $exception = Get-ReleaseException
    if (-not $exception) {
        $labels = $actualFindings | ForEach-Object { "$($_.source)/$($_.package)/$($_.id)" }
        throw "Release findings require a dated exception: $($labels -join ', ')"
    }

    $allowed = @{}
    foreach ($finding in @($exception.findings)) {
        $allowed[(Get-FindingKey $finding)] = $finding
    }
    $actual = @{}
    $unmatched = @()
    foreach ($finding in $actualFindings) {
        $key = Get-FindingKey $finding
        $actual[$key] = $finding
        if (-not $allowed.ContainsKey($key)) { $unmatched += $finding }
    }
    if ($unmatched.Count -gt 0) {
        $labels = $unmatched | ForEach-Object { "$($_.source)/$($_.package)/$($_.id)" }
        throw "Release exception does not cover: $($labels -join ', ')"
    }

    $unused = @($allowed.Keys | Where-Object { -not $actual.ContainsKey($_) })
    if ($unused.Count -gt 0) { throw "Release exception contains inactive findings: $($unused -join ', ')" }
}

function Assert-Policy {
    $blocked = @()
    $exceptionEligible = @()
    if ($PipAuditJson) {
        $audit = Read-Json $PipAuditJson
        $pythonFindings = @(Get-PipAuditDependencies $audit | Where-Object { @($_.vulns | Where-Object { $null -ne $_ }).Count -gt 0 })
        foreach ($dependency in $pythonFindings) {
            foreach ($vulnerability in @($dependency.vulns)) {
                $finding = [pscustomobject]@{
                    source = "pip-audit"
                    package = [string]$dependency.name
                    id = [string]$vulnerability.id
                }
                if (@($vulnerability.fix_versions | Where-Object { $_ }).Count -gt 0) {
                    $blocked += "Fixable Python vulnerability: $($dependency.name)/$($vulnerability.id)"
                } else {
                    $exceptionEligible += $finding
                }
            }
        }
    }
    if ($TrivyJson) {
        $trivy = Read-Json $TrivyJson
        $imageFindings = @($trivy.Results | ForEach-Object { $_.Vulnerabilities } | Where-Object { $null -ne $_ })
        foreach ($vulnerability in $imageFindings) {
            if ($vulnerability.Severity -in @("CRITICAL", "HIGH")) {
                if ($vulnerability.FixedVersion) {
                    $blocked += "Fixable $($vulnerability.Severity) vulnerability: $($vulnerability.PkgName)/$($vulnerability.VulnerabilityID)"
                } else {
                    $exceptionEligible += [pscustomobject]@{
                        source = "trivy"
                        package = [string]$vulnerability.PkgName
                        id = [string]$vulnerability.VulnerabilityID
                    }
                }
            }
        }
    }
    try {
        Assert-ExceptionCoverage $exceptionEligible
    } catch {
        $blocked += $_.Exception.Message
    }
    if ($SbomJson) {
        $sbom = Read-Json $SbomJson
        $denied = @(Get-Content -Encoding UTF8 $denylistPath | Where-Object { $_ -and -not $_.StartsWith('#') })
        $osPackageTypes = @("alpm", "apk", "deb", "portage", "rpm")
        $found = @(
            $sbom.artifacts |
                Where-Object { $osPackageTypes -notcontains $_.type } |
                ForEach-Object { $_.licenses } |
                ForEach-Object { $_.value } |
                Where-Object { $denied -contains $_ } |
                Sort-Object -Unique
        )
        if ($found.Count -gt 0) { $blocked += "Denied license: $($found -join ', ')" }
    }
    if ($ImageSizeBytes -gt $ImageBudgetBytes) { $blocked += "Worker image size $ImageSizeBytes exceeds budget $ImageBudgetBytes" }
    if ($blocked.Count -gt 0) { throw ($blocked -join "; ") }
}

try {
    if ($PolicyOnly) { Assert-Policy; Write-Host "Release scan policy passed"; exit 0 }
    $pipAuditViaModule = $false
    if (-not (Get-Command "pip-audit" -ErrorAction SilentlyContinue)) {
        if (-not (Get-Command "python" -ErrorAction SilentlyContinue)) {
            throw "Missing required scan tool: pip-audit (or python with pip_audit installed)"
        }
        & python -c "import pip_audit" 2>$null
        if ($LASTEXITCODE -ne 0) {
            throw "Missing required scan tool: install pip-audit with 'python -m pip install pip-audit'"
        }
        $pipAuditViaModule = $true
    }
    foreach ($command in @("syft", "trivy", "docker")) {
        if (-not (Get-Command $command -ErrorAction SilentlyContinue)) { throw "Missing required scan tool: $command" }
    }
    New-Item -ItemType Directory -Force -Path $OutputPath | Out-Null
    $PipAuditJson = Join-Path $OutputPath "pip-audit.json"
    $SbomJson = Join-Path $OutputPath "worker-sbom.syft.json"
    $cycloneDx = Join-Path $OutputPath "worker-sbom.cdx.json"
    $TrivyJson = Join-Path $OutputPath "worker-trivy.json"
    $trivyVersionJson = Join-Path $OutputPath "trivy-version.json"
    $resolvedRequirements = Join-Path $OutputPath "worker-requirements.lock.txt"
    & docker build -t $Image (Join-Path $PSScriptRoot "..\services\worker")
    if ($LASTEXITCODE -ne 0) { throw "Worker image build failed" }
    & docker run --rm --entrypoint python $Image -m pip check
    if ($LASTEXITCODE -ne 0) { throw "Worker image dependency check failed" }
    $runtimeProbe = "import os, fastapi, multipart, sentence_transformers, torch, pymilvus, minio, redis; import app.main, app.consumer, app.retrieval.hybrid; assert os.getuid() != 0; assert '+cpu' in torch.__version__; assert not torch.cuda.is_available(); print(f'uid={os.getuid()} torch={torch.__version__} imports=ok')"
    & docker run --rm --entrypoint python $Image -c $runtimeProbe
    if ($LASTEXITCODE -ne 0) { throw "Worker image runtime smoke test failed" }
    $freezeOutput = @(& docker run --rm --entrypoint python $Image -m pip freeze --all)
    if ($LASTEXITCODE -ne 0 -or $freezeOutput.Count -eq 0) { throw "Unable to resolve Worker image dependencies" }
    $freezeOutput | Set-Content -Encoding UTF8 $resolvedRequirements
    $pipAuditArgs = @(
        "-r", $resolvedRequirements,
        "--no-deps",
        "--disable-pip",
        "-f", "json",
        "-o", $PipAuditJson,
        "--vulnerability-service", $PipAuditService,
        "--timeout", $PipAuditTimeout
    )
    $pipAuditExitCode = -1
    $pipAuditExecution = if ($pipAuditViaModule) { "python-module" } else { "executable" }
    for ($attempt = 1; $attempt -le $PipAuditAttempts; $attempt++) {
        Remove-Item -LiteralPath $PipAuditJson -Force -ErrorAction SilentlyContinue
        if ($pipAuditViaModule) {
            & python -m pip_audit @pipAuditArgs
        } else {
            & pip-audit @pipAuditArgs
        }
        $pipAuditExitCode = $LASTEXITCODE
        if ($pipAuditExitCode -in @(0, 1) -and (Test-Path $PipAuditJson)) { break }
        if ($attempt -lt $PipAuditAttempts) {
            Write-Warning "pip-audit attempt $attempt did not produce a valid artifact; retrying"
            Start-Sleep -Seconds 1
        }
    }
    if (-not (Test-Path $PipAuditJson) -and -not $DisablePipAuditContainerFallback -and $PipAuditContainerImage) {
        Write-Warning "Host pip-audit did not produce an artifact; using the isolated Docker fallback"
        & docker image inspect $PipAuditContainerImage *> $null
        if ($LASTEXITCODE -ne 0) {
            $pipAuditDockerfile = Join-Path $OutputPath "pip-audit.Dockerfile"
            @"
FROM python:3.13-slim
RUN python -m pip install --no-cache-dir pip-audit==2.10.1
ENTRYPOINT ["python", "-m", "pip_audit"]
"@ | Set-Content -Encoding UTF8 $pipAuditDockerfile
            & docker build -t $PipAuditContainerImage -f $pipAuditDockerfile $OutputPath
            if ($LASTEXITCODE -ne 0) { throw "Unable to build the pip-audit fallback image" }
        }
        Remove-Item -LiteralPath $PipAuditJson -Force -ErrorAction SilentlyContinue
        $auditVolume = "$(Resolve-Path $OutputPath):/scan"
        & docker run --rm --volume $auditVolume $PipAuditContainerImage `
            -r /scan/worker-requirements.lock.txt --no-deps --disable-pip `
            -f json -o /scan/pip-audit.json --vulnerability-service $PipAuditService --timeout $PipAuditTimeout
        $pipAuditExitCode = $LASTEXITCODE
        $pipAuditExecution = "docker:$PipAuditContainerImage"
    }
    if ($pipAuditExitCode -notin @(0, 1)) { throw "pip-audit failed with exit code $pipAuditExitCode" }
    if (-not (Test-Path $PipAuditJson)) { throw "pip-audit did not produce an audit artifact" }
    & syft $Image -o "syft-json=$SbomJson" -o "cyclonedx-json=$cycloneDx"
    if ($LASTEXITCODE -ne 0) { throw "Syft scan failed" }
    $trivyVersionOutput = @(& trivy --version --format json)
    $trivyVersionExitCode = $LASTEXITCODE
    if ($trivyVersionExitCode -ne 0) { throw "Unable to inspect Trivy database metadata" }
    ($trivyVersionOutput -join [Environment]::NewLine) | Set-Content -Encoding UTF8 $trivyVersionJson
    $trivyVersion = Read-Json $trivyVersionJson
    $trivyArgs = @(
        "image",
        "--scanners", "vuln",
        "--severity", "HIGH,CRITICAL",
        "--format", "json",
        "--output", $TrivyJson,
        "--timeout", $TrivyTimeout
    )
    if ($TrivySkipDbUpdate) { $trivyArgs += "--skip-db-update" }
    foreach ($repository in $TrivyDbRepository) {
        if ($repository) { $trivyArgs += @("--db-repository", $repository) }
    }
    $trivyArgs += $Image
    & trivy @trivyArgs
    if ($LASTEXITCODE -ne 0) { throw "Trivy scan failed" }
    $ImageSizeBytes = [long](& docker image inspect $Image --format "{{.Size}}")
    if ($LASTEXITCODE -ne 0) { throw "Unable to inspect Worker image size" }
    $imageDigest = [string](& docker image inspect $Image --format "{{.Id}}")
    if ($LASTEXITCODE -ne 0 -or -not $imageDigest) { throw "Unable to inspect Worker image digest" }
    Assert-Policy
    $trivy = Read-Json $TrivyJson
    $high = @($trivy.Results | ForEach-Object { $_.Vulnerabilities } | Where-Object { $_.Severity -eq "HIGH" })
    $critical = @($trivy.Results | ForEach-Object { $_.Vulnerabilities } | Where-Object { $_.Severity -eq "CRITICAL" })
    $acceptedExceptions = if ($HighExceptionPath) { @((Read-Json $HighExceptionPath).findings).Count } else { 0 }
    $releaseLabel = Get-ImageRelease
    if (-not $releaseLabel) { $releaseLabel = "Release" }
    $releaseLabel = $releaseLabel.ToUpperInvariant()
    @"
# $releaseLabel Release Scan

- Generated: $((Get-Date).ToUniversalTime().ToString("o"))
- Image: $Image
- Image digest: $imageDigest
- Image size: $ImageSizeBytes bytes
- Image budget: $ImageBudgetBytes bytes
- pip-audit execution: $pipAuditExecution
- Trivy version: $($trivyVersion.Version)
- Trivy vulnerability DB updated: $($trivyVersion.VulnerabilityDB.UpdatedAt)
- Trivy skip DB update: $TrivySkipDbUpdate
- CRITICAL findings: $($critical.Count)
- HIGH findings: $($high.Count)
- Accepted exception findings: $acceptedExceptions
- Exception file: $HighExceptionPath
- License deny list: deploy/license-denylist.txt
- SBOM: worker-sbom.cdx.json
"@ | Set-Content -Encoding UTF8 (Join-Path $OutputPath "summary.md")
    Write-Host "Release scan passed: $OutputPath"
    exit 0
} catch {
    Write-Error $_
    exit 1
}
