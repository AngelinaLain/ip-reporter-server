#Requires -Version 5.1
<#
.SYNOPSIS
下载并校验服务器镜像中的 IP Reporter 客户端，再调用 Windows 服务安装器。
.DESCRIPTION
需要管理员 PowerShell。-VerifyOnly 仅检查包，无需管理员权限，不创建服务。
首次安装或 -Reconfigure 需要 -TokenFile，或从终端安全输入 Token。
升级保留已有配置；-Reconfigure 重建配置，可用 -DeviceId 保留原设备标识。
#>
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)][string]$ServerUrl,
    [string]$Endpoint,
    [string]$TokenFile,
    [string]$Tag,
    [ValidateSet('windows-x86_64', 'windows-arm64')][string]$Platform,
    [string]$DeviceId,
    [int]$PollSeconds = 60,
    [int]$HeartbeatSeconds = 300,
    [int]$TimeoutSeconds = 10,
    [switch]$Reconfigure,
    [switch]$AllowHttp,
    [switch]$VerifyOnly
)

$ErrorActionPreference = 'Stop'
$maxPackageBytes = 128MB
$tagPattern = '^v(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\.(0|[1-9][0-9]*)\z'

function Get-ValidatedUri {
    param([string]$Value, [switch]$Server)
    $uri = $null
    if ($Value -cmatch '[^!-~]|[\\#]' -or
        -not [Uri]::TryCreate($Value, [UriKind]::Absolute, [ref]$uri) -or
        $uri.Scheme -notin @('https', 'http') -or -not $uri.Host -or $uri.UserInfo -or
        ($Server -and $Value.Contains('?'))) {
        throw 'Supply an absolute HTTP(S) URL without user information or fragment. The server URL cannot contain a query.'
    }
    if ($uri.Scheme -eq 'http' -and -not $AllowHttp) {
        throw 'HTTPS required. Add -AllowHttp only for a trusted test network.'
    }
    return $uri
}

function Get-NativePlatform {
    # Windows ARM64 的 x64 模拟可能影响旧版 .NET 的 OSArchitecture。
    # 首选 IsWow64Process2 返回的原生处理器类型，旧版 Windows 再回退到 .NET。
    try {
        if (-not ('IpReporter.Bootstrap.NativeArchitecture' -as [type])) {
            Add-Type -TypeDefinition @'
using System;
using System.Runtime.InteropServices;
namespace IpReporter.Bootstrap {
    public static class NativeArchitecture {
        [DllImport("kernel32.dll")]
        private static extern IntPtr GetCurrentProcess();
        [DllImport("kernel32.dll", SetLastError = true)]
        [return: MarshalAs(UnmanagedType.Bool)]
        private static extern bool IsWow64Process2(IntPtr process, out ushort processMachine, out ushort nativeMachine);
        public static ushort GetNativeMachine() {
            ushort processMachine, nativeMachine;
            return IsWow64Process2(GetCurrentProcess(), out processMachine, out nativeMachine) ? nativeMachine : (ushort)0;
        }
    }
}
'@
        }
        switch ([IpReporter.Bootstrap.NativeArchitecture]::GetNativeMachine()) {
            0x8664 { return 'windows-x86_64' }
            0xAA64 { return 'windows-arm64' }
        }
    } catch {
        # IsWow64Process2 在较旧的 Windows 上可能没有导出。
    }
    try { $architecture = [System.Runtime.InteropServices.RuntimeInformation]::OSArchitecture.ToString() }
    catch {
        $architecture = $env:PROCESSOR_ARCHITEW6432
        if (-not $architecture) { $architecture = $env:PROCESSOR_ARCHITECTURE }
    }
    switch ($architecture) {
        'X64'   { return 'windows-x86_64' }
        'AMD64' { return 'windows-x86_64' }
        'Arm64' { return 'windows-arm64' }
        default { throw 'CPU architecture is unavailable in the Windows release catalog.' }
    }
}

