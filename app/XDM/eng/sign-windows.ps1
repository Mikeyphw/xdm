param(
    [Parameter(Mandatory = $true)][string]$PublishDirectory,
    [Parameter(Mandatory = $true)][string]$CertificateBase64,
    [Parameter(Mandatory = $true)][string]$CertificatePassword
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $PublishDirectory)) { throw "Publish directory does not exist: $PublishDirectory" }
$CertificatePath = Join-Path ([System.IO.Path]::GetTempPath()) ("xdm-signing-" + [guid]::NewGuid() + ".pfx")
try {
    [System.IO.File]::WriteAllBytes($CertificatePath, [Convert]::FromBase64String($CertificateBase64))
    $SignTool = Get-ChildItem "${env:ProgramFiles(x86)}\Windows Kits\10\bin" -Filter signtool.exe -Recurse |
        Sort-Object FullName -Descending | Select-Object -First 1
    if ($null -eq $SignTool) { throw "signtool.exe was not found." }
    Get-ChildItem $PublishDirectory -Filter *.exe -File | ForEach-Object {
        & $SignTool.FullName sign /fd SHA256 /td SHA256 /tr http://timestamp.digicert.com /f $CertificatePath /p $CertificatePassword $_.FullName
        if ($LASTEXITCODE -ne 0) { throw "Authenticode signing failed for $($_.Name)." }
        & $SignTool.FullName verify /pa /all $_.FullName
        if ($LASTEXITCODE -ne 0) { throw "Authenticode verification failed for $($_.Name)." }
    }
}
finally {
    Remove-Item $CertificatePath -Force -ErrorAction SilentlyContinue
}
