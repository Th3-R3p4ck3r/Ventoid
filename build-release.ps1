# ============================================================
#  build-release.ps1
#  Loads signing credentials from .env (gitignored) and runs
#  a signed release build.
#
#  Usage:
#    powershell -ExecutionPolicy Bypass -File build-release.ps1
# ============================================================

param(
    [string]$Task = "assembleRelease"
)

$ErrorActionPreference = "Stop"
$envFile = Join-Path $PSScriptRoot ".env"

if (-not (Test-Path $envFile)) {
    Write-Host "ERROR: .env not found. Copy .env.example to .env and fill in your signing credentials." -ForegroundColor Red
    exit 1
}

# Read and export every KEY=VALUE line from .env
foreach ($line in Get-Content $envFile) {
    $line = $line.Trim()
    if ($line -eq "" -or $line.StartsWith("#") -or -not $line.Contains("=")) {
        continue
    }
    $key = ($line.Substring(0, $line.IndexOf("="))).Trim()
    $value = ($line.Substring($line.IndexOf("=") + 1)).Trim()
    if ($value.StartsWith('"') -and $value.EndsWith('"')) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    [System.Environment]::SetEnvironmentVariable($key, $value, "Process")
}

# Sanity check that credentials are present
foreach ($required in @("VENTOID_RELEASE_STORE_FILE", "VENTOID_RELEASE_STORE_PASSWORD", "VENTOID_RELEASE_KEY_ALIAS", "VENTOID_RELEASE_KEY_PASSWORD")) {
    if (-not [System.Environment]::GetEnvironmentVariable($required, "Process")) {
        Write-Host "ERROR: $required is missing from .env" -ForegroundColor Red
        exit 1
    }
}

Write-Host "Loaded signing credentials from .env. Running $Task..." -ForegroundColor Green
& (Join-Path $PSScriptRoot "gradlew.bat") $Task
exit $LASTEXITCODE
