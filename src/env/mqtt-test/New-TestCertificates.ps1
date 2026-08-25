[CmdletBinding()]
param(
    [string]$OutputDirectory,
    [string]$StorePassword = 'changeit-test-only'
)

$ErrorActionPreference = 'Stop'

if ([string]::IsNullOrWhiteSpace($OutputDirectory)) {
    $OutputDirectory = Join-Path $PSScriptRoot 'certs'
}

$resolvedRoot = [System.IO.Path]::GetFullPath($PSScriptRoot)
$resolvedOutput = [System.IO.Path]::GetFullPath($OutputDirectory)
if (-not $resolvedOutput.StartsWith($resolvedRoot, [System.StringComparison]::OrdinalIgnoreCase)) {
    throw 'Test certificate output must stay under the mqtt-test directory.'
}

$openssl = Get-Command openssl -ErrorAction SilentlyContinue
$docker = Get-Command docker -ErrorAction SilentlyContinue
if (-not $docker) {
    $dockerDesktopCli = Join-Path $env:LOCALAPPDATA `
        'Programs\DockerDesktop\resources\bin\docker.exe'
    if (Test-Path -LiteralPath $dockerDesktopCli) {
        $docker = Get-Item -LiteralPath $dockerDesktopCli
    }
}
if (-not $openssl -and -not $docker) {
    throw 'Local OpenSSL or an available Docker Desktop CLI is required.'
}
$keytool = Get-Command keytool -ErrorAction Stop
$dockerExecutable = if ($docker -is [System.Management.Automation.ApplicationInfo]) {
    $docker.Source
}
elseif ($docker) {
    $docker.FullName
}
else {
    $null
}
$opensslExecutable = if ($openssl) { $openssl.Source } else { $null }
New-Item -ItemType Directory -Force -Path $resolvedOutput | Out-Null

$caKey = 'ca.key'
$caCert = 'ca.crt'
$serverKey = 'server.key'
$serverTraditionalKey = 'server-rsa.key'
$serverCsr = 'server.csr'
$serverCert = 'server.crt'
$clientKey = 'client.key'
$clientCsr = 'client.csr'
$clientCert = 'client.crt'
$trustStore = Join-Path $resolvedOutput 'truststore.p12'
$keyStore = Join-Path $resolvedOutput 'client-keystore.p12'
$serialFile = 'ca.srl'

function Invoke-TestOpenSsl {
    param([string[]]$OpenSslArguments)
    if ($openssl) {
        & $opensslExecutable @OpenSslArguments
    }
    else {
        $mount = "${resolvedOutput}:/certs"
        & $dockerExecutable run --rm --volume $mount --workdir /certs `
            alpine/openssl:latest @OpenSslArguments
    }
    if ($LASTEXITCODE -ne 0) {
        throw "OpenSSL failed: $($OpenSslArguments -join ' ')"
    }
}

Push-Location $resolvedOutput
try {
    Invoke-TestOpenSsl @('req', '-x509', '-newkey', 'rsa:2048', '-nodes', '-days',
        '30', '-subj', '/CN=Building Energy Test CA', '-keyout', $caKey,
        '-out', $caCert)
    Invoke-TestOpenSsl @('req', '-newkey', 'rsa:2048', '-nodes', '-subj',
        '/CN=localhost', '-keyout', $serverKey, '-addext',
        'subjectAltName=DNS:localhost,IP:127.0.0.1', '-out', $serverCsr)
    Invoke-TestOpenSsl @('rsa', '-in', $serverKey, '-traditional', '-out',
        $serverTraditionalKey)
    Invoke-TestOpenSsl @('x509', '-req', '-days', '30', '-in', $serverCsr,
        '-CA', $caCert, '-CAkey', $caKey, '-CAcreateserial', '-copy_extensions',
        'copy', '-out', $serverCert)

    Invoke-TestOpenSsl @('req', '-newkey', 'rsa:2048', '-nodes', '-subj',
        '/CN=test-mqtt-client', '-keyout', $clientKey, '-out', $clientCsr)
    Invoke-TestOpenSsl @('x509', '-req', '-days', '30', '-in', $clientCsr,
        '-CA', $caCert, '-CAkey', $caKey, '-CAserial', $serialFile,
        '-out', $clientCert)
}
finally {
    Pop-Location
}
Move-Item -LiteralPath (Join-Path $resolvedOutput $serverTraditionalKey) `
    -Destination (Join-Path $resolvedOutput $serverKey) -Force
if (-not $openssl) {
    $mount = "${resolvedOutput}:/certs"
    & $dockerExecutable run --rm --volume $mount --entrypoint chmod `
        alpine/openssl:latest 0644 /certs/server.key
    if ($LASTEXITCODE -ne 0) {
        throw 'Unable to make the Docker-generated test server key readable by EMQX.'
    }
}

if (Test-Path -LiteralPath $trustStore) {
    Remove-Item -LiteralPath $trustStore -Force
}
& $keytool.Source -importcert -noprompt -storetype PKCS12 -alias test-ca `
    -file (Join-Path $resolvedOutput $caCert) -keystore $trustStore `
    -storepass $StorePassword
Push-Location $resolvedOutput
try {
    Invoke-TestOpenSsl @('pkcs12', '-export', '-name', 'test-client', '-inkey',
        $clientKey, '-in', $clientCert, '-certfile', $caCert, '-out',
        (Split-Path -Leaf $keyStore), '-passout', "pass:$StorePassword")
}
finally {
    Pop-Location
}

Remove-Item -LiteralPath (Join-Path $resolvedOutput $serverCsr), `
    (Join-Path $resolvedOutput $clientCsr) -Force -ErrorAction SilentlyContinue
Write-Output "MQTT_TEST_CERTS_OK $resolvedOutput"
