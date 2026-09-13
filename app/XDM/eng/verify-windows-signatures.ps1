param(
    [Parameter(Mandatory = $true)][string]$PublishDirectory
)
$ErrorActionPreference = "Stop"
if (-not (Test-Path $PublishDirectory)) { throw "Publish directory does not exist: $PublishDirectory" }
$Executables = Get-ChildItem $PublishDirectory -Filter *.exe -File
if ($Executables.Count -eq 0) { throw "No Windows executables were found for signature verification in $PublishDirectory" }
foreach ($Executable in $Executables) {
    $Signature = Get-AuthenticodeSignature -FilePath $Executable.FullName
    if ($Signature.Status -ne 'Valid') {
        throw "Authenticode signature verification failed for $($Executable.Name): $($Signature.Status) $($Signature.StatusMessage)"
    }
}
Write-Host "Verified Authenticode signatures in $PublishDirectory"
