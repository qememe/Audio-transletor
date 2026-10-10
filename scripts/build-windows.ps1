param([switch]$Package)
$ErrorActionPreference = 'Stop'
Set-Location (Join-Path $PSScriptRoot '..')
New-Item -ItemType Directory -Force build\desktop, dist | Out-Null
$sources = Get-ChildItem core\src,desktop\src -Recurse -Filter '*.java' | ForEach-Object { '"' + $_.FullName.Replace('\', '/') + '"' }
# PowerShell 5.1 defaults to UTF-16; javac argfile needs UTF-8 without BOM.
[System.IO.File]::WriteAllLines((Join-Path $PWD 'build\desktop-sources.txt'), $sources, (New-Object System.Text.UTF8Encoding($false)))
& javac --release 8 -encoding UTF-8 -d build\desktop '@build/desktop-sources.txt'
if ($LASTEXITCODE -ne 0) { throw 'javac failed' }
& jar cfe dist\AudioALL.jar org.audioall.desktop.Main -C build\desktop .
if ($LASTEXITCODE -ne 0) { throw 'jar failed' }
Copy-Item scripts\run-windows.bat dist\run-windows.bat -Force
if ($Package) {
    New-Item -ItemType Directory -Force build\package-input | Out-Null
    Copy-Item dist\AudioALL.jar build\package-input\AudioALL.jar -Force
    if (Test-Path dist\windows\AudioALL) { Remove-Item -Recurse -Force dist\windows\AudioALL }
    & jpackage --type app-image --name AudioALL --app-version 1.0.0 --input build\package-input --main-jar AudioALL.jar --main-class org.audioall.desktop.Main --dest dist\windows --java-options '-Xmx512m'
    if ($LASTEXITCODE -ne 0) { throw 'jpackage failed' }
    Copy-Item README.md,LICENSE dist\windows\AudioALL\ -Force
    Compress-Archive -Path dist\windows\AudioALL -DestinationPath dist\AudioALL-windows-x86_64.zip -Force
}
Write-Host 'Created dist/AudioALL.jar (run with Java 8+).'
