# Génère les modèles 3D des blocs et leurs textures peintes (style « meuble » : couleurs douces,
# liseré sombre au bord de chaque pièce, reflet en haut, détails dessinés sur les façades).
# Chaque bloc a une seule texture où toutes ses faces sont rangées côte à côte.
#
# Lancer :  powershell -ExecutionPolicy Bypass -File outils\modeles.ps1
# Puis recompiler le mod.

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$assets = Join-Path $root 'src\main\resources\assets\radio'
$S = 2          # pixels par unité de modèle (32 pixels pour un bloc entier)
$ATLAS = 128    # taille de la texture de chaque bloc
$utf8 = New-Object System.Text.UTF8Encoding $false

function Col([string]$h) { [System.Drawing.ColorTranslator]::FromHtml($h) }
function Clamp([double]$v) { [int][Math]::Max(0, [Math]::Min(255, [Math]::Round($v))) }
function Mix($a, $b, [double]$t) {
    [System.Drawing.Color]::FromArgb(255, (Clamp ($a.R + ($b.R - $a.R) * $t)), (Clamp ($a.G + ($b.G - $a.G) * $t)), (Clamp ($a.B + ($b.B - $a.B) * $t)))
}
function Lit($c, [double]$d) { [System.Drawing.Color]::FromArgb(255, (Clamp ($c.R + $d)), (Clamp ($c.G + $d)), (Clamp ($c.B + $d))) }

# ---------- Matériaux ----------
$MAT = @{
    wood  = @{ base = '#C38E5E'; dark = '#A2734A'; light = '#DCA878'; edge = '#6E4528'; kind = 'wood' }
    trim  = @{ base = '#7C5436'; dark = '#634129'; light = '#966948'; edge = '#3C2616'; kind = 'wood' }
    metal = @{ base = '#C8CDD2'; dark = '#9CA2A8'; light = '#EEF1F4'; edge = '#686E74'; kind = 'metal' }
    black = @{ base = '#3D3D44'; dark = '#2E2E34'; light = '#585861'; edge = '#18181C'; kind = 'flat' }
    gray  = @{ base = '#737985'; dark = '#5F6470'; light = '#8D939E'; edge = '#363A42'; kind = 'flat' }
    red   = @{ base = '#C8483C'; dark = '#A2342A'; light = '#E06E61'; edge = '#6A1E18'; kind = 'flat' }
    felt  = @{ base = '#303036'; dark = '#27272C'; light = '#3C3C43'; edge = '#141417'; kind = 'felt' }
}

# ---------- Peinture d'une face ----------
function Paint-Base($bmp, $ox, $oy, $pw, $ph, $m, $dir, $seed) {
    $rnd = New-Object System.Random $seed
    $base = Col $m.base; $dark = Col $m.dark; $light = Col $m.light; $edge = Col $m.edge
    $side = $dir -ne 'up' -and $dir -ne 'down'
    # Veinage du bois : une teinte par « fibre » (lignes sur les côtés, colonnes sur le dessus)
    $fibre = @{}
    for ($y = 0; $y -lt $ph; $y++) {
        for ($x = 0; $x -lt $pw; $x++) {
            switch ($m.kind) {
                'wood' {
                    $k = if ($side) { $y } else { $x }
                    if (-not $fibre.ContainsKey($k)) { $fibre[$k] = $rnd.NextDouble() }
                    $f = $fibre[$k]
                    $c = if ($f -lt 0.18) { Mix $base $dark 0.7 } elseif ($f -gt 0.86) { Mix $base $light 0.45 } else { $base }
                    if ($rnd.NextDouble() -lt 0.04) { $c = Mix $c $dark 0.5 }
                }
                'metal' {
                    $t = if ($ph -gt 1) { $y / ($ph - 1) } else { 0.5 }
                    if (-not $side) { $t = 0.25 + 0.3 * ($x / [Math]::Max(1, $pw - 1)) }
                    $c = if ($t -lt 0.5) { Mix $light $base ($t * 2) } else { Mix $base $dark (($t - 0.5) * 2) }
                }
                'felt' { $c = if ((($x + $y) % 2) -eq 0) { $base } else { $dark } }
                default { $c = Lit $base ($rnd.Next(-3, 4)) }
            }
            $bmp.SetPixel($ox + $x, $oy + $y, $c)
        }
    }
    # Bords : liseré sombre tout autour, reflet en haut et à gauche (si la face est assez grande)
    if ($pw -ge 3 -and $ph -ge 3) {
        for ($x = 0; $x -lt $pw; $x++) {
            $bmp.SetPixel($ox + $x, $oy, (Mix $base $edge 0.65))
            $bmp.SetPixel($ox + $x, $oy + $ph - 1, (Mix $base $edge 0.8))
            if ($x -gt 0 -and $x -lt $pw - 1) { $bmp.SetPixel($ox + $x, $oy + 1, (Mix $bmp.GetPixel($ox + $x, $oy + 1) $light 0.55)) }
        }
        for ($y = 0; $y -lt $ph; $y++) {
            $bmp.SetPixel($ox, $oy + $y, (Mix $base $edge 0.65))
            $bmp.SetPixel($ox + $pw - 1, $oy + $y, (Mix $base $edge 0.75))
            if ($y -gt 1 -and $y -lt $ph - 1) { $bmp.SetPixel($ox + 1, $oy + $y, (Mix $bmp.GetPixel($ox + 1, $oy + $y) $light 0.3)) }
        }
    } elseif ($pw -ge 2 -and $ph -ge 2) {
        for ($x = 0; $x -lt $pw; $x++) { $bmp.SetPixel($ox + $x, $oy + $ph - 1, (Mix $base $edge 0.6)) }
    }
}

