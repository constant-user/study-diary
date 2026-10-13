# ============================================================
#  Нативная сборка APK: aapt2 + javac + d8 + apksigner
#  (без Gradle и без копирования веб-ассетов)
#
#  Требования: JDK 17+, Android SDK (platforms;android-34, build-tools;34.0.0)
#  Запуск: powershell -ExecutionPolicy Bypass -File build-native.ps1
#  Результат: bin/study-diary.apk
#
#  ВАЖНО: debug.keystore хранится ВНЕ bin\, иначе «Очистка bin\»
#  удаляла бы ключ, и каждая сборка получала бы другую подпись —
#  Android отказался бы ставить обновление поверх предыдущей версии.
# ============================================================

param(
    [string]$Sdk = "$env:USERPROFILE\Android\Sdk",
    [string]$BuildTools = "34.0.0",
    [string]$Platform = "android-34",
    [int]$MinSdk = 29
)

$ErrorActionPreference = "Stop"
$root = $PSScriptRoot
$main = Join-Path $root "app\src\main"
$bin = Join-Path $root "bin"

$aapt2     = Join-Path $Sdk "build-tools\$BuildTools\aapt2.exe"
$d8        = Join-Path $Sdk "build-tools\$BuildTools\d8.bat"
$zipalign  = Join-Path $Sdk "build-tools\$BuildTools\zipalign.exe"
$apksigner = Join-Path $Sdk "build-tools\$BuildTools\apksigner.bat"
$androidJar = Join-Path $Sdk "platforms\$Platform\android.jar"

foreach ($p in @($aapt2, $d8, $zipalign, $apksigner, $androidJar)) {
    if (-not (Test-Path $p)) { throw "Не найдено: $p" }
}

Write-Host "==> Очистка bin\"
Remove-Item $bin -Recurse -Force -ErrorAction SilentlyContinue
New-Item -ItemType Directory -Force -Path "$bin\classes", "$bin\gen" | Out-Null

Write-Host "==> 1/7 Компиляция ресурсов"
& $aapt2 compile --dir "$main\res" -o "$bin\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 compile failed" }

Write-Host "==> 2/7 Линковка пакета"
& $aapt2 link -o "$bin\base.apk" -I $androidJar `
    --manifest "$main\AndroidManifest.xml" `
    --java "$bin\gen" `
    --min-sdk-version $MinSdk --target-sdk-version 34 `
    --version-code 4 --version-name "3.1" `
    "$bin\res.zip"
if ($LASTEXITCODE -ne 0) { throw "aapt2 link failed" }

Write-Host "==> 3/7 Компиляция Java (нативные классы)"
$rJava = Get-ChildItem "$bin\gen" -Recurse -Filter "R.java" | Select-Object -First 1
$sources = Get-ChildItem "$main\java" -Recurse -Filter "*.java" | ForEach-Object { $_.FullName }
& javac --release 17 -encoding UTF-8 -classpath $androidJar -d "$bin\classes" @sources $rJava.FullName
if ($LASTEXITCODE -ne 0) { throw "javac failed" }

Write-Host "==> 4/7 DEX"
$classFiles = Get-ChildItem "$bin\classes" -Recurse -Filter "*.class" | ForEach-Object { $_.FullName }
& $d8 --release --lib $androidJar --output "$bin" @classFiles
if ($LASTEXITCODE -ne 0) { throw "d8 failed" }

Write-Host "==> 5/7 DEX в пакет"
Push-Location $bin
& jar --update --file base.apk -C $bin classes.dex
Pop-Location
if ($LASTEXITCODE -ne 0) { throw "jar failed" }

Write-Host "==> 6/7 zipalign"
& $zipalign -f 4 "$bin\base.apk" "$bin\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "zipalign failed" }

Write-Host "==> 7/7 подпись"
$ks = Join-Path $root "debug.keystore"
if (-not (Test-Path $ks)) {
    & keytool -genkeypair -keystore $ks -storepass android -keypass android `
        -alias androiddebugkey -keyalg RSA -keysize 2048 -validity 10950 `
        -dname "CN=Android Debug,O=Android,C=US" | Out-Null
}
& $apksigner sign --ks $ks --ks-pass pass:android --key-pass pass:android `
    --out "$bin\study-diary.apk" "$bin\aligned.apk"
if ($LASTEXITCODE -ne 0) { throw "apksigner failed" }

& $apksigner verify --print-certs "$bin\study-diary.apk" | Select-Object -First 3 | Out-String | Write-Host

$apk = Get-Item "$bin\study-diary.apk"
Write-Host ""
Write-Host ("ГОТОВО: " + $apk.FullName + " (" + [math]::Round($apk.Length / 1KB, 0) + " КБ)")
