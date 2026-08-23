param(
    [switch]$SkipTests,
    [switch]$SkipInstaller,
    [string]$OutputPath = ""
)

$ErrorActionPreference = "Stop"
$Root = (Resolve-Path (Join-Path $PSScriptRoot "..")).Path
$Frontend = Join-Path $Root "frontend"
$Tauri = Join-Path $Frontend "src-tauri"
$Stage = Join-Path $Tauri "java-backend"
$Jar = Join-Path $Root "target\rag-java-0.0.1-SNAPSHOT.jar"

function Get-JavaMajor([string]$JdkHome) {
    $java = Join-Path $JdkHome "bin\java.exe"
    $jlink = Join-Path $JdkHome "bin\jlink.exe"
    if (-not (Test-Path $java) -or -not (Test-Path $jlink)) {
        return 0
    }
    $versionText = (& $java --version | Select-Object -First 1).ToString()
    if ($versionText -match '(?:openjdk|java)\s+["]?(\d+)') {
        return [int]$Matches[1]
    }
    return 0
}

function Find-Jdk {
    $candidates = [System.Collections.Generic.List[string]]::new()
    if ($env:JAVA_HOME) {
        $candidates.Add($env:JAVA_HOME)
    }
    $userJdks = Join-Path $HOME ".jdks"
    if (Test-Path $userJdks) {
        Get-ChildItem $userJdks -Directory |
            Sort-Object Name -Descending |
            ForEach-Object { $candidates.Add($_.FullName) }
    }
    @("C:\Program Files\Microsoft", "C:\Program Files\Eclipse Adoptium", "C:\Program Files\Java") |
        Where-Object { Test-Path $_ } |
        ForEach-Object {
            Get-ChildItem $_ -Directory |
                Sort-Object Name -Descending |
                ForEach-Object { $candidates.Add($_.FullName) }
        }

    foreach ($candidate in $candidates | Select-Object -Unique) {
        if ((Get-JavaMajor $candidate) -ge 17) {
            return (Resolve-Path $candidate).Path
        }
    }
    throw "JDK 17 or newer with jlink was not found."
}

function Assert-StagingPath([string]$Path) {
    $resolvedParent = (Resolve-Path (Split-Path $Path -Parent)).Path
    $tauriRoot = (Resolve-Path $Tauri).Path
    if (-not $resolvedParent.StartsWith($tauriRoot, [StringComparison]::OrdinalIgnoreCase)) {
        throw "Refusing to modify unexpected staging path: $Path"
    }
}

function Copy-Tree([string]$Source, [string]$Destination) {
    if (-not (Test-Path $Source)) {
        return
    }
    New-Item -ItemType Directory -Force -Path $Destination | Out-Null
    Copy-Item -Path (Join-Path $Source "*") -Destination $Destination -Recurse -Force
}

function Assert-SeedIndex {
    $dataRoot = Join-Path $Root "data\ukm_ftsm"
    $manifestPath = Join-Path $dataRoot "ingestion_manifest.json"
    if (-not (Test-Path $manifestPath)) {
        throw "Seed ingestion manifest is missing: $manifestPath"
    }

    $manifest = Get-Content $manifestPath -Raw | ConvertFrom-Json
    $documents = @($manifest.documents.PSObject.Properties)
    $allowedExtensions = @(".txt", ".pdf", ".png", ".jpg", ".jpeg", ".webp", ".gif")
    $sourceFiles = @(Get-ChildItem $dataRoot -File |
        Where-Object { $allowedExtensions -contains $_.Extension.ToLowerInvariant() })
    $transcriptStems = @{}
    $sourceFiles |
        Where-Object { $_.Extension.Equals(".txt", [StringComparison]::OrdinalIgnoreCase) } |
        ForEach-Object {
            $transcriptStems[[IO.Path]::ChangeExtension($_.FullName, $null).ToLowerInvariant()] = $true
        }
    $effectiveFiles = @($sourceFiles | Where-Object {
        $_.Extension.Equals(".txt", [StringComparison]::OrdinalIgnoreCase) -or
        -not $transcriptStems.ContainsKey(
            [IO.Path]::ChangeExtension($_.FullName, $null).ToLowerInvariant()
        )
    })

    if ($documents.Count -ne $effectiveFiles.Count) {
        throw "Seed manifest coverage mismatch: documents=$($documents.Count), effective files=$($effectiveFiles.Count)"
    }

    $chunkCount = 0
    foreach ($document in $documents) {
        $chunkCount += @($document.Value.chunk_ids).Count
    }
    if ($chunkCount -le 0) {
        throw "Seed manifest contains no chunks."
    }

    $websiteId = "file:data/ukm_ftsm/ftsm_official_website.txt"
    $website = $documents | Where-Object { $_.Name -eq $websiteId } | Select-Object -First 1
    if (-not $website -or @($website.Value.chunk_ids).Count -le 0) {
        throw "The required FTSM website seed document is not indexed."
    }

    $qdrantCollection = [string]$manifest.index.qdrant_collection
    $lexicalGeneration = [string]$manifest.index.lexical_generation
    if ([string]::IsNullOrWhiteSpace($qdrantCollection) -or
        [string]::IsNullOrWhiteSpace($lexicalGeneration)) {
        throw "Seed manifest does not identify the active Qdrant and Lucene generations."
    }

    $lexicalPath = Join-Path (Join-Path $Root "lucene_local") $lexicalGeneration
    if (-not (Test-Path $lexicalPath) -or
        -not (Get-ChildItem $lexicalPath -File -ErrorAction SilentlyContinue)) {
        throw "Lucene seed generation is missing or empty: $lexicalPath"
    }
    $qdrantStorage = Join-Path $Root "qdrant_local\storage"
    if (-not (Test-Path $qdrantStorage) -or
        -not (Get-ChildItem $qdrantStorage -Recurse -File -ErrorAction SilentlyContinue |
            Select-Object -First 1)) {
        throw "Qdrant seed storage is missing or empty: $qdrantStorage"
    }

    Write-Host "Validated seed index: $($documents.Count) documents, $chunkCount chunks."
}

