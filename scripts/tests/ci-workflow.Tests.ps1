Describe "CI workflow dependency contract" {
    BeforeAll {
        $script:workflowPath = Join-Path $PSScriptRoot "..\..\.github\workflows\ci.yml"
    }

    It "installs and caches Worker runtime and test dependencies" {
        $workflow = Get-Content -Raw -LiteralPath $script:workflowPath

        $workflow | Should -Match 'cache-dependency-path:\s*\|\s*services/worker/requirements\.txt\s+services/worker/requirements-dev\.txt'
        $workflow | Should -Match 'pip install -r requirements\.txt -r requirements-dev\.txt'
    }
}
