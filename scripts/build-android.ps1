param(
    [ValidateSet('Debug', 'Release')][string]$Configuration = 'Release',
    [switch]$BuildDeviceTests,
    [switch]$DiagnosticsOnly,
    [string]$ToolsRoot = 'D:\agent\android-build-tools'
)
$ErrorActionPreference = 'Stop'
$projectRoot = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$androidSource = Join-Path $projectRoot 'android'
$androidTools = [IO.Path]::GetFullPath($ToolsRoot)
$androidStage = [IO.Path]::GetFullPath((Join-Path $androidTools 'work\clearplay-android'))
if (-not $androidStage.StartsWith($androidTools + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected Android staging directory.' }
New-Item -ItemType Directory -Path (Join-Path $androidStage 'app') -Force | Out-Null
# Stage Android builds in an ASCII path for Windows JVM test compatibility.
$stagedSources = [IO.Path]::GetFullPath((Join-Path $androidStage 'app\src'))
if (-not $stagedSources.StartsWith($androidStage + '\', [StringComparison]::OrdinalIgnoreCase)) { throw 'Unexpected staged source directory.' }
if (Test-Path -LiteralPath $stagedSources) { Remove-Item -LiteralPath $stagedSources -Recurse -Force }
Copy-Item -LiteralPath (Join-Path $androidSource 'app\src') -Destination $stagedSources -Recurse
foreach ($name in @('settings.gradle', 'build.gradle', 'gradle.properties')) {
    Copy-Item -LiteralPath (Join-Path $androidSource $name) -Destination (Join-Path $androidStage $name) -Force
}
Copy-Item -LiteralPath (Join-Path $androidSource 'app\build.gradle') -Destination (Join-Path $androidStage 'app\build.gradle') -Force
$env:JAVA_HOME = (Get-ChildItem -LiteralPath (Join-Path $androidTools 'jdk21') -Directory | Select-Object -First 1).FullName
$env:ANDROID_HOME = Join-Path $androidTools 'sdk'
$env:GRADLE_USER_HOME = Join-Path $androidTools 'gradle-cache'
if (-not (Test-Path -LiteralPath (Join-Path $env:JAVA_HOME 'bin\jlink.exe'))) { throw 'A complete JDK 21 is required.' }
$gradle = Join-Path $androidTools 'gradle-8.13\bin\gradle.bat'
$previousKey = $env:CLEARPLAY_KEYSTORE
$previousPassword = $env:CLEARPLAY_STORE_PASSWORD
try {
    if ($Configuration -eq 'Release') {
        $signingRoot = Join-Path $androidTools 'signing'
        New-Item -ItemType Directory -Path $signingRoot -Force | Out-Null
        $secretFile = Join-Path $signingRoot 'android-password.dpapi'
        if (-not (Test-Path -LiteralPath $secretFile)) {
            $random = New-Object byte[] 32
            [Security.Cryptography.RandomNumberGenerator]::Create().GetBytes($random)
            $secret = [Convert]::ToBase64String($random) | ConvertTo-SecureString -AsPlainText -Force
            $secret | ConvertFrom-SecureString | Set-Content -LiteralPath $secretFile
        }
        $secure = (Get-Content -LiteralPath $secretFile -Raw).Trim() | ConvertTo-SecureString
        $handle = [Runtime.InteropServices.Marshal]::SecureStringToBSTR($secure)
        try { $env:CLEARPLAY_STORE_PASSWORD = [Runtime.InteropServices.Marshal]::PtrToStringBSTR($handle) }
        finally { [Runtime.InteropServices.Marshal]::ZeroFreeBSTR($handle) }
        $env:CLEARPLAY_KEYSTORE = Join-Path $signingRoot 'clearplay-android-release.jks'
        if (-not (Test-Path -LiteralPath $env:CLEARPLAY_KEYSTORE)) {
            & (Join-Path $env:JAVA_HOME 'bin\keytool.exe') -genkeypair -noprompt -keystore $env:CLEARPLAY_KEYSTORE -storetype PKCS12 -storepass:env CLEARPLAY_STORE_PASSWORD -keypass:env CLEARPLAY_STORE_PASSWORD -alias clearplay -keyalg RSA -keysize 3072 -sigalg SHA256withRSA -validity 10000 -dname 'CN=Clearplay, O=Clearplay, C=CN'
            if ($LASTEXITCODE -ne 0) { throw 'APK signing key creation failed.' }
        }
    }
    $tasks = @((':app:assemble{0}' -f $Configuration), (':app:test{0}UnitTest' -f $Configuration), (':app:lint{0}' -f $Configuration))
    if ($DiagnosticsOnly) { $tasks = @() }
    if ($BuildDeviceTests -or $DiagnosticsOnly) {
        $tasks += (':app:assemble{0}AndroidTest' -f $Configuration)
        $tasks += ('-PdeviceTestBuildType=' + $Configuration.ToLowerInvariant())
    }
    Push-Location $androidStage
    try { & $gradle --no-daemon @tasks; if ($LASTEXITCODE -ne 0) { throw 'Android build or checks failed.' } }
    finally { Pop-Location }
    $variant = $Configuration.ToLowerInvariant()
    $releaseRoot = Join-Path $projectRoot 'release'
    New-Item -ItemType Directory -Path $releaseRoot -Force | Out-Null
    $version = [regex]::Match((Get-Content -LiteralPath (Join-Path $androidSource 'app\build.gradle') -Raw), "versionName '([^']+)'").Groups[1].Value
    $suffix = if ($Configuration -eq 'Debug') { '-test' } else { '' }
    $output = Join-Path $releaseRoot ('Clearplay-' + $version + $suffix + '.apk')
    $apk = Join-Path $androidStage ('app\build\outputs\apk\' + $variant + '\app-' + $variant + '.apk')
    if ($DiagnosticsOnly) {
        $output = Join-Path $releaseRoot 'Clearplay-diagnostic.apk'
        $apk = Join-Path $androidStage ('app\build\outputs\apk\androidTest\' + $variant + '\app-' + $variant + '-androidTest.apk')
    }
    Copy-Item -LiteralPath $apk -Destination $output -Force
    & (Join-Path $env:ANDROID_HOME 'build-tools\35.0.0\apksigner.bat') verify --verbose $output
    if ($LASTEXITCODE -ne 0) { throw 'APK signature verification failed.' }
    $hash = (Get-FileHash -LiteralPath $output -Algorithm SHA256).Hash.ToLowerInvariant()
    ($hash + '  ' + [IO.Path]::GetFileName($output)) | Set-Content -LiteralPath ($output + '.sha256')
    Write-Output ('APK ready: ' + $output)
} finally {
    $env:CLEARPLAY_KEYSTORE = $previousKey
    $env:CLEARPLAY_STORE_PASSWORD = $previousPassword
}