function New-PrivateDirectory {
    $parent = [IO.Path]::GetFullPath([IO.Path]::GetTempPath())
    $directory = Join-Path $parent ('ip-reporter-install.' + [Guid]::NewGuid().ToString('N'))
    $acl = New-Object Security.AccessControl.DirectorySecurity
    $acl.SetAccessRuleProtection($true, $false)
    $inherit = [Security.AccessControl.InheritanceFlags]'ContainerInherit,ObjectInherit'
    $identities = @([Security.Principal.WindowsIdentity]::GetCurrent().User.Value, 'S-1-5-18')
    try {
        foreach ($identity in $identities) {
            $sid = New-Object Security.Principal.SecurityIdentifier($identity)
            $rule = New-Object Security.AccessControl.FileSystemAccessRule(
                $sid, [Security.AccessControl.FileSystemRights]::FullControl, $inherit,
                [Security.AccessControl.PropagationFlags]::None, [Security.AccessControl.AccessControlType]::Allow)
            $acl.AddAccessRule($rule)
        }
        if ($PSVersionTable.PSEdition -eq 'Desktop') {
            $null = [IO.Directory]::CreateDirectory($directory, $acl)
        } else {
            $null = [IO.FileSystemAclExtensions]::CreateDirectory($acl, $directory)
        }
    } catch {
        Remove-Item -LiteralPath $directory -Force -ErrorAction SilentlyContinue
        throw
    }
    return $directory
}

function Copy-BoundedStream {
    param([IO.Stream]$InputStream, [string]$Destination, [long]$Limit, [int]$Seconds = 0)
    $output = New-Object IO.FileStream($Destination, [IO.FileMode]::CreateNew, [IO.FileAccess]::Write)
    $buffer = New-Object byte[] 65536
    $total = 0L
    $elapsed = [Diagnostics.Stopwatch]::StartNew()
    try {
        while (($read = $InputStream.Read($buffer, 0, $buffer.Length)) -gt 0) {
            if ($Seconds -gt 0 -and $elapsed.Elapsed.TotalSeconds -gt $Seconds) { throw 'Download exceeded the total time limit.' }
            $total += $read
            if ($total -gt $Limit) { throw 'Stream exceeds the permitted size.' }
            $output.Write($buffer, 0, $read)
        }
    } finally { $output.Dispose() }
    return $total
}

function Receive-File {
    param([string]$Path, [string]$Destination, [long]$Limit)
    # URL 只由固定前缀和已校验的相对文件名生成，请求不携带上报 Token。
    $uri = Get-ValidatedUri ($baseUrl + $Path)
    $request = [Net.HttpWebRequest]::Create($uri)
    $request.AllowAutoRedirect = $false
    $request.Timeout = 15000
    $request.ReadWriteTimeout = 15000
    $request.UserAgent = 'IP-Reporter-Installer/1'
    try { $response = $request.GetResponse() }
    catch [Net.WebException] {
        if ($_.Exception.Response) { $_.Exception.Response.Dispose() }
        throw 'Download failed. Check the server URL and whether the release is mirrored.'
    }
    try {
        if ([int]$response.StatusCode -ne 200) { throw 'The download URL must return HTTP 200 without redirecting.' }
        if ($response.ContentLength -gt $Limit) { throw 'Download exceeds the permitted size.' }
        $stream = $response.GetResponseStream()
        try { return Copy-BoundedStream $stream $Destination $Limit 180 }
        finally { $stream.Dispose() }
    } finally { $response.Dispose() }
}

function Get-Sha256 {
    param([string]$Path)
    $sha256 = [Security.Cryptography.SHA256]::Create()
    try {
        $stream = [IO.File]::OpenRead($Path)
        try { return [BitConverter]::ToString($sha256.ComputeHash($stream)).Replace('-', '').ToLowerInvariant() }
        finally { $stream.Dispose() }
    } finally { $sha256.Dispose() }
}