function Initialize-MsvcEnvironment {
    if (Get-Command "link.exe" -ErrorAction SilentlyContinue) {
        return
    }

    $vswhereCandidates = @(
        (Join-Path ${env:ProgramFiles(x86)} "Microsoft Visual Studio\Installer\vswhere.exe"),
        (Join-Path $env:ProgramFiles "Microsoft Visual Studio\Installer\vswhere.exe")
    ) | Where-Object { $_ -and (Test-Path $_) }

    foreach ($vswhere in $vswhereCandidates) {
        $installationPath = & $vswhere -latest -products * `
            -requires Microsoft.VisualStudio.Component.VC.Tools.x86.x64 `
            -property installationPath
        if (-not $installationPath) {
            continue
        }

        $vsDevCmd = Join-Path $installationPath "Common7\Tools\VsDevCmd.bat"
        if (-not (Test-Path $vsDevCmd)) {
            continue
        }

        $environmentLines = & cmd.exe /d /s /c "`"$vsDevCmd`" -no_logo -arch=x64 -host_arch=x64 >nul && set"
        foreach ($line in $environmentLines) {
            $separator = $line.IndexOf("=")
            if ($separator -gt 0) {
                $name = $line.Substring(0, $separator)
                $value = $line.Substring($separator + 1)
                Set-Item -Path "Env:$name" -Value $value
            }
        }
        if (Get-Command "link.exe" -ErrorAction SilentlyContinue) {
            return
        }
    }

    throw @"
Microsoft C++ Build Tools were not found. Install Visual Studio Build Tools
with 'Desktop development with C++' and the Windows 10/11 SDK, then rerun this script.
Tauri prerequisites: https://v2.tauri.app/start/prerequisites/
"@
}

$JdkHome = Find-Jdk
$env:JAVA_HOME = $JdkHome
$env:Path = "$(Join-Path $JdkHome 'bin');$env:Path"
Write-Host "Using JDK: $JdkHome"

if (-not $SkipInstaller) {
    Initialize-MsvcEnvironment
}

Push-Location $Root
try {
    if ($SkipTests) {
        & .\mvnw.cmd clean package -DskipTests
    } else {
        & .\mvnw.cmd clean package
    }
    if ($LASTEXITCODE -ne 0) {
        throw "Maven build failed."
    }
} finally {
    Pop-Location
}

Push-Location $Frontend
try {
    if (-not (Test-Path "node_modules")) {
        & npm ci
        if ($LASTEXITCODE -ne 0) {
            throw "npm ci failed."
        }
    }
    & npm run lint
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend lint failed."
    }
    & npm run build
    if ($LASTEXITCODE -ne 0) {
        throw "Frontend build failed."
    }
} finally {
    Pop-Location
}

Assert-StagingPath $Stage
if (Test-Path $Stage) {
    Remove-Item -LiteralPath $Stage -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $Stage | Out-Null

Copy-Item $Jar (Join-Path $Stage "rag-java.jar")
Copy-Item (Join-Path $Root ".env.example") (Join-Path $Stage ".env.example")

$Runtime = Join-Path $Stage "runtime"
& (Join-Path $JdkHome "bin\jlink.exe") `
    --add-modules ALL-MODULE-PATH `
    --strip-debug `
    --no-header-files `
    --no-man-pages `
    --compress=2 `
    --output $Runtime
if ($LASTEXITCODE -ne 0) {
    throw "jlink runtime build failed."
}

$QdrantStage = Join-Path $Stage "qdrant"
New-Item -ItemType Directory -Force -Path $QdrantStage | Out-Null
$ProjectQdrant = (Resolve-Path (Join-Path $Root "qdrant_local\qdrant.exe")).Path
Copy-Item $ProjectQdrant (Join-Path $QdrantStage "qdrant.exe")

$SeedData = Join-Path $Stage "seed-data"
New-Item -ItemType Directory -Force -Path $SeedData | Out-Null
$DataRoot = Join-Path $Root "data\ukm_ftsm"
$allowedExtensions = @(".txt", ".pdf", ".png", ".jpg", ".jpeg", ".webp", ".gif")
Get-ChildItem $DataRoot -Recurse -File |
    Where-Object {
        $allowedExtensions -contains $_.Extension.ToLowerInvariant() -or
        $_.Name -eq "ingestion_manifest.json"
    } |
    ForEach-Object {
        $relative = $_.FullName.Substring($DataRoot.Length).TrimStart("\", "/")
        $target = Join-Path $SeedData $relative
        New-Item -ItemType Directory -Force -Path (Split-Path $target -Parent) | Out-Null
        Copy-Item $_.FullName $target -Force
    }

$runningProjectQdrant = @()
try {
    $runningProjectQdrant = Get-CimInstance Win32_Process -Filter "Name = 'qdrant.exe'" |
        Where-Object {
            $_.ExecutablePath -and
            [IO.Path]::GetFullPath($_.ExecutablePath).Equals(
                $ProjectQdrant,
                [StringComparison]::OrdinalIgnoreCase
            )
        }
} catch {
    throw "Could not verify whether Qdrant is stopped: $($_.Exception.Message)"
}
if ($runningProjectQdrant) {
    throw "The project Qdrant process is running. Stop the application before packaging its storage."
}

Assert-SeedIndex
Copy-Tree (Join-Path $Root "qdrant_local\storage") (Join-Path $Stage "seed-qdrant-storage")
Copy-Tree (Join-Path $Root "lucene_local") (Join-Path $Stage "seed-lucene-storage")

$seedHashes = [ordered]@{}
Get-ChildItem $SeedData -Recurse -File |
    Where-Object { $_.Name -ne "ingestion_manifest.json" } |
    Sort-Object FullName |
    ForEach-Object {
        $relative = $_.FullName.Substring($SeedData.Length).TrimStart("\", "/").Replace("\", "/")
        $seedHashes[$relative] = (Get-FileHash $_.FullName -Algorithm SHA256).Hash.ToLowerInvariant()
    }
$seedManifest = [ordered]@{
    version = "0.2.0"
    files = $seedHashes
}
$seedManifestPath = Join-Path $Stage "seed-manifest.json"
$seedManifestJson = $seedManifest | ConvertTo-Json -Depth 6
[IO.File]::WriteAllText(
    $seedManifestPath,
    $seedManifestJson,
    [Text.UTF8Encoding]::new($false)
)

if ($SkipInstaller) {
    Write-Host "Desktop resources prepared at: $Stage"
    exit 0
}

Push-Location $Frontend
try {
    & npx tauri build --bundles nsis
    if ($LASTEXITCODE -ne 0) {
        throw "Tauri installer build failed."
    }
} finally {
    Pop-Location
}

$NsisDir = Join-Path $Tauri "target\release\bundle\nsis"
$Installer = Get-ChildItem $NsisDir -Filter "*.exe" |
    Sort-Object LastWriteTime -Descending |
    Select-Object -First 1
if (-not $Installer) {
    throw "No NSIS installer was produced in $NsisDir"
}

if (-not $OutputPath) {
    $OutputPath = Join-Path $Root "dist\FTSM-RAG-0.2.0-windows-x64-setup.exe"
}
if (-not [IO.Path]::IsPathRooted($OutputPath)) {
    $OutputPath = Join-Path $Root $OutputPath
}
$OutputPath = [IO.Path]::GetFullPath($OutputPath)
New-Item -ItemType Directory -Force -Path (Split-Path $OutputPath -Parent) | Out-Null
Copy-Item $Installer.FullName $OutputPath -Force
Write-Host "Installer written to: $OutputPath"