# ---------- Détails des façades ----------
function Paint-Cone($bmp, $ox, $oy, $pw, $ph) {
    # Toute la face : coins couleur façade, puis bague en métal, membrane en dégradé, cache central
    $cx = ($pw - 1) / 2.0; $cy = ($ph - 1) / 2.0; $r = [Math]::Min($pw, $ph) / 2.0
    for ($y = 0; $y -lt $ph; $y++) { for ($x = 0; $x -lt $pw; $x++) {
        $dx = $x - $cx; $dy = $y - $cy; $d = [Math]::Sqrt($dx * $dx + $dy * $dy) / $r; $l = -($dx + $dy) / ($r * 2)
        if ($d -gt 1.0) { $c = Col '#2B2B31' }
        elseif ($d -gt 0.82) { $c = Mix (Col '#E8ECF0') (Col '#6E747C') (0.5 - $l * 1.2) }
        elseif ($d -gt 0.72) { $c = Col '#131316' }
        elseif ($d -gt 0.28) { $c = Mix (Col '#55555E') (Col '#24242A') (($d - 0.28) / 0.44 - $l * 0.5) }
        elseif ($d -gt 0.2) { $c = Col '#18181C' }
        else { $c = Mix (Col '#F2F4F7') (Col '#8E949C') (0.4 - $l) }
        $bmp.SetPixel($ox + $x, $oy + $y, $c)
    } }
}
function Paint-Grille($bmp, $ox, $oy, $pw, $ph) {
    for ($y = 1; $y -lt $ph - 1; $y++) { for ($x = 1; $x -lt $pw - 1; $x++) {
        $c = if (($x % 2) -eq 1 -and ($y % 2) -eq 1) { Col '#55555D' } else { Col '#2A2A30' }
        $bmp.SetPixel($ox + $x, $oy + $y, $c)
    } }
}

function Paint-Dial($bmp, $ox, $oy, $pw, $ph) {
    for ($y = 1; $y -lt $ph - 1; $y++) { for ($x = 1; $x -lt $pw - 1; $x++) { $bmp.SetPixel($ox + $x, $oy + $y, (Col '#EADBA6')) } }
    for ($x = 2; $x -lt $pw - 2; $x += 2) { $bmp.SetPixel($ox + $x, $oy + 2, (Col '#7A6638')); if ($ph -gt 6) { $bmp.SetPixel($ox + $x, $oy + $ph - 3, (Col '#7A6638')) } }
    for ($x = 3; $x -lt $pw - 2; $x += 4) { $bmp.SetPixel($ox + $x, $oy + 3, (Col '#9C8650')) }
    $n = [int]($pw * 0.42)
    for ($y = 2; $y -lt $ph - 2; $y++) { $bmp.SetPixel($ox + $n, $oy + $y, (Col '#B83A2E')) }
    for ($i = 1; $i -le 3; $i++) { if ($i -lt $pw - 1 -and $i -lt $ph - 1) { $bmp.SetPixel($ox + $i, $oy + 1, (Col '#FFF8E0')) } }
}

