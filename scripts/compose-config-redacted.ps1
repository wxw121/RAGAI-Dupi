param(
    [string]$ComposeDir = "deploy"
)

$ErrorActionPreference = "Stop"

Push-Location $ComposeDir
try {
    $json = docker compose config --format json | ConvertFrom-Json
} finally {
    Pop-Location
}

$sensitivePattern = '(?i)(KEY|SECRET|PASSWORD|TOKEN|CREDENTIAL)'

foreach ($service in $json.services.PSObject.Properties) {
    $env = $service.Value.environment
    if ($null -eq $env) {
        continue
    }
    foreach ($item in $env.PSObject.Properties) {
        if ($item.Name -match $sensitivePattern -and -not [string]::IsNullOrEmpty([string]$item.Value)) {
            $item.Value = "<redacted>"
        }
    }
}

$json | ConvertTo-Json -Depth 100
