Describe "RAG evaluation case manifest" {
    BeforeAll {
        $script:scriptPath = Join-Path $PSScriptRoot "..\rag-eval-cases.ps1"
        $script:manifestPath = Join-Path $PSScriptRoot "..\..\benchmarks\v1.3\retrieval-cases.json"
        $script:corpusPath = Join-Path $PSScriptRoot "..\..\benchmarks\v1.3\corpus"
    }

    It "contains at least thirty cases across all required categories" {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ManifestPath $script:manifestPath -ValidateOnly
        $LASTEXITCODE | Should -Be 0
        $parsed = Get-Content -Raw -Encoding UTF8 $script:manifestPath | ConvertFrom-Json
        $cases = @($parsed | ForEach-Object { $_ })
        $cases.Count | Should -BeGreaterThan 29
        @($cases.category | Sort-Object -Unique) -join "," | Should -Be "conflict,en,exact,no_answer,semantic,zh"
        @($cases.caseKey | Sort-Object -Unique).Count | Should -Be $cases.Count
    }

    It "rejects a no-answer case that requires hits" {
        $invalid = Join-Path $TestDrive "invalid-no-answer.json"
        @(@{ caseKey = "none"; category = "no_answer"; query = "unknown"; minHits = 1; topK = 5; mustContainAny = @() }) |
            ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $invalid
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ManifestPath $invalid -ValidateOnly 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "rejects a conflict case without source and disambiguation evidence" {
        $invalid = Join-Path $TestDrive "invalid-conflict.json"
        @(@{ caseKey = "conflict"; category = "conflict"; query = "version"; minHits = 1; topK = 5; mustContainAny = @() }) |
            ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $invalid
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ManifestPath $invalid -ValidateOnly 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "ships the current and conflicting legacy corpus files" {
        $corpus = $script:corpusPath
        Test-Path (Join-Path $corpus "release-runbook.md") | Should -Be $true
        Test-Path (Join-Path $corpus "legacy-release-notes.md") | Should -Be $true
        $parsed = Get-Content -Raw -Encoding UTF8 $script:manifestPath | ConvertFrom-Json
        foreach ($fileName in @($parsed.expectedFileName | Where-Object { $_ } | Sort-Object -Unique)) {
            Test-Path (Join-Path $corpus $fileName) | Should -Be $true
        }
    }
}
