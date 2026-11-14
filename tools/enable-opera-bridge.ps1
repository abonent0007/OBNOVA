#Requires -Version 5.1
<#
.SYNOPSIS
    Пробрасывает Opera-прокси с ПК на телефон через adb reverse.

.DESCRIPTION
    На ПК должен быть запущен opera-proxy на 127.0.0.1:1371 (US/EU).
    Скрипт ждёт авторизованное устройство, ставит `adb reverse tcp:1371 tcp:1371`
    и проверяет, что мост виден. После этого Nova в MULTI видит Opera как
    SECONDARY и пускает остальные приложения через non-RU выход.

    Запускать после каждого подключения телефона по USB: adb reverse живёт
    только пока существует транспорт adb.

.EXAMPLE
    powershell -ExecutionPolicy Bypass -File tools\enable-opera-bridge.ps1
#>
[CmdletBinding()]
param(
    [string]$Serial = "",
    [int]$Port = 1371,
    [int]$WaitSeconds = 60
)

$ErrorActionPreference = "Stop"

function Find-Adb {
    $candidates = @(
        (Join-Path $env:USERPROFILE "Android\Sdk\platform-tools\adb.exe"),
        (Join-Path $env:LOCALAPPDATA "Android\Sdk\platform-tools\adb.exe"),
        "adb.exe"
    )
    foreach ($candidate in $candidates) {
        try {
            $command = Get-Command $candidate -ErrorAction Stop
            return $command.Source
        } catch {
        }
    }
    throw "adb не найден. Укажите ANDROID_HOME или добавьте platform-tools в PATH."
}

function Wait-Device([string]$Adb, [string]$Wanted, [int]$Seconds) {
    $deadline = (Get-Date).AddSeconds($Seconds)
    while ((Get-Date) -lt $deadline) {
        $lines = & $Adb devices 2>$null | Select-Object -Skip 1
        $ready = @()
        foreach ($line in $lines) {
            $parts = ($line -split "\s+") | Where-Object { $_ }
            if ($parts.Count -ge 2 -and $parts[1] -eq "device") {
                $ready += $parts[0]
            }
        }
        if ($Wanted) {
            if ($ready -contains $Wanted) { return $Wanted }
        } elseif ($ready.Count -gt 0) {
            return $ready[0]
        }
        Start-Sleep -Seconds 1
    }
    throw "ADB: нет авторизованного устройства за $Seconds с. Подключите телефон и разрешите отладку."
}

$adb = Find-Adb
$device = Wait-Device -Adb $adb -Wanted $Serial -Seconds $WaitSeconds
Write-Host "== Устройство: $device =="

$pcListener = Get-NetTCPConnection -LocalPort $Port -State Listen -ErrorAction SilentlyContinue
if (-not $pcListener) {
    Write-Warning "На ПК никто не слушает 127.0.0.1:$Port. Запустите opera-proxy (US/EU) — иначе мост будет пустым."
} else {
    Write-Host "== Opera на ПК: 127.0.0.1:$Port (PID $($pcListener[0].OwningProcess)) =="
}

& $adb -s $device reverse "tcp:$Port" "tcp:$Port" | Out-Null
$list = & $adb -s $device reverse --list
if ($list -match "tcp:$Port") {
    Write-Host "== Мост включён: телефон 127.0.0.1:$Port -> ПК 127.0.0.1:$Port =="
    Write-Host "   В Nova выберите MULTI и подключитесь. Проверка: Chrome -> https://ipinfo.io/json"
} else {
    throw "adb reverse не появился в списке. Проверьте USB-отладку и повторите."
}