function Expand-VerifiedZip {
    param([string]$Path, [string]$ExpectedRoot, [string]$Destination)
    Add-Type -AssemblyName System.IO.Compression.FileSystem
    $zip = [IO.Compression.ZipFile]::OpenRead($Path)
    try {
        if ($zip.Entries.Count -lt 1 -or $zip.Entries.Count -gt 2048) { throw 'ZIP has an invalid entry count.' }
        $seen = [Collections.Generic.HashSet[string]]::new([StringComparer]::OrdinalIgnoreCase)
        $unpackedBytes = 0L
        # 先检查所有成员。只将验证通过的普通文件和目录写入私有目录。
        foreach ($entry in $zip.Entries) {
            $name = $entry.FullName.TrimEnd('/')
            if ($entry.FullName -cnotmatch '^[A-Za-z0-9_.\-/]+/?\z' -or
                ($name -cne $ExpectedRoot -and -not $name.StartsWith($ExpectedRoot + '/', [StringComparison]::Ordinal)) -or
                $name -match '(^|/)\.\.?($|/)|//' -or -not $seen.Add($name)) {
                throw 'ZIP contains an unsafe path or duplicate entry.'
            }
            foreach ($component in $name.Split('/')) {
                if ($component.EndsWith('.') -or $component -match '^(CON|PRN|AUX|NUL|COM[1-9]|LPT[1-9])(\.|$)') {
                    throw 'ZIP contains a reserved Windows filename.'
                }
            }
            if ($entry.PSObject.Properties['ExternalAttributes']) {
                $kind = ($entry.ExternalAttributes -shr 16) -band 0xF000
                if ($kind -notin @(0, 0x4000, 0x8000) -or ($entry.ExternalAttributes -band 0x0400)) {
                    throw 'ZIP contains a link or another unsupported entry.'
                }
            }
            $unpackedBytes += $entry.Length
            if ($unpackedBytes -gt $maxPackageBytes) { throw 'Uncompressed ZIP exceeds 128 MiB.' }
        }
        foreach ($entry in $zip.Entries) {
            $path = Join-Path $Destination $entry.FullName.Replace('/', '\')
            if ($entry.FullName.EndsWith('/')) {
                $null = [IO.Directory]::CreateDirectory($path)
            } else {
                $null = [IO.Directory]::CreateDirectory([IO.Path]::GetDirectoryName($path))
                $entryStream = $entry.Open()
                try { $written = Copy-BoundedStream $entryStream $path $entry.Length }
                finally { $entryStream.Dispose() }
                if ($written -ne $entry.Length) { throw 'ZIP entry size does not match the archive metadata.' }
            }
        }
    } finally { $zip.Dispose() }
}

function Write-PromptedToken {
    param([string]$Path)
    $token = Read-Host 'Report token' -AsSecureString
    if (-not $token -or $token.Length -lt 1 -or $token.Length -gt 4096) { throw 'Report token must be 1 to 4096 characters.' }
    $pointer = [Runtime.InteropServices.Marshal]::SecureStringToGlobalAllocUnicode($token)
    try {
        $value = [Runtime.InteropServices.Marshal]::PtrToStringUni($pointer)
        if ($value -cmatch '[^!-~]') { throw 'Report token must be printable ASCII without whitespace.' }
        [IO.File]::WriteAllText($Path, $value, [Text.Encoding]::ASCII)
    } finally {
        [Runtime.InteropServices.Marshal]::ZeroFreeGlobalAllocUnicode($pointer)
        $token.Dispose()
    }
}

$baseUrl = (Get-ValidatedUri $ServerUrl -Server).AbsoluteUri.TrimEnd('/')
if (-not $Endpoint) { $Endpoint = $baseUrl + '/api/report' }
$null = Get-ValidatedUri $Endpoint
if ($Tag -and ($Tag.Length -gt 40 -or $Tag -cnotmatch $tagPattern)) { throw 'Release tag must have the form vX.Y.Z.' }
if (-not $VerifyOnly) {
    $principal = New-Object Security.Principal.WindowsPrincipal([Security.Principal.WindowsIdentity]::GetCurrent())
    if (-not $principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)) {
        throw 'Run in an administrator PowerShell to install a system service.'
    }
}
$nativePlatform = Get-NativePlatform
if (-not $Platform) { $Platform = $nativePlatform }
if ($Platform -ne $nativePlatform) { throw 'Package architecture does not match this device.' }
$Platform = $Platform.ToLowerInvariant()