function Paint-Cassette($bmp, $ox, $oy, $pw, $ph) {
    for ($y = 2; $y -lt $ph - 2; $y++) { for ($x = 1; $x -lt $pw - 1; $x++) { $bmp.SetPixel($ox + $x, $oy + $y, (Col '#26262B')) } }
    for ($y = 3; $y -lt $ph - 3; $y++) { for ($x = 2; $x -lt $pw - 2; $x++) { $bmp.SetPixel($ox + $x, $oy + $y, (Col '#3A3A42')) } }
    $cy = [int]($ph / 2)
    foreach ($cx in @([int]($pw * 0.3), [int]($pw * 0.7))) {
        for ($y = $cy - 1; $y -le $cy + 1; $y++) { for ($x = $cx - 1; $x -le $cx + 1; $x++) { $bmp.SetPixel($ox + $x, $oy + $y, (Col '#E2E2E6')) } }
        $bmp.SetPixel($ox + $cx, $oy + $cy, (Col '#26262B'))
    }
    for ($x = [int]($pw * 0.3) + 2; $x -le [int]($pw * 0.7) - 2; $x++) { $bmp.SetPixel($ox + $x, $oy + $cy + 1, (Col '#7A5233')) }
}

function Paint-Lcd($bmp, $ox, $oy, $pw, $ph) {
    for ($y = 1; $y -lt $ph - 1; $y++) { for ($x = 1; $x -lt $pw - 1; $x++) { $bmp.SetPixel($ox + $x, $oy + $y, (Col '#2F8F6C')) } }
    $rnd = New-Object System.Random 3
    for ($x = 2; $x -lt $pw - 2; $x += 2) { $h = $rnd.Next(1, [Math]::Max(2, $ph - 3)); for ($y = $ph - 2; $y -ge $ph - 1 - $h; $y--) { $bmp.SetPixel($ox + $x, $oy + $y, (Col '#B9F5D8')) } }
}

# ---------- Rangement des faces dans la texture ----------
$script:shelfX = 0; $script:shelfY = 0; $script:shelfH = 0
function Alloc($pw, $ph) {
    if ($script:shelfX + $pw -gt $script:ATLAS) { $script:shelfX = 0; $script:shelfY += $script:shelfH + 1; $script:shelfH = 0 }
    if ($script:shelfY + $ph -gt $script:ATLAS) { throw "Texture trop petite" }
    $p = @($script:shelfX, $script:shelfY)
    $script:shelfX += $pw + 1
    $script:shelfH = [Math]::Max($script:shelfH, $ph)
    return $p
}

function FaceSize($from, $to, $dir) {
    $dx = $to[0] - $from[0]; $dy = $to[1] - $from[1]; $dz = $to[2] - $from[2]
    switch ($dir) { { $_ -in 'north', 'south' } { return @($dx, $dy) } { $_ -in 'east', 'west' } { return @($dz, $dy) } default { return @($dx, $dz) } }
}

# Une pièce : nom, coins, matériau, détail sur la face avant (north), faces gardées, rotation (origine de l'antenne)
function Part($name, $from, $to, $mat, $decal = $null, $faces = @('north', 'south', 'east', 'west', 'up', 'down'), $rot = $null) {
    [ordered]@{ name = $name; from = $from; to = $to; mat = $mat; decal = $decal; faces = $faces; rot = $rot }
}

function Build($blockName, $parts) {
    # On essaie une texture de 128 pixels, puis 256 si les faces ne tiennent pas
    foreach ($size in 128, 256) {
        $script:ATLAS = $size
        try { BuildAt $blockName $parts; return } catch { if ("$_" -ne 'Texture trop petite') { throw } }
    }
    throw "Bloc trop grand : $blockName"
}

