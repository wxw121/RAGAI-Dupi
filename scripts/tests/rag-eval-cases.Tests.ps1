Describe "RAG evaluation case manifest" {
    BeforeAll {
        $script:scriptPath = Join-Path $PSScriptRoot "..\rag-eval-cases.ps1"
        $script:manifestPath = Join-Path $PSScriptRoot "..\..\benchmarks\v1.6b\retrieval-cases.json"
        $script:corpusPath = Join-Path $PSScriptRoot "..\..\benchmarks\v1.6b\corpus"
    }

    It "contains one hundred cases with the required V1.6b distribution" {
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ManifestPath $script:manifestPath -ValidateOnly
        $LASTEXITCODE | Should -Be 0
        $parsed = Get-Content -Raw -Encoding UTF8 $script:manifestPath | ConvertFrom-Json
        $cases = @($parsed | ForEach-Object { $_ })
        $cases.Count | Should -Be 100
        @($cases.category | Sort-Object -Unique) -join "," | Should -Be "AMBIGUOUS,HARD_NEGATIVE,MULTI_DOCUMENT,REAL_QUERY"
        @($cases | Where-Object category -eq "REAL_QUERY").Count | Should -Be 40
        @($cases | Where-Object category -eq "HARD_NEGATIVE").Count | Should -Be 20
        @($cases | Where-Object category -eq "MULTI_DOCUMENT").Count | Should -Be 20
        @($cases | Where-Object category -eq "AMBIGUOUS").Count | Should -Be 20
        @($cases.caseKey | Sort-Object -Unique).Count | Should -Be $cases.Count
        @("formats-supported", "core-capabilities", "chunk-strategies") | ForEach-Object {
            $cases.caseKey | Should -Contain $_
        }
    }

    It "rejects a no-answer case that requires hits" {
        $invalid = Join-Path $TestDrive "invalid-no-answer.json"
        @(@{ caseKey = "none"; category = "HARD_NEGATIVE"; query = "unknown"; minHits = 1; topK = 5; expectedFileNames = @(); mustContainAny = @() }) |
            ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $invalid
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ManifestPath $invalid -ValidateOnly 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "rejects a multi-document case without two expected sources" {
        $invalid = Join-Path $TestDrive "invalid-multi-document.json"
        @(@{ caseKey = "multi"; category = "MULTI_DOCUMENT"; query = "compare"; minHits = 2; topK = 5; expectedFileName = "one.md"; expectedFileNames = @(); mustContainAny = @("fact") }) |
            ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $invalid
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ManifestPath $invalid -ValidateOnly 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "rejects an ambiguous case without disambiguation evidence" {
        $invalid = Join-Path $TestDrive "invalid-ambiguous.json"
        @(@{ caseKey = "ambiguous"; category = "AMBIGUOUS"; query = "Which version?"; minHits = 1; topK = 5; expectedFileName = "current.md"; expectedFileNames = @(); mustContainAny = @() }) |
            ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $invalid
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ManifestPath $invalid -ValidateOnly 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "rejects a real-query case with zero minimum hits even when tokens are present" {
        $invalid = Join-Path $TestDrive "invalid-real-query.json"
        $cases = @()
        for ($i = 0; $i -lt 40; $i++) {
            $cases += @{ caseKey = "real-$i"; category = "REAL_QUERY"; query = "real query $i"; minHits = 1; topK = 5; expectedFileName = "product-overview.md"; expectedFileNames = @(); mustContainAny = @("PDF") }
        }
        for ($i = 0; $i -lt 20; $i++) {
            $cases += @{ caseKey = "hard-$i"; category = "HARD_NEGATIVE"; query = "hard query $i"; minHits = 0; topK = 5; expectedFileName = $null; expectedFileNames = @(); mustContainAny = @() }
        }
        for ($i = 0; $i -lt 20; $i++) {
            $cases += @{ caseKey = "multi-$i"; category = "MULTI_DOCUMENT"; query = "multi query $i"; minHits = 2; topK = 5; expectedFileName = "release-runbook.md"; expectedFileNames = @("recovery-runbook.md"); mustContainAny = @("benchmark") }
        }
        for ($i = 0; $i -lt 20; $i++) {
            $cases += @{ caseKey = "amb-$i"; category = "AMBIGUOUS"; query = "amb query $i"; minHits = 1; topK = 5; expectedFileName = "release-runbook.md"; expectedFileNames = @(); mustContainAny = @("2.5.4") }
        }
        $cases[0].minHits = 0
        $cases[0].mustContainAny = @("PDF")
        $cases | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $invalid
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ManifestPath $invalid -CorpusPath $script:corpusPath -ValidateOnly 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "rejects manifests without the exact V1.6b category distribution" {
        $invalid = Join-Path $TestDrive "invalid-distribution.json"
        $cases = @()
        for ($i = 0; $i -lt 41; $i++) {
            $cases += @{ caseKey = "real-$i"; category = "REAL_QUERY"; query = "real query $i"; minHits = 1; topK = 5; expectedFileName = "product-overview.md"; expectedFileNames = @(); mustContainAny = @("PDF") }
        }
        for ($i = 0; $i -lt 19; $i++) {
            $cases += @{ caseKey = "hard-$i"; category = "HARD_NEGATIVE"; query = "hard query $i"; minHits = 0; topK = 5; expectedFileName = $null; expectedFileNames = @(); mustContainAny = @() }
        }
        for ($i = 0; $i -lt 20; $i++) {
            $cases += @{ caseKey = "multi-$i"; category = "MULTI_DOCUMENT"; query = "multi query $i"; minHits = 2; topK = 5; expectedFileName = "release-runbook.md"; expectedFileNames = @("recovery-runbook.md"); mustContainAny = @("benchmark") }
        }
        for ($i = 0; $i -lt 20; $i++) {
            $cases += @{ caseKey = "amb-$i"; category = "AMBIGUOUS"; query = "amb query $i"; minHits = 1; topK = 5; expectedFileName = "release-runbook.md"; expectedFileNames = @(); mustContainAny = @("2.5.4") }
        }
        $cases | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $invalid
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ManifestPath $invalid -CorpusPath $script:corpusPath -ValidateOnly 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "rejects manifests that reference expected sources missing from the corpus" {
        $invalid = Join-Path $TestDrive "invalid-missing-source.json"
        $cases = @()
        for ($i = 0; $i -lt 40; $i++) {
            $source = if ($i -eq 0) { "missing-source.md" } else { "product-overview.md" }
            $cases += @{ caseKey = "real-$i"; category = "REAL_QUERY"; query = "real query $i"; minHits = 1; topK = 5; expectedFileName = $source; expectedFileNames = @(); mustContainAny = @("PDF") }
        }
        for ($i = 0; $i -lt 20; $i++) {
            $cases += @{ caseKey = "hard-$i"; category = "HARD_NEGATIVE"; query = "hard query $i"; minHits = 0; topK = 5; expectedFileName = $null; expectedFileNames = @(); mustContainAny = @() }
        }
        for ($i = 0; $i -lt 20; $i++) {
            $cases += @{ caseKey = "multi-$i"; category = "MULTI_DOCUMENT"; query = "multi query $i"; minHits = 2; topK = 5; expectedFileName = "release-runbook.md"; expectedFileNames = @("recovery-runbook.md"); mustContainAny = @("benchmark") }
        }
        for ($i = 0; $i -lt 20; $i++) {
            $cases += @{ caseKey = "amb-$i"; category = "AMBIGUOUS"; query = "amb query $i"; minHits = 1; topK = 5; expectedFileName = "release-runbook.md"; expectedFileNames = @(); mustContainAny = @("2.5.4") }
        }
        $cases | ConvertTo-Json -Depth 5 | Set-Content -Encoding UTF8 $invalid
        & powershell -NoProfile -ExecutionPolicy Bypass -File $script:scriptPath -ManifestPath $invalid -CorpusPath $script:corpusPath -ValidateOnly 2>$null
        $LASTEXITCODE | Should -Not -Be 0
    }

    It "ships the current and conflicting legacy corpus files" {
        $corpus = $script:corpusPath
        foreach ($required in @("product-overview.md", "release-runbook.md", "recovery-runbook.md", "security-governance.md", "ingestion-operations.md", "retrieval-operations.md", "legacy-release-notes.md")) {
            Test-Path (Join-Path $corpus $required) | Should -Be $true
        }
        $parsed = Get-Content -Raw -Encoding UTF8 $script:manifestPath | ConvertFrom-Json
        $expectedFiles = @($parsed.expectedFileName) + @($parsed.expectedFileNames | ForEach-Object { $_ })
        foreach ($fileName in @($expectedFiles | Where-Object { $_ } | Sort-Object -Unique)) {
            Test-Path (Join-Path $corpus $fileName) | Should -Be $true
        }
    }
}
