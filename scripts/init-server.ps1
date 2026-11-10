#Requires -Version 5.1
<# .SYNOPSIS 生成服务器独立密钥；已有 .env 时不覆盖。 #>
[CmdletBinding()]
param()
$ErrorActionPreference = 'Stop'
$target = Join-Path (Split-Path $PSScriptRoot -Parent) '.env'
if (Test-Path -LiteralPath $target) { Write-Host '.env already exists; retained without changes.'; exit 0 }
function New-RandomBytes([int]$Length) {
    $bytes = New-Object byte[] $Length
    $random = [System.Security.Cryptography.RandomNumberGenerator]::Create()
    try { $random.GetBytes($bytes) } finally { $random.Dispose() }
    return ,$bytes
}
function New-HexSecret { return [BitConverter]::ToString((New-RandomBytes 24)).Replace('-', '').ToLowerInvariant() }
$content = @"
ADMIN_USERNAME=admin
ADMIN_PASSWORD=$(New-HexSecret)
REPORT_TOKEN=$(New-HexSecret)
APP_ENCRYPTION_KEY=$([Convert]::ToBase64String((New-RandomBytes 32)))
DB_PASSWORD=$(New-HexSecret)
APP_PORT=18080
BIND_ADDRESS=127.0.0.1
COOKIE_SECURE=false
OFFLINE_SECONDS=900
DDNS_SYNC_SECONDS=60
IMAGE_PREFIX=ghcr.io/angelinalain/ip-reporter-server
IMAGE_TAG=1.0.2
CLIENT_GITHUB_REPOSITORY=AngelinaLain/ip-reporter-client
CLIENT_SYNC_INTERVAL_SECONDS=3600
CLIENT_KEEP_RELEASES=2
"@
# 在写入密码之前限制权限；文件创建使用 CreateNew，避免并发覆盖已有配置。
$configStream = New-Object System.IO.FileStream($target, [System.IO.FileMode]::CreateNew, [System.IO.FileAccess]::Write, [System.IO.FileShare]::None)
try {
    $acl = New-Object System.Security.AccessControl.FileSecurity
    $acl.SetAccessRuleProtection($true, $false)
    foreach ($sid in @([System.Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18', 'S-1-5-32-544')) {
        $identity = New-Object System.Security.Principal.SecurityIdentifier($sid)
        $acl.AddAccessRule((New-Object System.Security.AccessControl.FileSystemAccessRule($identity, [System.Security.AccessControl.FileSystemRights]::FullControl, [System.Security.AccessControl.AccessControlType]::Allow)))
    }
    $configFile = New-Object System.IO.FileInfo($target)
    if ($PSVersionTable.PSVersion.Major -ge 6) {
        [System.IO.FileSystemAclExtensions]::SetAccessControl($configFile, $acl)
    } else {
        $configFile.SetAccessControl($acl)
    }
    $bytes = (New-Object System.Text.UTF8Encoding($false)).GetBytes($content + "`n")
    $configStream.Write($bytes, 0, $bytes.Length)
    $configStream.Flush()
} finally { if ($null -ne $configStream) { $configStream.Dispose() } }
Write-Host 'Created .env with independent random secrets. Read ADMIN_PASSWORD locally to log in.'
Write-Host 'Start: docker compose pull; docker compose up -d'
Write-Host 'Console: http://127.0.0.1:18080'