function BuildAt($blockName, $parts) {
    $script:shelfX = 0; $script:shelfY = 0; $script:shelfH = 0
    $bmp = New-Object System.Drawing.Bitmap $script:ATLAS, $script:ATLAS
    $k = $script:ATLAS / 16.0
    $elements = @()
    $seed = 1
    foreach ($p in $parts) {
        $faces = [ordered]@{}
        foreach ($dir in $p.faces) {
            $sz = FaceSize $p.from $p.to $dir
            $pw = [Math]::Max(1, [int][Math]::Ceiling($sz[0] * $S - 0.01))
            $ph = [Math]::Max(1, [int][Math]::Ceiling($sz[1] * $S - 0.01))
            $at = Alloc $pw $ph
            Paint-Base $bmp $at[0] $at[1] $pw $ph $MAT[$p.mat] $dir ($seed++)
            if ($dir -eq 'north' -and $p.decal) {
                switch ($p.decal) {
                    'cone' { Paint-Cone $bmp $at[0] $at[1] $pw $ph }
                    'grille' { Paint-Grille $bmp $at[0] $at[1] $pw $ph }
                    'dial' { Paint-Dial $bmp $at[0] $at[1] $pw $ph }
                    'cassette' { Paint-Cassette $bmp $at[0] $at[1] $pw $ph }
                    'lcd' { Paint-Lcd $bmp $at[0] $at[1] $pw $ph }
                }
            }
            $uv = @([Math]::Round($at[0] / $k, 4), [Math]::Round($at[1] / $k, 4), [Math]::Round(($at[0] + $pw) / $k, 4), [Math]::Round(($at[1] + $ph) / $k, 4))
            $face = [ordered]@{ uv = $uv; texture = '#t' }
            if ($dir -eq 'down' -and $p.from[1] -eq 0) { $face.cullface = 'down' }
            $faces[$dir] = $face
        }
        $el = [ordered]@{ name = $p.name; from = $p.from; to = $p.to; faces = $faces }
        if ($p.rot) { $el.rotation = [ordered]@{ angle = -22.5; axis = 'z'; origin = $p.rot } }
        $elements += $el
    }
    $bmp.Save((Join-Path $assets "textures\block\$blockName.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    $model = [ordered]@{
        parent = 'minecraft:block/block'
        textures = [ordered]@{ particle = "radio:block/$blockName"; t = "radio:block/$blockName" }
        elements = $elements
    }
    [IO.File]::WriteAllText((Join-Path $assets "models\block\$blockName.json"), ($model | ConvertTo-Json -Depth 10), $utf8)
    Write-Host "$blockName : $($elements.Count) pièces, texture $($script:ATLAS) px"
}

$side4 = @('north', 'south', 'east', 'west')
$noBack = @('north', 'east', 'west', 'up', 'down')

# ---------- Radio en bois ----------
Build 'radio' @(
    (Part 'socle' @(1.5, 0.5, 4.5) @(14.5, 1.5, 11.5) 'trim'),
    (Part 'pied1' @(2, 0, 5) @(3, 0.5, 6) 'trim'), (Part 'pied2' @(13, 0, 5) @(14, 0.5, 6) 'trim'),
    (Part 'pied3' @(2, 0, 10) @(3, 0.5, 11) 'trim'), (Part 'pied4' @(13, 0, 10) @(14, 0.5, 11) 'trim'),
    (Part 'caisse' @(2, 1.5, 5) @(14, 9, 11) 'wood'),
    (Part 'couvercle' @(1.5, 9, 4.5) @(14.5, 10, 11.5) 'trim'),
    (Part 'dessus' @(2.5, 10, 5.5) @(13.5, 10.5, 10.5) 'wood'),
    (Part 'grille' @(7.5, 2.5, 4.75) @(13.5, 8, 5) 'trim' 'grille' $noBack),
    (Part 'cadran' @(2.5, 5, 4.75) @(7, 8, 5) 'metal' 'dial' $noBack),
    (Part 'molette1' @(2.75, 2.5, 4) @(4.25, 4, 5) 'metal'), (Part 'molette2' @(5.25, 2.5, 4) @(6.75, 4, 5) 'metal'),
    (Part 'poignee_g' @(4, 10.5, 7.5) @(5, 12, 8.5) 'trim' $null $side4), (Part 'poignee_d' @(11, 10.5, 7.5) @(12, 12, 8.5) 'trim' $null $side4),
    (Part 'poignee' @(4, 12, 7.5) @(12, 13, 8.5) 'black'),
    (Part 'socle_antenne' @(12.5, 10.5, 9.5) @(13.5, 11, 10.5) 'metal'),
    (Part 'antenne' @(12.75, 11, 9.75) @(13.25, 17, 10.25) 'metal' $null @('north', 'south', 'east', 'west') @(13, 11, 10)),
    (Part 'boule' @(12.5, 17, 9.5) @(13.5, 18, 10.5) 'metal' $null @('north', 'south', 'east', 'west', 'up', 'down') @(13, 11, 10))
)

# ---------- Radio-cassette ----------
Build 'boombox' @(
    (Part 'base' @(0, 0.5, 4.5) @(16, 1, 11.5) 'black'),
    (Part 'pied1' @(1, 0, 5) @(2.5, 0.5, 6.5) 'black'), (Part 'pied2' @(13.5, 0, 5) @(15, 0.5, 6.5) 'black'),
    (Part 'pied3' @(1, 0, 9.5) @(2.5, 0.5, 11) 'black'), (Part 'pied4' @(13.5, 0, 9.5) @(15, 0.5, 11) 'black'),
    (Part 'caisse' @(0.5, 1, 5) @(15.5, 9, 11) 'gray'),
    (Part 'facade' @(0.75, 1.25, 4.75) @(15.25, 8.75, 5) 'black' $null $noBack),
    (Part 'liseret' @(0.25, 8.75, 4.75) @(15.75, 9.25, 11.25) 'metal'),
    (Part 'hp_g' @(1, 1.75, 4.5) @(6, 6.75, 4.75) 'black' 'cone' $noBack),
    (Part 'hp_d' @(10, 1.75, 4.5) @(15, 6.75, 4.75) 'black' 'cone' $noBack),
    (Part 'aigu_g' @(2, 7, 4.5) @(5, 8.25, 4.75) 'metal' 'grille' $noBack),
    (Part 'aigu_d' @(11, 7, 4.5) @(14, 8.25, 4.75) 'metal' 'grille' $noBack),
    (Part 'cassette' @(6.25, 1.75, 4.5) @(9.75, 6.25, 4.75) 'metal' 'cassette' $noBack),
    (Part 'ecran' @(6.25, 6.75, 4.5) @(9.75, 8.25, 4.75) 'black' 'lcd' $noBack),
    (Part 'touche1' @(6.5, 9.25, 6) @(7.25, 9.75, 7) 'red'), (Part 'touche2' @(7.5, 9.25, 6) @(8.25, 9.75, 7) 'metal'),
    (Part 'touche3' @(8.5, 9.25, 6) @(9.25, 9.75, 7) 'metal'),
    (Part 'poignee_g' @(2, 9.25, 7.5) @(3, 12, 8.5) 'black' $null $side4), (Part 'poignee_d' @(13, 9.25, 7.5) @(14, 12, 8.5) 'black' $null $side4),
    (Part 'poignee' @(2, 12, 7.5) @(14, 13, 8.5) 'black'),
    (Part 'prise' @(5, 12.25, 7.25) @(11, 13.25, 8.75) 'metal'),
    (Part 'socle_antenne' @(14.25, 9.25, 9.5) @(15.25, 9.75, 10.5) 'metal'),
    (Part 'antenne' @(14.5, 9.75, 9.75) @(15, 17.5, 10.25) 'metal' $null @('north', 'south', 'east', 'west') @(14.75, 9.75, 10)),
    (Part 'bout' @(14.25, 17.5, 9.5) @(15.25, 18, 10.5) 'metal' $null @('north', 'south', 'east', 'west', 'up', 'down') @(14.75, 9.75, 10))
)

# ---------- Petite enceinte (caisson en bois) ----------
Build 'speaker' @(
    (Part 'socle' @(3, 0, 4) @(13, 1, 12) 'trim'),
    (Part 'caisse' @(3.5, 1, 4.5) @(12.5, 13, 11.5) 'wood'),
    (Part 'dessus' @(3, 13, 4) @(13, 14, 12) 'trim'),
    (Part 'facade' @(4, 1.5, 4.25) @(12, 12.5, 4.5) 'black' $null $noBack),
    (Part 'grave' @(4.5, 2, 4) @(11.5, 9, 4.25) 'black' 'cone' $noBack),
    (Part 'aigu' @(6.25, 9.25, 4) @(9.75, 12.25, 4.25) 'black' 'cone' $noBack)
)

# ---------- Enceinte de concert ----------
Build 'speaker_big' @(
    (Part 'caisse' @(0.5, 0.5, 0.5) @(15.5, 15.5, 15.5) 'felt'),
    (Part 'coin1' @(0, 0, 0) @(1, 16, 1) 'metal'), (Part 'coin2' @(15, 0, 0) @(16, 16, 1) 'metal'),
    (Part 'coin3' @(0, 0, 15) @(1, 16, 16) 'metal'), (Part 'coin4' @(15, 0, 15) @(16, 16, 16) 'metal'),
    (Part 'bas_av' @(1, 0, 0) @(15, 1, 1) 'black'), (Part 'haut_av' @(1, 15, 0) @(15, 16, 1) 'black'),
    (Part 'bas_ar' @(1, 0, 15) @(15, 1, 16) 'black'), (Part 'haut_ar' @(1, 15, 15) @(15, 16, 16) 'black'),
    (Part 'bas_g' @(0, 0, 1) @(1, 1, 15) 'black'), (Part 'bas_d' @(15, 0, 1) @(16, 1, 15) 'black'),
    (Part 'haut_g' @(0, 15, 1) @(1, 16, 15) 'black'), (Part 'haut_d' @(15, 15, 1) @(16, 16, 15) 'black'),
    (Part 'grave' @(2.5, 1.75, 0.25) @(13.5, 10.75, 0.5) 'black' 'cone' $noBack),
    (Part 'aigu' @(5.5, 11.25, 0.25) @(10.5, 14.25, 0.5) 'black' 'cone' $noBack)
)
