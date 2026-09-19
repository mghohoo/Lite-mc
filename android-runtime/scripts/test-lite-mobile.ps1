<#
.SYNOPSIS
  Repeatable Lite-MC Android source/build/package checks. No account values are read.
.DESCRIPTION
  Source checks are necessary preconditions, not proof of behavior or security.
  Missing product modules FAIL. Optional checks not requested are SKIP, never PASS.
  This script does not install an APK, launch the app, change device settings, or
  start/stop an emulator. See docs/LITE_MC_TEST_PLAN.md for functional validation.
#>
[CmdletBinding()]
param(
    [string]$ProjectRoot,
    [switch]$Build,
    [switch]$RunUnitTests,
    [string]$JavaHome,
    [string]$SdkRoot,
    [string]$ApkPath,
    [string]$DeviceSerial,
    [string]$PackageName = 'com.litemc.launcher.android.debug'
)

Set-StrictMode -Version 2.0
$ErrorActionPreference = 'Stop'
if (-not $ProjectRoot) {
    $ProjectRoot = Split-Path -Parent (Split-Path -Parent $MyInvocation.MyCommand.Path)
}
$checks = New-Object 'System.Collections.Generic.List[object]'

function Record-Check {
    param([string]$Name, [string]$State, [string]$Detail)
    $checks.Add([pscustomobject]@{ Name = $Name; State = $State; Detail = $Detail })
    Write-Host ('[{0}] {1}: {2}' -f $State, $Name, $Detail)
}

function Require-Condition {
    param([string]$Name, [bool]$Condition, [string]$Pass, [string]$Fail)
    if ($Condition) { Record-Check $Name 'PASS' $Pass }
    else { Record-Check $Name 'FAIL' $Fail }
}

function Read-Source {
    param([string]$RelativePath)
    $path = Join-Path $ProjectRoot $RelativePath
    if (-not (Test-Path -LiteralPath $path -PathType Leaf)) { return '' }
    return [string](Get-Content -LiteralPath $path -Raw -Encoding UTF8)
}

function Find-SdkTool {
    param([string]$RelativePath)
    if ($SdkRoot) {
        $candidate = Join-Path $SdkRoot $RelativePath
        if (Test-Path -LiteralPath $candidate -PathType Leaf) { return $candidate }
    }
    return $null
}

