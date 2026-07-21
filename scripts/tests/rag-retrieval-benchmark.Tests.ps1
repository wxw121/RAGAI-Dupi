Describe "RAG retrieval benchmark artifact" {
    BeforeAll {
        $script:scriptPath = Join-Path $PSScriptRoot "..\rag-retrieval-benchmark.ps1"
        $script:manifestPath = Join-Path $PSScriptRoot "..\..\benchmarks\v1.6b\retrieval-cases.json"
        $script:corpusPath = Join-Path $PSScriptRoot "..\..\benchmarks\v1.6b\corpus"
        $parsedManifest = Get-Content -Raw -Encoding UTF8 $script:manifestPath | ConvertFrom-Json
        $script:manifestCases = @($parsedManifest | ForEach-Object { $_ })
        $global:RagBenchmarkCaseResponsesForTest = @($script:manifestCases | ForEach-Object {
            [pscustomobject]@{ id = "case-$($_.caseKey)"; caseKey = $_.caseKey; category = $_.category }
        })
        $global:RagBenchmarkDocumentResponsesForTest = @(Get-ChildItem -File $script:corpusPath | ForEach-Object {
            [pscustomobject]@{ fileName = $_.Name; fileSize = $_.Length; status = "COMPLETED" }
        })
    }

    It "includes experiment metadata and release gate rollups in per-run artifacts" {
        $outputPath = Join-Path $TestDrive "benchmark.json"
        $global:RagBenchmarkRunBodiesForTest = @()

        Mock -CommandName Invoke-RestMethod -MockWith {
            param($Method, $Uri, $Headers, $ContentType, $Body)

            if ($Method -eq "Get" -and $Uri -like "*/documents") { return $global:RagBenchmarkDocumentResponsesForTest }
            if ($Method -eq "Get" -and $Uri -like "*/rag-eval/cases") { return $global:RagBenchmarkCaseResponsesForTest }
            if ($Method -eq "Patch" -and $Uri -like "*/rag-eval/cases/*") { return [pscustomobject]@{ ok = $true } }
            if ($Method -eq "Post" -and $Uri -like "*/rag-eval/runs") {
                $bodyObject = $Body | ConvertFrom-Json
                $global:RagBenchmarkRunBodiesForTest += ,$bodyObject
                $mode = if ($bodyObject.useRerank -eq $true) { "hybrid_rerank" } elseif ($bodyObject.retrievalMode -eq "HYBRID") { "hybrid" } else { "vector" }
                return [pscustomobject]@{
                    id = "run-$mode"
                    gateStatus = "PASS"
                    profileSnapshot = [pscustomobject]@{
                        retrievalMode = $mode
                        rerankEnabled = ($bodyObject.useRerank -eq $true)
                        experimentLabel = $bodyObject.experimentLabel
                        topKOverride = $bodyObject.topKOverride
                    }
                    metrics = [pscustomobject]@{
                        releaseGate = [pscustomobject]@{ status = "PASS"; requiredPassRate = 0.95; actualPassRate = 1.0 }
                    }
                    results = @(
                        [pscustomobject]@{
                            caseKey = "formats-supported"; category = "REAL_QUERY"; passed = $true; hitCount = 1; latencyMs = 12
                            failureCategories = @(); expectedFileName = "product-overview.md"; expectedFileNames = @("product-overview.md")
                            matchedFileNames = @("product-overview.md"); retrievalMode = $mode; fallbackReason = $null
                            matchedRank = 1; vectorRank = 1; sparseRank = 1; fusionRank = 1
                            rerankRank = $(if ($bodyObject.useRerank -eq $true) { 1 } else { $null })
                        }
                    )
                }
            }
            throw "Unexpected API call: $Method $Uri"
        }

        & $script:scriptPath `
            -KbId "kb-1" `
            -HybridProfileId "hybrid-profile" `
            -RerankProfileId "rerank-profile" `
            -BaseUrl "http://example.test" `
            -OutputPath $outputPath `
            -ManifestPath $script:manifestPath `
            -CorpusPath $script:corpusPath `
            -WarmIterations 0 `
            -SkipCaseReconcile `
            -ExperimentLabel "candidate-topk-8" `
            -TopKOverride 8

        $artifact = Get-Content -Raw -Encoding UTF8 $outputPath | ConvertFrom-Json
        $global:RagBenchmarkRunBodiesForTest.Count | Should -Be 3
        @($global:RagBenchmarkRunBodiesForTest | Where-Object { $_.experimentLabel -ne "candidate-topk-8" }).Count | Should -Be 0
        @($global:RagBenchmarkRunBodiesForTest | Where-Object { $_.topKOverride -ne 8 }).Count | Should -Be 0
        @($artifact.results | Where-Object { $_.experimentLabel -ne "candidate-topk-8" }).Count | Should -Be 0
        @($artifact.results | Where-Object { $_.topKOverride -ne 8 }).Count | Should -Be 0
        @($artifact.results | Where-Object { $_.releaseGate.status -ne "PASS" }).Count | Should -Be 0
    }
}
