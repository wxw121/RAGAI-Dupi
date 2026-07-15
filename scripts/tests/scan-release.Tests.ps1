$scriptPath = Join-Path $PSScriptRoot "..\scan-release.ps1"

Describe "Release scan policy" {
    It "blocks an unfixed critical vulnerability" {
        $fixture = Join-Path $TestDrive "critical.json"
        @{ Results = @(@{ Vulnerabilities = @(@{ VulnerabilityID = "CVE-1"; Severity = "CRITICAL"; FixedVersion = "" }) }) } |
            ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $fixture
        & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -PolicyOnly -TrivyJson $fixture 2>$null
        $LASTEXITCODE | Should Not Be 0
    }

    It "blocks a denied license" {
        $fixture = Join-Path $TestDrive "sbom.json"
        @{ artifacts = @(@{ name = "bad"; licenses = @(@{ value = "GPL-3.0" }) }) } |
            ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $fixture
        & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -PolicyOnly -SbomJson $fixture 2>$null
        $LASTEXITCODE | Should Not Be 0
    }

    It "blocks a vulnerable Python dependency" {
        $fixture = Join-Path $TestDrive "pip-audit.json"
        @(@{ name = "torch"; version = "1.0"; vulns = @(@{ id = "PYSEC-1"; fix_versions = @("2.0") }) }) |
            ConvertTo-Json -Depth 8 | Set-Content -Encoding UTF8 $fixture
        & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -PolicyOnly -PipAuditJson $fixture 2>$null
        $LASTEXITCODE | Should Not Be 0
    }

    It "accepts clean findings within the image budget" {
        $trivy = Join-Path $TestDrive "clean.json"
        $sbom = Join-Path $TestDrive "clean-sbom.json"
        @{ Results = @() } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $trivy
        @{ artifacts = @() } | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $sbom
        & powershell -NoProfile -ExecutionPolicy Bypass -File $scriptPath -PolicyOnly -TrivyJson $trivy -SbomJson $sbom -ImageSizeBytes 1000
        $LASTEXITCODE | Should Be 0
    }
}
