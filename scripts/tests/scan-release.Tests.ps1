Describe "Release scan policy" {
    BeforeAll {
        $script:scriptPath = Join-Path $PSScriptRoot "..\scan-release.ps1"
    }

    It "blocks an unfixed critical vulnerability" {
        $fixture = Join-Path $TestDrive "critical.json"
        @{ Results = @(@{ Vulnerabilities = @(@{ VulnerabilityID = "CVE-1"; Severity = "CRITICAL"; FixedVersion = "" }) }) } |
            ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $fixture
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -PolicyOnly -TrivyJson $fixture 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "blocks a denied license" {
        $fixture = Join-Path $TestDrive "sbom.json"
        @{ artifacts = @(@{ name = "bad"; licenses = @(@{ value = "GPL-3.0" }) }) } |
            ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $fixture
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -PolicyOnly -SbomJson $fixture 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "blocks a vulnerable Python dependency" {
        $fixture = Join-Path $TestDrive "pip-audit.json"
        @(@{ name = "torch"; version = "1.0"; vulns = @(@{ id = "PYSEC-1"; fix_versions = @("2.0") }) }) |
            ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $fixture
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -PolicyOnly -PipAuditJson $fixture 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "blocks a vulnerable Python dependency from current pip-audit JSON" {
        $fixture = Join-Path $TestDrive "pip-audit-current.json"
        @{
            dependencies = @(
                @{
                    name = "torch"
                    version = "2.5.1"
                    vulns = @(@{ id = "PYSEC-2"; fix_versions = @("2.6.0") })
                }
            )
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $fixture
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -PolicyOnly -PipAuditJson $fixture 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "accepts clean current pip-audit JSON" {
        $fixture = Join-Path $TestDrive "pip-audit-current-clean.json"
        @{
            dependencies = @(
                @{ name = "fastapi"; version = "0.115.0"; vulns = @() },
                @{ name = "uvicorn"; version = "0.30.0" }
            )
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $fixture
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -PolicyOnly -PipAuditJson $fixture
        $LASTEXITCODE | Should -Be 0
    }

    It "accepts local scan mirror override parameters in policy mode" {
        $fixture = Join-Path $TestDrive "pip-audit-current-clean.json"
        @{ dependencies = @(@{ name = "fastapi"; version = "0.115.0"; vulns = @() }) } |
            ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $fixture
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -PolicyOnly -PipAuditJson $fixture -PipAuditIndexUrl "https://pypi.tuna.tsinghua.edu.cn/simple" -PipAuditExtraIndexUrl "https://download.pytorch.org/whl/cpu" -TrivyDbRepository "ghcr.io/aquasecurity/trivy-db:2" -TrivyTimeout "15m" -TrivySkipDbUpdate
        $LASTEXITCODE | Should -Be 0
    }

    It "rejects a failed pip audit instead of reusing a stale artifact" {
        $bin = Join-Path $TestDrive "failed-audit-bin"
        $output = Join-Path $TestDrive "failed-audit-output"
        New-Item -ItemType Directory -Force $bin, $output | Out-Null
        foreach ($command in @("pip-audit", "syft", "trivy")) {
            "@echo off`r`nexit /b 1" | Set-Content -Encoding ASCII (Join-Path $bin "$command.cmd")
        }
        @"
@echo off
if "%1"=="build" exit /b 0
if "%1"=="run" (
  echo %* | findstr /C:"freeze" >NUL
  if not errorlevel 1 echo safe-package==1.0
  exit /b 0
)
exit /b 1
"@ | Set-Content -Encoding ASCII (Join-Path $bin "docker.cmd")
        $staleAudit = Join-Path $output "pip-audit.json"
        '{"dependencies":[]}' | Set-Content -Encoding UTF8 $staleAudit
        $originalPath = $env:PATH
        try {
            $env:PATH = "$bin;$originalPath"
            $result = & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -OutputPath $output -DisablePipAuditContainerFallback 2>&1
        } finally {
            $env:PATH = $originalPath
        }

        $LASTEXITCODE | Should -Not -Be 0
        ($result -join "`n") | Should -Match "audit artifact"
        Test-Path $staleAudit | Should -Be $false
    }

    It "accepts clean findings within the image budget" {
        $trivy = Join-Path $TestDrive "clean.json"
        $sbom = Join-Path $TestDrive "clean-sbom.json"
        @{ Results = @() } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $trivy
        @{ artifacts = @() } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $sbom
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -PolicyOnly -TrivyJson $trivy -SbomJson $sbom -ImageSizeBytes 1000
        $LASTEXITCODE | Should -Be 0
    }

    It "accepts exact unexpired exceptions for unresolved findings" {
        $pipAudit = Join-Path $TestDrive "pip-audit-exception.json"
        $trivy = Join-Path $TestDrive "trivy-exception.json"
        $exception = Join-Path $TestDrive "release-exception.json"
        @{
            dependencies = @(
                @{
                    name = "torch"
                    version = "2.10.0"
                    vulns = @(@{ id = "PYSEC-1"; fix_versions = @() })
                }
            )
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $pipAudit
        @{
            Results = @(
                @{
                    Vulnerabilities = @(
                        @{
                            VulnerabilityID = "CVE-1"
                            PkgName = "perl-base"
                            Severity = "CRITICAL"
                            FixedVersion = ""
                        }
                    )
                }
            )
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $trivy
        @{
            release = "v1.4.0"
            expiresAt = "2099-01-01T00:00:00Z"
            findings = @(
                @{ source = "pip-audit"; package = "torch"; id = "PYSEC-1"; reason = "No upstream fix" },
                @{ source = "trivy"; package = "perl-base"; id = "CVE-1"; reason = "No upstream fix" }
            )
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $exception

        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -PolicyOnly -PipAuditJson $pipAudit -TrivyJson $trivy -HighExceptionPath $exception
        $LASTEXITCODE | Should -Be 0
    }

    It "rejects a fixable high vulnerability even when it is listed as an exception" {
        $trivy = Join-Path $TestDrive "trivy-fixable-high.json"
        $exception = Join-Path $TestDrive "release-exception-fixable-high.json"
        @{
            Results = @(
                @{
                    Vulnerabilities = @(
                        @{
                            VulnerabilityID = "CVE-FIXED"
                            PkgName = "openssl"
                            Severity = "HIGH"
                            FixedVersion = "3.0.2"
                        }
                    )
                }
            )
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $trivy
        @{
            release = "v1.4.0"
            expiresAt = "2099-01-01T00:00:00Z"
            findings = @(
                @{ source = "trivy"; package = "openssl"; id = "CVE-FIXED"; reason = "Must not bypass a published fix" }
            )
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $exception

        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -PolicyOnly -TrivyJson $trivy -HighExceptionPath $exception 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "rejects an exception issued for a different image release" {
        $trivy = Join-Path $TestDrive "trivy-wrong-release.json"
        $exception = Join-Path $TestDrive "release-exception-wrong-release.json"
        @{
            Results = @(
                @{
                    Vulnerabilities = @(
                        @{
                            VulnerabilityID = "CVE-UNFIXED"
                            PkgName = "perl-base"
                            Severity = "HIGH"
                            FixedVersion = ""
                        }
                    )
                }
            )
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $trivy
        @{
            release = "v1.3.0"
            expiresAt = "2099-01-01T00:00:00Z"
            findings = @(
                @{ source = "trivy"; package = "perl-base"; id = "CVE-UNFIXED"; reason = "Wrong release" }
            )
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $exception

        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -PolicyOnly -TrivyJson $trivy -HighExceptionPath $exception 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "rejects an expired release exception" {
        $pipAudit = Join-Path $TestDrive "pip-audit-expired.json"
        $exception = Join-Path $TestDrive "release-exception-expired.json"
        @{
            dependencies = @(
                @{
                    name = "torch"
                    version = "2.10.0"
                    vulns = @(@{ id = "PYSEC-1"; fix_versions = @() })
                }
            )
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $pipAudit
        @{
            release = "v1.4.0"
            expiresAt = "2000-01-01T00:00:00Z"
            findings = @(
                @{ source = "pip-audit"; package = "torch"; id = "PYSEC-1"; reason = "Expired" }
            )
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $exception

        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -PolicyOnly -PipAuditJson $pipAudit -HighExceptionPath $exception 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "ignores denied licenses from operating-system packages" {
        $fixture = Join-Path $TestDrive "os-sbom.json"
        @{
            artifacts = @(
                @{
                    name = "sysvinit-utils"
                    version = "3.06-4"
                    type = "deb"
                    licenses = @(@{ value = "GPL-3.0" })
                }
            )
        } | ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $fixture

        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -PolicyOnly -SbomJson $fixture
        $LASTEXITCODE | Should -Be 0
    }
}
