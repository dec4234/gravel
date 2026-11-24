# Installs the Gravel MCP server for Claude Code in user scope (all projects).
# Usage: powershell -ExecutionPolicy Bypass -File install-mcp.ps1 [-SkipBuild]
param(
	[switch]$SkipBuild
)

$ErrorActionPreference = 'Stop'
$ProjectDir = $PSScriptRoot
$Jar = Join-Path $ProjectDir 'mcp-server\build\libs\gravel-mcp.jar'

foreach ($cmd in @('java', 'claude')) {
	if (-not (Get-Command $cmd -ErrorAction SilentlyContinue)) {
		Write-Error "'$cmd' is not on PATH. Install it, then run this script again."
	}
}

if (-not $SkipBuild) {
	Write-Host 'Building the MCP server jar...'
	& (Join-Path $ProjectDir 'gradlew.bat') -p $ProjectDir ':mcp-server:jar'
	if ($LASTEXITCODE -ne 0) { Write-Error 'Gradle build failed.' }
}

if (-not (Test-Path $Jar)) {
	Write-Error "Jar not found: $Jar. Run without -SkipBuild."
}

# Remove an old user-scope entry, if there is one. Ignore the error if not.
& claude mcp remove --scope user gravel *> $null

Write-Host 'Adding the gravel MCP server in user scope...'
& claude mcp add --scope user gravel -e "GRAVEL_PROJECT_DIR=$ProjectDir" -- java -jar "$Jar"
if ($LASTEXITCODE -ne 0) { Write-Error 'claude mcp add failed.' }

Write-Host ''
Write-Host 'Done. Restart Claude Code to load the gravel server.'
Write-Host "Project dir: $ProjectDir"
