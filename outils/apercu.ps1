# Fait publication\apercu.html : la description avec les images intégrées, pour la regarder avant de publier.
# Lancer :  powershell -ExecutionPolicy Bypass -File outils\apercu.ps1

$ErrorActionPreference = 'Stop'
$pub = Join-Path (Split-Path $PSScriptRoot -Parent) 'publication'
$html = [IO.File]::ReadAllText((Join-Path $pub 'description.html'), [Text.Encoding]::UTF8)
$html = [regex]::Replace($html, 'src="images/(\w+\.png)"', {
    param($m)
    $b = [Convert]::ToBase64String([IO.File]::ReadAllBytes((Join-Path $pub "images\$($m.Groups[1].Value)")))
    "src=`"data:image/png;base64,$b`""
})
$page = @"
<!doctype html>
<html lang="en"><head><meta charset="utf-8"><meta name="viewport" content="width=device-width, initial-scale=1">
<title>Radio - preview</title>
<style>
body { background: #1b1b1b; color: #d8d8d8; font: 16px/1.6 'Segoe UI', sans-serif; margin: 0; }
main { max-width: 920px; margin: 0 auto; padding: 24px 16px; }
img { max-width: 100%; height: auto; }
h2 { color: #fff; border-bottom: 1px solid #333; padding-bottom: 4px; margin-top: 2em; }
a { color: #f0a050; }
td { padding: 4px 16px 4px 0; border-bottom: 1px solid #333; }
</style></head><body><main>
$html
</main></body></html>
"@
[IO.File]::WriteAllText((Join-Path $pub 'apercu.html'), $page, (New-Object Text.UTF8Encoding $false))
Write-Host 'apercu.html'
