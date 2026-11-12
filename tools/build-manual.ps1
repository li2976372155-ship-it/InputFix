# InputFix — 无 Gradle 手工构建
#
# 只在装了 JDK 17 和 Android build-tools，但没有完整 Gradle 环境时用。
# 现在的 Android SDK 里 build-tools 自带 aapt2 / d8 / apksigner，
# 拼起来就能出一个可安装的 APK。
#
# 用法：
#   1. 改下面三个路径
#   2. powershell -ExecutionPolicy Bypass -File build-manual.ps1
#
# 注意：不要用 cmd.exe 跑这套流程。cmd 在 GBK 代码页下会撕碎含中文的路径，
# 所以这里全程 PowerShell，并且直接 java -cp d8.jar 调 d8 绕开 d8.bat。

$ErrorActionPreference = 'Stop'

# ── 按你的环境改这三个 ──────────────────────────────────────────
$JAVA_HOME = "C:\jdk-17"                                # JDK 17 根目录
$SDK       = "C:\android-sdk"                           # Android SDK 根目录
$BT_VER    = "34.0.0"                                   # build-tools 版本
$PLAT_VER  = "android-34"                               # platform 版本
# ───────────────────────────────────────────────────────────────

$root = $PSScriptRoot
$BT   = Join-Path $SDK "build-tools\$BT_VER"
$PLAT = Join-Path $SDK "platforms\$PLAT_VER"
$AJAR = Join-Path $PLAT "android.jar"

$java     = Join-Path $JAVA_HOME 'bin\java.exe'
$javac    = Join-Path $JAVA_HOME 'bin\javac.exe'
$jar      = Join-Path $JAVA_HOME 'bin\jar.exe'
$keytool  = Join-Path $JAVA_HOME 'bin\keytool.exe'
$aapt2    = Join-Path $BT 'aapt2.exe'
$zipalign = Join-Path $BT 'zipalign.exe'
$d8jar    = Join-Path $BT 'lib\d8.jar'
$asjar    = Join-Path $BT 'lib\apksigner.jar'

foreach ($p in @($java,$javac,$jar,$keytool,$aapt2,$zipalign,$d8jar,$asjar,$AJAR)) {
    if (-not (Test-Path $p)) { throw "missing: $p" }
}

$manifest = Join-Path $root 'app\src\main\AndroidManifest.xml'
$resDir   = Join-Path $root 'app\src\main\res'
$srcDir   = Join-Path $root 'app\src\main\java'

$gen  = Join-Path $root 'build\gen'
$cls  = Join-Path $root 'build\classes'
$dex  = Join-Path $root 'build\dexout'
$bd   = Join-Path $root 'build'

function Run($name, $exe, $argv) {
    Write-Host "=== $name ===" -ForegroundColor Cyan
    & $exe @argv
    if ($LASTEXITCODE -ne 0) { throw "$name failed ($LASTEXITCODE)" }
}

# 先整个删掉 build，再建子目录。
# 注意顺序：gen/classes/dexout 都在 build 下面，不能放进同一个"删除+创建"循环，
# 否则删除父目录时会把刚建好的子目录一起带走（d8 会报 Invalid output）。
if (Test-Path $bd) { Remove-Item $bd -Recurse -Force }
foreach ($d in @($bd, $gen, $cls, $dex)) {
    New-Item -ItemType Directory -Force $d | Out-Null
}

# 1. 编译资源
Run 'aapt2 compile' $aapt2 @('compile','--dir',$resDir,'-o',(Join-Path $bd 'res.zip'))

# 2. 链接 -> base.apk + R.java
Run 'aapt2 link' $aapt2 @(
    'link','-o',(Join-Path $bd 'base.apk'),
    '-I',$AJAR,'--manifest',$manifest,(Join-Path $bd 'res.zip'),
    '--java',$gen,
    '--min-sdk-version','26','--target-sdk-version','28',
    '--auto-add-overlay'
)

# 3. javac
$sources = @()
$sources += (Get-ChildItem $srcDir -Recurse -Filter *.java | ForEach-Object { $_.FullName })
$sources += (Get-ChildItem $gen -Recurse -Filter R.java | ForEach-Object { $_.FullName })
Write-Host "=== javac ($($sources.Count) files) ===" -ForegroundColor Cyan
& $javac -encoding UTF-8 -source 8 -target 8 `
    -bootclasspath $AJAR -classpath $AJAR -d $cls @sources
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }

# 4. jar -> d8 -> classes.dex
Run 'jar' $jar @('cf',(Join-Path $bd 'classes.jar'),'-C',$cls,'.')
Write-Host '=== d8 ===' -ForegroundColor Cyan
& $java -cp $d8jar com.android.tools.r8.D8 `
    --lib $AJAR --min-api 26 --output $dex (Join-Path $bd 'classes.jar')
if ($LASTEXITCODE -ne 0) { throw 'd8 failed' }

$dexFile = Join-Path $dex 'classes.dex'
if (-not (Test-Path $dexFile)) { throw 'classes.dex not produced' }

# 5. dex 塞回 apk
Write-Host '=== add dex ===' -ForegroundColor Cyan
Add-Type -AssemblyName System.IO.Compression.FileSystem
$apk = Join-Path $bd 'base.apk'
$zip = [System.IO.Compression.ZipFile]::Open($apk,'Update')
$old = $zip.GetEntry('classes.dex'); if ($old) { $old.Delete() }
[System.IO.Compression.ZipFileExtensions]::CreateEntryFromFile(
    $zip,$dexFile,'classes.dex',[System.IO.Compression.CompressionLevel]::Optimal) | Out-Null
$zip.Dispose()

# 6. 对齐
Run 'zipalign' $zipalign @('-f','-p','4',$apk,(Join-Path $bd 'aligned.apk'))

# 7. 签名
$ks = Join-Path $root 'debug.keystore'
if (-not (Test-Path $ks)) {
    Write-Host '=== keytool ===' -ForegroundColor Cyan
    $prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
    & $keytool -genkeypair -v -keystore $ks -alias androiddebugkey `
        -keyalg RSA -keysize 2048 -validity 10000 `
        -storepass android -keypass android `
        -dname "CN=Android Debug,O=Android,C=US" 2>$null
    $ErrorActionPreference = $prev
}

Write-Host '=== apksigner ===' -ForegroundColor Cyan
$out = Join-Path $bd 'InputFix.apk'
$prev = $ErrorActionPreference; $ErrorActionPreference = 'Continue'
& $java -jar $asjar sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --ks-key-alias androiddebugkey --out $out (Join-Path $bd 'aligned.apk')
$ErrorActionPreference = $prev

Write-Host ''
Write-Host ("[OK] " + $out + "  " + [math]::Round((Get-Item $out).Length/1KB,1) + " KB") -ForegroundColor Green