try {
    $ProjectRoot = (Resolve-Path -LiteralPath $ProjectRoot).Path
    if (-not (Test-Path -LiteralPath (Join-Path $ProjectRoot 'app_pojavlauncher/build.gradle'))) {
        throw 'Not the Android runtime project: app_pojavlauncher/build.gradle is missing.'
    }
    if (-not $SdkRoot) {
        foreach ($candidate in @($env:ANDROID_SDK_ROOT, $env:ANDROID_HOME,
                (Join-Path $env:LOCALAPPDATA 'Android/Sdk'))) {
            if ($candidate -and (Test-Path -LiteralPath $candidate -PathType Container)) {
                $SdkRoot = $candidate
                break
            }
        }
    }

    $javaBase = 'app_pojavlauncher/src/main/java/com/litemc/launcher/'
    $assetBase = 'app_pojavlauncher/src/main/assets/litemc/'
    $required = @('LiteActivity.java', 'LiteAccounts.java', 'LiteMods.java', 'LiteNetwork.java')
    foreach ($name in $required) {
        $source = Read-Source ($javaBase + $name)
        Require-Condition ('source/' + $name) ([bool]$source.Trim()) 'Present.' 'Missing or empty; feature not implemented.'
    }
    $activity = Read-Source ($javaBase + 'LiteActivity.java')
    $accounts = Read-Source ($javaBase + 'LiteAccounts.java')
    $mods = Read-Source ($javaBase + 'LiteMods.java')
    $index = Read-Source ($assetBase + 'index.html')
    $manifestText = Read-Source 'app_pojavlauncher/src/main/AndroidManifest.xml'
    $bootstrap = Read-Source 'app_pojavlauncher/src/main/java/net/kdt/pojavlaunch/TestStorageActivity.java'
    Require-Condition 'ui/local-index' ([bool]$index.Trim()) 'Own local mobile UI exists.' 'Local litemc/index.html missing.'

    [xml]$manifest = $manifestText
    $ns = New-Object System.Xml.XmlNamespaceManager($manifest.NameTable)
    $ns.AddNamespace('android', 'http://schemas.android.com/apk/res/android')
    $androidNs = 'http://schemas.android.com/apk/res/android'
    $liteNode = $manifest.SelectSingleNode("/manifest/application/activity[@android:name='com.litemc.launcher.LiteActivity']", $ns)
    Require-Condition 'entry/activity-registered' ($null -ne $liteNode) 'Own Activity is registered.' 'LiteActivity registration missing.'
    $launchers = @($manifest.SelectNodes("/manifest/application/activity[intent-filter/action[@android:name='android.intent.action.MAIN'] and intent-filter/category[@android:name='android.intent.category.LAUNCHER']]", $ns))
    $ownEntry = $false
    if ($launchers.Count -eq 1) {
        $entryName = $launchers[0].GetAttribute('name', $androidNs)
        $ownEntry = ($entryName -eq 'com.litemc.launcher.LiteActivity') -or
            (($entryName -eq '.TestStorageActivity' -or $entryName -eq 'net.kdt.pojavlaunch.TestStorageActivity') -and
            ($bootstrap -match 'com\.litemc\.launcher\.LiteActivity|new\s+Intent\([^;]*\bLiteActivity\.class'))
    }
    Require-Condition 'entry/launcher-route' $ownEntry 'Single launcher routes to Lite-MC.' 'Launcher missing, ambiguous, or still routes to upstream home.'
    $appNode = $manifest.SelectSingleNode('/manifest/application')
    Require-Condition 'security/no-backup' ($appNode.GetAttribute('allowBackup', $androidNs) -eq 'false') 'App backup disabled.' 'Account-bearing app must explicitly disable backup or revise this check after audited exclusions.'

    $allOwnJava = ''
    $ownJavaDir = Join-Path $ProjectRoot $javaBase
    if (Test-Path -LiteralPath $ownJavaDir -PathType Container) {
        $allOwnJava = (@(Get-ChildItem -LiteralPath $ownJavaDir -Filter '*.java' -File -Recurse |
            ForEach-Object { Get-Content -LiteralPath $_.FullName -Raw -Encoding UTF8 }) -join "`n")
    }
    $localOrigin = ($activity -match 'file:///android_asset/litemc/') -or
        (($activity -match 'https://appassets\.androidplatform\.net["/]') -and
         ($activity -match 'shouldInterceptRequest') -and
         ($activity -match 'getAssets\(\)\.open\(') -and
         ($activity -match '"/litemc/index\.html"'))
    Require-Condition 'security/local-webview-origin' $localOrigin 'Trusted local asset origin and interception found; dynamically test navigation rejection.' 'Local-only WebView asset loading not found.'
    Require-Condition 'security/bridge-defined' ($allOwnJava -match '@JavascriptInterface') 'Native bridge annotation found; payload validation still requires review.' 'Expected local native bridge is absent.'
    Require-Condition 'security/webview-navigation-guard' ($allOwnJava -match 'shouldOverrideUrlLoading') 'Navigation interception exists; malicious URL tests remain mandatory.' 'No WebView navigation interception found.'
    Require-Condition 'security/webview-no-universal-file-access' ($allOwnJava -match 'setAllowUniversalAccessFromFileURLs\s*\(\s*false\s*\)') 'Universal file-origin access disabled.' 'Universal file-origin access must be explicitly disabled.'
    Require-Condition 'security/webview-no-file-origin-access' ($allOwnJava -match 'setAllowFileAccessFromFileURLs\s*\(\s*false\s*\)') 'File-origin access disabled.' 'File-origin access must be explicitly disabled.'
    Require-Condition 'security/ui-csp' ($index -match '(?i)http-equiv\s*=\s*["'']Content-Security-Policy["'']') 'CSP is declared; effective policy still needs review.' 'Local UI has no CSP meta declaration.'
    Require-Condition 'security/no-remote-ui-scripts' (([bool]$index.Trim()) -and $index -notmatch '(?i)<script\b[^>]*\bsrc\s*=\s*["''](?:https?:)?//') 'No remote script element in local index.' 'Missing index or remotely hosted executable UI script found.'
    Require-Condition 'security/keystore-encryption' (($allOwnJava -match 'AndroidKeyStore') -and ($allOwnJava -match 'Cipher\.getInstance\s*\(\s*"AES/GCM/NoPadding"')) 'Android Keystore + authenticated encryption references found.' 'Keystore-backed authenticated token encryption not found.'
    $possibleSecretLog = $allOwnJava -match '(?im)(?:Log\.[vdiew]|System\.out\.print(?:ln)?)\s*\([^\r\n;]*(?:accessToken|refreshToken|msaRefreshToken|Authorization)'
    Require-Condition 'security/no-obvious-token-logging' (-not $possibleSecretLog -and [bool]$accounts.Trim()) 'No simple single-line credential logging pattern found; not a full taint audit.' 'Missing accounts module or possible credential logging. Inspect source; no secret values printed.'
    Require-Condition 'mods/modrinth-provider' ($mods -match 'api\.modrinth\.com') 'Modrinth endpoint reference exists; behavior untested.' 'Own Modrinth provider not found.'
    Require-Condition 'mods/curseforge-provider' ($mods -match 'api\.curseforge\.com') 'CurseForge endpoint reference exists; valid API key still required.' 'Own CurseForge provider not found.'
    Require-Condition 'licensing/upstream-license' (Test-Path -LiteralPath (Join-Path $ProjectRoot 'LICENSE') -PathType Leaf) 'Runtime license file retained.' 'Upstream license file missing.'

    $jsFiles = @()
    $assetDir = Join-Path $ProjectRoot $assetBase
    if (Test-Path -LiteralPath $assetDir -PathType Container) {
        $jsFiles = @(Get-ChildItem -LiteralPath $assetDir -Filter '*.js' -Recurse -File)
    }
    $node = Get-Command node -ErrorAction SilentlyContinue
    if ($jsFiles.Count -eq 0) { Record-Check 'ui/javascript-syntax' 'FAIL' 'No local JavaScript file to validate.' }
    elseif (-not $node) { Record-Check 'ui/javascript-syntax' 'SKIP' 'Node.js unavailable.' }
    else {
        foreach ($file in $jsFiles) {
            & $node.Source --check $file.FullName *> $null
            Require-Condition ('ui/js-syntax/' + $file.Name) ($LASTEXITCODE -eq 0) 'Node syntax check passed; WebView compatibility not proven.' 'JavaScript syntax check failed.'
        }
    }

    if ($Build -or $RunUnitTests) {
        $previousJava = $env:JAVA_HOME
        $previousHost = $env:HOST_OS
        try {
            if ($JavaHome) {
                if (-not (Test-Path -LiteralPath (Join-Path $JavaHome 'bin/java.exe'))) { throw 'JavaHome must contain bin/java.exe.' }
                $env:JAVA_HOME = $JavaHome
            }
            $env:HOST_OS = 'windows'
            $tasks = @()
            if ($Build) { $tasks += ':app_pojavlauncher:assembleDebug' }
            if ($RunUnitTests) { $tasks += ':MioLibPatcher:test' }
            Push-Location $ProjectRoot
            try {
                & '.\gradlew.bat' @tasks '--console=plain' '--no-daemon'
                Require-Condition 'gradle/requested-tasks' ($LASTEXITCODE -eq 0) 'Requested Gradle tasks succeeded; game execution is not verified.' 'Gradle failed; inspect its build diagnostics.'
            } finally { Pop-Location }
        } finally {
            $env:JAVA_HOME = $previousJava
            $env:HOST_OS = $previousHost
        }
        if ($Build -and -not $ApkPath) { $ApkPath = Join-Path $ProjectRoot 'app_pojavlauncher/build/outputs/apk/debug/app_pojavlauncher-debug.apk' }
    } else { Record-Check 'gradle/build-and-unit-tests' 'SKIP' 'Not requested. Use -Build and/or -RunUnitTests.' }

    if ($ApkPath) {
        if (-not (Test-Path -LiteralPath $ApkPath -PathType Leaf)) { Record-Check 'apk/present' 'FAIL' 'Requested APK is missing.' }
        else {
            $apk = Get-Item -LiteralPath $ApkPath
            Record-Check 'apk/present' 'PASS' ('APK size: {0} bytes.' -f $apk.Length)
            Write-Host ('APK SHA-256: ' + (Get-FileHash -LiteralPath $ApkPath -Algorithm SHA256).Hash)
            $productFiles = @()
            foreach ($path in @($ownJavaDir, $assetDir)) {
                if (Test-Path -LiteralPath $path -PathType Container) { $productFiles += @(Get-ChildItem -LiteralPath $path -Recurse -File) }
            }
            $newer = @($productFiles | Where-Object { $_.LastWriteTimeUtc -gt $apk.LastWriteTimeUtc })
            Require-Condition 'apk/product-source-freshness' ($productFiles.Count -gt 0 -and $newer.Count -eq 0) 'No newer product source files; timestamp check is not reproducibility proof.' 'Missing product source or APK predates current product files.'
            $buildTools = @()
            if ($SdkRoot -and (Test-Path -LiteralPath (Join-Path $SdkRoot 'build-tools'))) {
                $buildTools = @(Get-ChildItem -LiteralPath (Join-Path $SdkRoot 'build-tools') -Directory | Sort-Object Name -Descending)
            }
            $apksigner = $null
            foreach ($dir in $buildTools) {
                $candidate = Join-Path $dir.FullName 'apksigner.bat'
                if (Test-Path -LiteralPath $candidate) { $apksigner = $candidate; break }
            }
            if ($apksigner) {
                & $apksigner verify $ApkPath *> $null
                Require-Condition 'apk/signature' ($LASTEXITCODE -eq 0) 'APK signature is valid; signing-key ownership is a separate release gate.' 'APK signature verification failed.'
            } else { Record-Check 'apk/signature' 'FAIL' 'Android build-tools/apksigner not found.' }
        }
    } else { Record-Check 'apk/verification' 'SKIP' 'No -ApkPath or -Build supplied.' }

    if ($DeviceSerial) {
        $adb = Find-SdkTool 'platform-tools/adb.exe'
        if (-not $adb) { Record-Check 'device/adb' 'FAIL' 'SDK platform-tools/adb.exe unavailable.' }
        else {
            $state = (& $adb -s $DeviceSerial get-state 2>$null | Out-String).Trim()
            Require-Condition 'device/connected' ($state -eq 'device') 'Requested device is online.' 'Requested device unavailable, offline, or unauthorized.'
            if ($state -eq 'device') {
                $packagePath = (& $adb -s $DeviceSerial shell pm path $PackageName 2>$null | Out-String).Trim()
                Require-Condition 'device/package-installed' ($packagePath -match '^package:') 'Package is installed; no install or launch was performed.' 'Package not installed on requested device.'
                $activityState = & $adb -s $DeviceSerial shell dumpsys activity activities 2>$null | Out-String
                $foregroundOwn = $activityState -match ('(?m)(?:mResumedActivity|topResumedActivity)[^\r\n]*' + [regex]::Escape($PackageName) + '/com\.litemc\.launcher\.LiteActivity')
                Require-Condition 'device/own-home-foreground' $foregroundOwn 'Own home is the resumed Activity at this instant; visual interaction still unverified.' 'Own home is not resumed. Open it manually before this optional check.'
            }
        }
    } else { Record-Check 'device/read-only-smoke' 'SKIP' 'No -DeviceSerial supplied; emulator/phone checks not run.' }
} catch {
    # Do not dump arbitrary exception content: a future exception might contain a credential.
    Record-Check 'runner/environment' 'FAIL' ('Could not complete check at script line {0}. Inspect configuration/source; exception content withheld.' -f $_.InvocationInfo.ScriptLineNumber)
}

$failed = @($checks | Where-Object State -eq 'FAIL').Count
$passed = @($checks | Where-Object State -eq 'PASS').Count
$skipped = @($checks | Where-Object State -eq 'SKIP').Count
Write-Host ('SUMMARY: {0} PASS, {1} FAIL, {2} SKIP. Static/build success never means Minecraft launched successfully.' -f $passed, $failed, $skipped)
if ($failed -gt 0) { exit 1 }
exit 0