$working = New-PrivateDirectory
$oldProtocols = [Net.ServicePointManager]::SecurityProtocol
try {
    # Windows PowerShell 5.1 在旧系统设置中可能没有默认启用 TLS 1.2。
    if ($PSVersionTable.PSEdition -eq 'Desktop') {
        [Net.ServicePointManager]::SecurityProtocol = [Net.SecurityProtocolType]::Tls12
    }
    $catalogPath = '/client-downloads/latest.json'
    if ($Tag) { $catalogPath = "/client-downloads/releases/$Tag/INSTALL-INDEX.json" }
    $catalogFile = Join-Path $working 'catalog.json'
    $null = Receive-File $catalogPath $catalogFile 65536
    $catalog = Get-Content -LiteralPath $catalogFile -Raw -Encoding UTF8 | ConvertFrom-Json
    if ($catalog.schemaVersion -isnot [ValueType] -or $catalog.schemaVersion -ne 1 -or
        $catalog.tag -isnot [string] -or $catalog.tag.Length -gt 40 -or $catalog.tag -cnotmatch $tagPattern -or
        $catalog.version -cne $catalog.tag.Substring(1) -or
        ($Tag -and $catalog.tag -cne $Tag) -or $catalog.packages -isnot [array] -or $catalog.packages.Count -gt 64) {
        throw 'Invalid or unsupported release catalog.'
    }
    $packages = @($catalog.packages | Where-Object { $_.platform -ceq $Platform })
    if ($packages.Count -eq 0) { throw "No mirrored package for $Platform. Verify the release tag." }
    if ($packages.Count -ne 1) { throw 'The catalog has duplicate packages for this platform.' }
    $package = $packages[0]
    $archiveRoot = 'ip-reporter-' + $catalog.version + '-' + $Platform
    if ($package.file -cne ($archiveRoot + '.zip') -or $package.sha256 -isnot [string] -or
        $package.sha256 -cnotmatch '^[a-f0-9]{64}\z' -or $package.size -isnot [ValueType] -or
        [string]$package.size -cnotmatch '^[1-9][0-9]{0,8}\z' -or $package.size -gt $maxPackageBytes) {
        throw 'Invalid package filename, SHA-256, or size in catalog.'
    }
    $archivePath = Join-Path $working 'package.zip'
    $downloadPath = '/client-downloads/releases/' + $catalog.tag + '/' + $package.file
    $downloaded = Receive-File $downloadPath $archivePath ([long]$package.size)
    if ($downloaded -ne $package.size) { throw 'Package size does not match the catalog.' }
    if ((Get-Sha256 $archivePath) -cne $package.sha256) {
        throw 'Package SHA-256 does not match the catalog.'
    }
    $extracted = Join-Path $working 'extracted'
    Expand-VerifiedZip $archivePath $archiveRoot $extracted
    $packageRoot = Join-Path $extracted $archiveRoot
    $binary = Join-Path $packageRoot 'bin\ip-reporter.exe'
    $installer = Join-Path $packageRoot 'share\ip-reporter\scripts\install.ps1'
    if (-not (Test-Path -LiteralPath $binary -PathType Leaf) -or -not (Test-Path -LiteralPath $installer -PathType Leaf)) {
        throw 'Package is missing its binary or system service installer.'
    }
    Write-Host ('Verified {0} ({1}).' -f $catalog.tag, $Platform)
    if ($VerifyOnly) { return }

    $installerOptions = @{ BinaryPath = $binary }
    $existingConfig = Join-Path $env:ProgramData 'IpReporter\config.json'
    if ($Reconfigure -or -not (Test-Path -LiteralPath $existingConfig -PathType Leaf)) {
        if (-not $TokenFile) {
            $TokenFile = Join-Path $working 'report-token.txt'
            Write-PromptedToken $TokenFile
        }
        if (-not (Test-Path -LiteralPath $TokenFile -PathType Leaf)) { throw 'Token file is not readable.' }
        $installerOptions.Endpoint = $Endpoint
        $installerOptions.TokenFile = $TokenFile
        $installerOptions.PollSeconds = $PollSeconds
        $installerOptions.HeartbeatSeconds = $HeartbeatSeconds
        $installerOptions.TimeoutSeconds = $TimeoutSeconds
        if ($DeviceId) { $installerOptions.DeviceId = $DeviceId }
        if ($AllowHttp) { $installerOptions.AllowHttp = $true }
        if ($Reconfigure) { $installerOptions.Reconfigure = $true }
    } else {
        Write-Host 'Upgrading the binary and retaining the existing service configuration.'
    }
    & $installer @installerOptions
} finally {
    [Net.ServicePointManager]::SecurityProtocol = $oldProtocols
    # 仅清理本次创建的随机目录，确保解析后的路径仍在系统临时目录内。
    $tempRoot = [IO.Path]::GetFullPath([IO.Path]::GetTempPath()).TrimEnd('\', '/')
    $fullPath = [IO.Path]::GetFullPath($working)
    if ($fullPath.StartsWith($tempRoot + [IO.Path]::DirectorySeparatorChar, [StringComparison]::OrdinalIgnoreCase) -and
        [IO.Path]::GetFileName($fullPath) -cmatch '^ip-reporter-install\.[a-f0-9]{32}\z') {
        Remove-Item -LiteralPath $fullPath -Recurse -Force
    }
}
