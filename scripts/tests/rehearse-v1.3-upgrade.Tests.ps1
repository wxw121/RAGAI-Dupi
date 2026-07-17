Describe "V1.3 upgrade rehearsal guardrails" {
    BeforeAll {
        $script:scriptPath = Join-Path $PSScriptRoot "..\rehearse-v1.3-upgrade.ps1"
    }

    It "refuses production without explicit acknowledgement" {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -Environment production -ValidateTargetOnly 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "accepts an acknowledged rehearsal target" {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -Environment rehearsal -ValidateTargetOnly
        $LASTEXITCODE | Should -Be 0
    }

    It "refuses upgrade when backup verification failed" {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidateBackupOnly 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "restores every backup before starting the upgraded Milvus" {
        $json = & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ValidatePlanOnly
        $LASTEXITCODE | Should -Be 0
        $plan = @($json | ConvertFrom-Json | ForEach-Object { $_ })
        $plan -join ',' | Should -Be 'backup-postgres,backup-etcd,backup-minio,backup-milvus,restore-milvus,restore-etcd,restore-postgres,restore-minio,start-milvus-2.5.4,benchmark,rollback'
    }
}
