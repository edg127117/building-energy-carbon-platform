[CmdletBinding()]
param()

Set-StrictMode -Version Latest
$ErrorActionPreference = 'Stop'

$failures = [System.Collections.Generic.List[string]]::new()
$repoRoot = Split-Path -Parent $PSScriptRoot

function Invoke-VersionCommand {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,
        [Parameter(Mandatory = $true)]
        [string]$Executable,
        [Parameter(Mandatory = $true)]
        [string[]]$Arguments
    )

    try {
        # Windows PowerShell 5 会把 java -version 写到 stderr 的正常内容包装成错误记录。
        # 使用 Process 分别读取 stdout 和 stderr，可以保留原始版本文本并可靠取得退出码。
        $startInfo = New-Object System.Diagnostics.ProcessStartInfo
        if ([System.IO.Path]::GetExtension($Executable) -eq '.cmd') {
            $startInfo.FileName = $env:ComSpec
            $startInfo.Arguments = '/d /s /c ""' + $Executable + '" ' + ($Arguments -join ' ') + '"'
        } else {
            $startInfo.FileName = $Executable
            $startInfo.Arguments = $Arguments -join ' '
        }
        $startInfo.UseShellExecute = $false
        $startInfo.CreateNoWindow = $true
        $startInfo.RedirectStandardOutput = $true
        $startInfo.RedirectStandardError = $true

        $process = New-Object System.Diagnostics.Process
        $process.StartInfo = $startInfo
        [void]$process.Start()
        $standardOutput = $process.StandardOutput.ReadToEnd()
        $standardError = $process.StandardError.ReadToEnd()
        $process.WaitForExit()

        $output = (($standardOutput, $standardError) |
            Where-Object { $_ } |
            ForEach-Object { $_.Trim() }) -join [Environment]::NewLine

        if ($process.ExitCode -ne 0) {
            throw "$Label 返回退出码 $($process.ExitCode)"
        }

        Write-Host "[$Label]"
        Write-Host $output
        return $output
    } catch {
        $script:failures.Add("$Label 检查失败：$($_.Exception.Message)")
        return ''
    }
}

function Assert-MajorVersion {
    param(
        [Parameter(Mandatory = $true)]
        [string]$Label,
        [Parameter(Mandatory = $true)]
        [string]$Output,
        [Parameter(Mandatory = $true)]
        [string]$Pattern
    )

    if ($Output -and $Output -notmatch $Pattern) {
        $script:failures.Add("$Label 必须使用 Java 21。")
    }
}

if (-not $env:JAVA_HOME) {
    $failures.Add('JAVA_HOME 未配置。')
} else {
    $javaHomeExecutable = Join-Path $env:JAVA_HOME 'bin\java.exe'
    if (-not (Test-Path -LiteralPath $javaHomeExecutable)) {
        $failures.Add("JAVA_HOME 中不存在 bin\java.exe：$env:JAVA_HOME")
    } else {
        $javaHomeOutput = Invoke-VersionCommand `
            -Label 'JAVA_HOME java' `
            -Executable $javaHomeExecutable `
            -Arguments @('-version')
        Assert-MajorVersion `
            -Label 'JAVA_HOME java' `
            -Output $javaHomeOutput `
            -Pattern 'version "21(?:[.\-"]|$)'
    }
}

$pathJavaCommand = @(Get-Command java -CommandType Application -ErrorAction SilentlyContinue) |
    Select-Object -First 1
if (-not $pathJavaCommand) {
    $failures.Add('PATH 中找不到 java。')
} else {
    $pathJava = $pathJavaCommand.Source
    Write-Host "[PATH java] $pathJava"
    $pathJavaOutput = Invoke-VersionCommand `
        -Label 'PATH java version' `
        -Executable $pathJava `
        -Arguments @('-version')
    Assert-MajorVersion `
        -Label 'PATH java' `
        -Output $pathJavaOutput `
        -Pattern 'version "21(?:[.\-"]|$)'
}

$pathJavacCommand = @(Get-Command javac -CommandType Application -ErrorAction SilentlyContinue) |
    Select-Object -First 1
if (-not $pathJavacCommand) {
    $failures.Add('PATH 中找不到 javac。')
} else {
    $pathJavac = $pathJavacCommand.Source
    Write-Host "[PATH javac] $pathJavac"
    $pathJavacOutput = Invoke-VersionCommand `
        -Label 'PATH javac version' `
        -Executable $pathJavac `
        -Arguments @('-version')
    Assert-MajorVersion `
        -Label 'PATH javac' `
        -Output $pathJavacOutput `
        -Pattern '^javac 21(?:[.\s\-]|$)'
}

$wrapperPath = Join-Path $repoRoot 'mvnw.cmd'
if (-not (Test-Path -LiteralPath $wrapperPath)) {
    $failures.Add("找不到 Maven Wrapper：$wrapperPath")
} else {
    $wrapperOutput = Invoke-VersionCommand `
        -Label 'Maven Wrapper' `
        -Executable $wrapperPath `
        -Arguments @('-version')
    if ($wrapperOutput -and $wrapperOutput -notmatch '(?m)^Apache Maven 3\.9\.9(?:\s|$)') {
        $failures.Add('Maven Wrapper 必须使用 Maven 3.9.9。')
    }
    if ($wrapperOutput -and $wrapperOutput -notmatch '(?m)^Java version: 21(?:[.\s,]|$)') {
        $failures.Add('Maven Wrapper 必须使用 Java 21。')
    }
}

if ($failures.Count -gt 0) {
    [Console]::Error.WriteLine("Java 21 环境检查未通过：`n- " + ($failures -join "`n- "))
    exit 1
}

Write-Host 'Java 21、javac 和 Maven Wrapper 环境一致。'
exit 0
