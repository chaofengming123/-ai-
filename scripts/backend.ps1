[CmdletBinding()]
param(
    [Parameter(ValueFromRemainingArguments = $true)]
    [string[]] $MavenArguments
)

$ErrorActionPreference = 'Stop'

$projectDir = (Resolve-Path (Join-Path $PSScriptRoot '..')).Path
$envFile = Join-Path $projectDir 'docker\.env'

if (-not (Test-Path -LiteralPath $envFile -PathType Leaf)) {
    throw 'Run "python scripts/init-db-env.py" first.'
}

foreach ($line in Get-Content -LiteralPath $envFile) {
    $trimmed = $line.Trim()
    if (-not $trimmed -or $trimmed.StartsWith('#')) {
        continue
    }

    $separator = $line.IndexOf('=')
    if ($separator -le 0) {
        throw 'Invalid entry in docker/.env: expected NAME=VALUE.'
    }

    $name = $line.Substring(0, $separator).Trim()
    $value = $line.Substring($separator + 1).Trim()
    if ($value.Length -ge 2 -and (
        ($value.StartsWith("'") -and $value.EndsWith("'")) -or
        ($value.StartsWith('"') -and $value.EndsWith('"'))
    )) {
        $value = $value.Substring(1, $value.Length - 2)
    }
    [Environment]::SetEnvironmentVariable($name, $value, 'Process')
}

$javaCommand = Get-Command java -ErrorAction SilentlyContinue
if (-not $javaCommand) {
    throw 'Java 17 is required, but the java command was not found.'
}

# Java writes version information to stderr even on success. Merge the streams
# inside cmd.exe so Windows PowerShell 5.1 does not raise NativeCommandError.
$javaVersionOutput = (& $env:ComSpec /d /c 'java -version 2>&1' | Out-String)
$javaVersionExitCode = $LASTEXITCODE
if ($javaVersionExitCode -ne 0 -or $javaVersionOutput -notmatch 'version "(?:1\.)?(\d+)') {
    throw "Unable to determine the Java version.`n$javaVersionOutput"
}
if ([int] $Matches[1] -ne 17) {
    throw "Java 17 is required, but the current java command is:`n$javaVersionOutput"
}

if (-not $env:MAVEN_USER_HOME -and $env:USERPROFILE) {
    $env:MAVEN_USER_HOME = Join-Path $env:USERPROFILE '.m2'
}

Push-Location (Join-Path $projectDir 'backend')
try {
    & '.\mvnw.cmd' @MavenArguments
    $mavenExitCode = $LASTEXITCODE
}
finally {
    Pop-Location
}

exit $mavenExitCode
