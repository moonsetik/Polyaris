param([string]$Destination, [string]$ToolsDirectory, [string]$SigningDirectory)
$ErrorActionPreference = 'Stop'
$project = $PSScriptRoot
$workspace = Split-Path $project
$tools = if ($ToolsDirectory) { (Resolve-Path -LiteralPath $ToolsDirectory).Path } else { Join-Path $workspace 'android-tools' }
$jdk = (Get-ChildItem -LiteralPath (Join-Path $tools 'jdk') -Directory | Select-Object -First 1).FullName
$env:JAVA_HOME = $jdk
$env:Path = (Join-Path $jdk 'bin') + ';' + $env:Path
$buildTools = Join-Path $tools 'buildtools/android-15'
$jar = Join-Path $tools 'platform/android-35/android.jar'
$build = Join-Path $project 'build'
$generated = Join-Path $build 'generated'
$classes = Join-Path $build 'classes'
$dex = Join-Path $build 'dex'
if (-not $Destination) { $Destination = Join-Path $build 'Polaris.apk' }

function Invoke-AndroidTool([string]$Executable, [string[]]$Arguments) {
    & $Executable @Arguments
    if ($LASTEXITCODE -ne 0) { throw "Android build tool failed ($LASTEXITCODE): $(Split-Path -Leaf $Executable)" }
}

New-Item -ItemType Directory -Force -Path $generated,$classes,$dex,(Split-Path $Destination) | Out-Null
Invoke-AndroidTool (Join-Path $buildTools 'aapt2.exe') @('compile','--dir',(Join-Path $project 'res'),'-o',(Join-Path $build 'resources.zip'))
Invoke-AndroidTool (Join-Path $buildTools 'aapt2.exe') @('link','-I',$jar,'--manifest',(Join-Path $project 'AndroidManifest.xml'),'--java',$generated,'-o',(Join-Path $build 'unsigned.apk'),(Join-Path $build 'resources.zip'))
$source = @(Get-ChildItem -LiteralPath (Join-Path $project 'src'),$generated -Recurse -Filter '*.java' -File | ForEach-Object FullName)
Invoke-AndroidTool 'javac.exe' (@('-encoding','UTF-8','--release','8','-classpath',$jar,'-d',$classes) + $source)
Invoke-AndroidTool 'jar.exe' @('--create','--file',(Join-Path $build 'classes.jar'),'-C',$classes,'.')
Invoke-AndroidTool (Join-Path $buildTools 'd8.bat') @('--min-api','26','--lib',$jar,'--output',$dex,(Join-Path $build 'classes.jar'))
Invoke-AndroidTool 'jar.exe' @('--update','--file',(Join-Path $build 'unsigned.apk'),'-C',$dex,'classes.dex')
Invoke-AndroidTool (Join-Path $buildTools 'zipalign.exe') @('-f','4',(Join-Path $build 'unsigned.apk'),(Join-Path $build 'aligned.apk'))

$signing = if ($SigningDirectory) { (Resolve-Path -LiteralPath $SigningDirectory).Path } else { Join-Path $project 'signing' }
if (!$SigningDirectory) { New-Item -ItemType Directory -Force -Path $signing | Out-Null }
$key = Join-Path $signing 'polaris.p12'
$password = Join-Path $signing 'password.txt'
if ($SigningDirectory -and (!(Test-Path -LiteralPath $key) -or !(Test-Path -LiteralPath $password))) {
    throw 'The supplied signing directory must contain the existing update key.'
}
if (!(Test-Path -LiteralPath $key)) {
    $randomBytes = [Security.Cryptography.RandomNumberGenerator]::GetBytes(36)
    [IO.File]::WriteAllText($password, [Convert]::ToBase64String($randomBytes))
}
$env:POLARIS_APK_STOREPASS = [IO.File]::ReadAllText($password)
try {
    if (!(Test-Path -LiteralPath $key)) {
        Invoke-AndroidTool 'keytool.exe' @('-genkeypair','-noprompt','-keystore',$key,'-storetype','PKCS12',
            '-storepass:env','POLARIS_APK_STOREPASS','-alias','polaris','-keyalg','RSA','-keysize','3072',
            '-validity','10000','-dname','CN=Polaris Planner')
    }
    Invoke-AndroidTool (Join-Path $buildTools 'apksigner.bat') @('sign','--ks',$key,'--ks-key-alias','polaris',
        '--ks-pass','env:POLARIS_APK_STOREPASS','--v4-signing-enabled','false',
        '--out',$Destination,(Join-Path $build 'aligned.apk'))
} finally {
    Remove-Item Env:POLARIS_APK_STOREPASS -ErrorAction SilentlyContinue
}
Invoke-AndroidTool (Join-Path $buildTools 'apksigner.bat') @('verify','--verbose',$Destination)
Invoke-AndroidTool (Join-Path $buildTools 'zipalign.exe') @('-c','4',$Destination)
