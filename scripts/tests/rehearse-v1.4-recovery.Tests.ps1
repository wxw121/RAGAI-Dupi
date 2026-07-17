Describe "V1.4 recovery rehearsal guardrails" {
    BeforeAll {
        $script:scriptPath = Join-Path $PSScriptRoot "..\rehearse-v1.4-recovery.ps1"
    }

    It "requires at least two fixture documents" {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidateFixtureOnly -FixtureDocumentCount 1 2>$null
        $LASTEXITCODE | Should -Not -Be 0
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidateFixtureOnly -FixtureDocumentCount 2
        $LASTEXITCODE | Should -Be 0
    }

    It "rejects incomplete and corrupt manifests" {
        $incomplete = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes('{"schemaVersion":1}'))
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidateManifestOnly -ManifestBase64 $incomplete 2>$null
        $LASTEXITCODE | Should -Not -Be 0
        $manifest = '{"schemaVersion":1,"itemCount":2,"totalBytes":8,"manifestChecksum":"abc","checksumValid":false,"items":[{"itemKey":"object:a","sha256":"aa"},{"itemKey":"vector:dense","sha256":"bb"}]}'
        $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($manifest))
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidateManifestOnly -ManifestBase64 $encoded 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "accepts complete source target and vector evidence" {
        $manifest = '{"schemaVersion":1,"itemCount":2,"totalBytes":8,"manifestChecksum":"abc","checksumValid":true,"items":[{"itemKey":"object:a","sha256":"aa"},{"itemKey":"vector:dense","sha256":"bb"}]}'
        $encoded = [Convert]::ToBase64String([Text.Encoding]::UTF8.GetBytes($manifest))
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidateManifestOnly -ManifestBase64 $encoded
        $LASTEXITCODE | Should -Be 0
    }

    It "only cleans resources created by this rehearsal" {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidateCleanupOnly -ResourceName 'v14-recovery-123'
        $LASTEXITCODE | Should -Be 0
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidateCleanupOnly -ResourceName 'production-kb' 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "emits a redacted artifact schema" {
        $json = & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidateArtifactOnly -ApiKey 'do-not-leak'
        $LASTEXITCODE | Should -Be 0
        $json | Should -Not -Match 'do-not-leak'
        $artifact = $json | ConvertFrom-Json
        $artifact.schemaVersion | Should -Be 1
        ($artifact.evidence.PSObject.Properties.Name -join ',') | Should -Match 'vectorChecksumMatch'
        ($artifact.cleanup.PSObject.Properties.Name -join ',') | Should -Match 'createdResourcesOnly'
    }
}
