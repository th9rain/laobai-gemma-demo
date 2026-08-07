param(
    [Parameter(Mandatory = $true)]
    [string]$Serial,

    [Parameter(Mandatory = $true)]
    [string]$ModelPath,

    [string]$RemoteDirectory = "/data/local/tmp/laobai-model-parts",

    [ValidateRange(16, 512)]
    [int]$ChunkSizeMiB = 256
)

$ErrorActionPreference = "Stop"

$resolvedModelPath = (Resolve-Path -LiteralPath $ModelPath).Path
$temporaryDirectory = Join-Path ([System.IO.Path]::GetTempPath()) "laobai-model-transfer"
$temporaryChunk = Join-Path $temporaryDirectory "current.part"
New-Item -ItemType Directory -Path $temporaryDirectory -Force | Out-Null

& adb -s $Serial shell am force-stop com.laobai.demo
& adb -s $Serial shell "mkdir -p $RemoteDirectory"
if ($LASTEXITCODE -ne 0) {
    throw "Unable to create remote directory $RemoteDirectory"
}

$inputStream = [System.IO.File]::OpenRead($resolvedModelPath)
$chunkSize = [int64]$ChunkSizeMiB * 1MB
$buffer = New-Object byte[] (8MB)
$partIndex = 0

try {
    while ($inputStream.Position -lt $inputStream.Length) {
        $partName = "part-{0:D4}" -f $partIndex
        $remotePart = "$RemoteDirectory/$partName"
        $remaining = [Math]::Min($chunkSize, $inputStream.Length - $inputStream.Position)
        $outputStream = [System.IO.File]::Open(
            $temporaryChunk,
            [System.IO.FileMode]::Create,
            [System.IO.FileAccess]::Write,
            [System.IO.FileShare]::None
        )

        try {
            while ($remaining -gt 0) {
                $requested = [int][Math]::Min([int64]$buffer.Length, $remaining)
                $read = $inputStream.Read($buffer, 0, $requested)
                if ($read -le 0) {
                    throw "Unexpected end of model while creating $partName"
                }
                $outputStream.Write($buffer, 0, $read)
                $remaining -= $read
            }
        }
        finally {
            $outputStream.Dispose()
        }

        $localHash = (Get-FileHash -LiteralPath $temporaryChunk -Algorithm SHA256).Hash.ToLower()
        $verified = $false

        for ($attempt = 1; $attempt -le 5 -and -not $verified; $attempt++) {
            $partLength = (Get-Item -LiteralPath $temporaryChunk).Length
            Write-Output "UPLOAD $partName attempt=$attempt bytes=$partLength"

            & adb -s $Serial wait-for-device
            & adb -s $Serial push $temporaryChunk $remotePart
            $pushExitCode = $LASTEXITCODE

            if ($pushExitCode -eq 0) {
                $hashOutput = & adb -s $Serial shell "sha256sum $remotePart"
                if ($LASTEXITCODE -eq 0 -and $hashOutput) {
                    $remoteHash = (($hashOutput | Select-Object -Last 1) -split "\s+")[0].Trim().ToLower()
                    if ($remoteHash -eq $localHash) {
                        $verified = $true
                        Write-Output "VERIFIED $partName $localHash"
                    }
                    else {
                        Write-Output "HASH_MISMATCH $partName local=$localHash remote=$remoteHash"
                    }
                }
            }

            if (-not $verified) {
                Start-Sleep -Seconds 2
            }
        }

        if (-not $verified) {
            throw "Failed to upload and verify $partName after 5 attempts"
        }

        $partIndex++
    }

    Write-Output "ALL_PARTS_VERIFIED count=$partIndex"
}
finally {
    $inputStream.Dispose()
    if (Test-Path -LiteralPath $temporaryChunk) {
        Remove-Item -LiteralPath $temporaryChunk -Force
    }
}
