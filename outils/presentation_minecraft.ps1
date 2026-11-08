# Assemble les visuels de la page CurseForge (bannière, logo, cartes) façon menus de Minecraft :
# police pixel du jeu, fond en terre assombrie, bulles d'info violettes, herbe, notes de musique.
# Utilise les rendus 3D (outils\rendu.ps1), les captures de l'écran (publication\ecrans)
# et quelques textures du jeu (publication\vanilla, sorties du jar client).
# Résultat : publication\images\*.png
#
# Lancer :  powershell -ExecutionPolicy Bypass -File outils\presentation.ps1

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$pub = Join-Path $root 'publication'
$rendus = Join-Path $pub 'rendus'
$ecrans = Join-Path $pub 'ecrans'
$van = Join-Path $pub 'vanilla'
$out = Join-Path $pub 'images'
New-Item -ItemType Directory -Force $out | Out-Null

function Col([string]$h, [int]$a = 255) { $c = [System.Drawing.ColorTranslator]::FromHtml($h); [System.Drawing.Color]::FromArgb($a, $c.R, $c.G, $c.B) }
function Brush([string]$h, [int]$a = 255) { New-Object System.Drawing.SolidBrush (Col $h $a) }
# Couleurs des textes du jeu (§e jaune, §6 or, §a vert, §b cyan, §c rouge, §7 gris)
$WHITE = '#FFFFFF'; $YELLOW = '#FFFF55'; $GOLD = '#FFAA00'; $GREEN = '#55FF55'; $AQUA = '#55FFFF'; $RED = '#FF5555'; $GRAY = '#C8C8C8'; $DGRAY = '#8A8A8A'

# ---------- Police du jeu (font/ascii.png : 16 x 16 caractères de 8 x 8) ----------
$fontImg = New-Object System.Drawing.Bitmap (Join-Path $van 'ascii.png')
$glyphs = @{}
for ($code = 32; $code -lt 127; $code++) {
    $gx = ($code % 16) * 8; $gy = [Math]::Floor($code / 16) * 8
    $pts = New-Object System.Collections.Generic.List[int[]]
    $w = 0
    for ($yy = 0; $yy -lt 8; $yy++) { for ($xx = 0; $xx -lt 8; $xx++) {
        if ($fontImg.GetPixel($gx + $xx, $gy + $yy).A -gt 128) { $pts.Add(@($xx, $yy)); if ($xx + 1 -gt $w) { $w = $xx + 1 } }
    } }
    if ($code -eq 32) { $w = 3 }
    $glyphs[[char]$code] = @{ pts = $pts; adv = $w + 1 }
}
$fontImg.Dispose()

function PWidth([string]$s, [int]$k) { $w = 0; foreach ($ch in $s.ToCharArray()) { $w += $glyphs[$ch].adv }; return ($w - 1) * $k }

# Écrit comme le jeu : chaque pixel de la police devient un carré de $k, avec l'ombre décalée d'un pixel
function PText($g, [string]$s, [int]$k, [string]$color, [float]$x, [float]$y) {
    $c = Col $color
    $shadow = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::FromArgb(255, [int]($c.R / 4), [int]($c.G / 4), [int]($c.B / 4)))
    $main = New-Object System.Drawing.SolidBrush $c
    foreach ($pass in 1, 0) {
        $cx = $x
        foreach ($ch in $s.ToCharArray()) {
            $gl = $glyphs[$ch]
            foreach ($p in $gl.pts) {
                $g.FillRectangle($(if ($pass) { $shadow } else { $main }), [float]($cx + ($p[0] + $pass) * $k), [float]($y + ($p[1] + $pass) * $k), [float]$k, [float]$k)
            }
            $cx += $gl.adv * $k
        }
    }
}

# Forme des lettres (un carré par pixel de la police)
function PPath([string]$s, [int]$k, [float]$x, [float]$y) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath ([System.Drawing.Drawing2D.FillMode]::Winding)
    $cx = $x
    foreach ($ch in $s.ToCharArray()) {
        $gl = $glyphs[$ch]
        foreach ($q in $gl.pts) { $p.AddRectangle((New-Object System.Drawing.RectangleF ([float]($cx + $q[0] * $k)), ([float]($y + $q[1] * $k)), ([float]$k), ([float]$k))) }
        $cx += $gl.adv * $k
    }
    return $p
}

# Gros titre façon logo du jeu : lettres en planches de chêne, relief sombre dessous, contour noir
function PTitle($g, [string]$s, [int]$k, [float]$x, [float]$y) {
    $depth = [int]($k * 0.9); $o = [int]($k / 4)
    $m = New-Object System.Drawing.Drawing2D.Matrix
    $black = Brush '#000000'
    for ($d = $depth; $d -ge 1; $d -= 2) {
        foreach ($dx in -$o, $o) { foreach ($dy in -$o, $o) { $p = PPath $s $k ($x + $dx) ($y + $d + $dy); $g.FillPath($black, $p) } }
    }
    foreach ($dx in -$o, 0, $o) { foreach ($dy in -$o, 0, $o) { $p = PPath $s $k ($x + $dx) ($y + $dy); $g.FillPath($black, $p) } }
    $dark = Brush '#4A3216'
    for ($d = $depth; $d -ge 1; $d -= 2) { $g.FillPath($dark, (PPath $s $k $x ($y + $d))) }
    $img = New-Object System.Drawing.Bitmap (Join-Path $van 'oak_planks.png')
    $big = New-Object System.Drawing.Bitmap ($k * 4), ($k * 4)
    $bg = [System.Drawing.Graphics]::FromImage($big); $bg.InterpolationMode = 'NearestNeighbor'; $bg.PixelOffsetMode = 'Half'
    $bg.DrawImage($img, 0, 0, $k * 4, $k * 4); $bg.Dispose(); $img.Dispose()
    $tb = New-Object System.Drawing.TextureBrush $big
    $tb.TranslateTransform($x, $y)
    $g.FillPath($tb, (PPath $s $k $x $y))
    # Reflet clair sur le haut de chaque lettre
    $g.FillPath((Brush '#FFFFFF' 40), (PPath $s $k $x $y))
    $big.Dispose()
}

# Texte coupé en lignes ; renvoie la hauteur utilisée
function PWrap($g, [string]$s, [int]$k, [string]$color, [float]$x, [float]$y, [int]$maxW, [int]$indent = 0) {
    $lines = @(); $cur = ''
    foreach ($word in $s.Split(' ')) {
        $try = if ($cur) { "$cur $word" } else { $word }
        $lim = if ($lines.Count) { $maxW - $indent } else { $maxW }
        if ($cur -and (PWidth $try $k) -gt $lim) { $lines += $cur; $cur = $word } else { $cur = $try }
    }
    if ($cur) { $lines += $cur }
    $i = 0
    foreach ($l in $lines) { PText $g $l $k $color ($x + $(if ($i) { $indent } else { 0 })) ($y + $i * 11 * $k); $i++ }
    return $lines.Count * 11 * $k
}

# ---------- Fonds et décors ----------
function Tile($g, [string]$file, $x, $y, $w, $h, $size, [float]$bright) {
    $img = New-Object System.Drawing.Bitmap (Join-Path $van $file)
    $attr = New-Object System.Drawing.Imaging.ImageAttributes
    $m = New-Object System.Drawing.Imaging.ColorMatrix
    $m.Matrix00 = $bright; $m.Matrix11 = $bright; $m.Matrix22 = $bright
    $attr.SetColorMatrix($m)
    for ($ty = $y; $ty -lt $y + $h; $ty += $size) { for ($tx = $x; $tx -lt $x + $w; $tx += $size) {
        $g.DrawImage($img, (New-Object System.Drawing.Rectangle ([int]$tx), ([int]$ty), $size, $size), 0, 0, 16, 16, [System.Drawing.GraphicsUnit]::Pixel, $attr)
    } }
    $img.Dispose()
}

# Fond des menus du jeu : la terre, assombrie
function Canvas($w, $h) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.InterpolationMode = 'NearestNeighbor'; $g.PixelOffsetMode = 'Half'; $g.SmoothingMode = 'None'
    Tile $g 'dirt.png' 0 0 $w $h 96 0.30
    return @($bmp, $g)
}

# Rangée d'herbe en bas, comme le sol du monde
function Grass($g, $w, $h, $size) { Tile $g 'grass_block_side.png' 0 ($h - $size) $w $size $size 0.9 }

# Note de musique (particule du bloc musical), teintée
function Note($g, $x, $y, $k, [string]$color) {
    $img = New-Object System.Drawing.Bitmap (Join-Path $van 'note.png')
    $c = Col $color
    $attr = New-Object System.Drawing.Imaging.ImageAttributes
    $m = New-Object System.Drawing.Imaging.ColorMatrix
    $m.Matrix00 = $c.R / 255; $m.Matrix11 = $c.G / 255; $m.Matrix22 = $c.B / 255
    $attr.SetColorMatrix($m)
    $g.DrawImage($img, (New-Object System.Drawing.Rectangle ([int]$x), ([int]$y), ($img.Width * $k), ($img.Height * $k)), 0, 0, $img.Width, $img.Height, [System.Drawing.GraphicsUnit]::Pixel, $attr)
    $img.Dispose()
}

# Bulle d'info du jeu : fond violet très sombre et liseré violet en dégradé, coins coupés
function TipBox($g, $x, $y, $w, $h, $k) {
    $bg = Brush '#100010' 240
    $g.FillRectangle($bg, $x + $k, $y, $w - 2 * $k, $h)
    $g.FillRectangle($bg, $x, $y + $k, $w, $h - 2 * $k)
    $rect = New-Object System.Drawing.Rectangle ([int]$x), ([int]($y + $k)), ([int]$w), ([int]($h - 2 * $k))
    $grad = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, (Col '#5000FF' 140), (Col '#28007F' 140), 90.0
    $g.FillRectangle($grad, $x + $k, $y + $k, $w - 2 * $k, $k)
    $g.FillRectangle($grad, $x + $k, $y + $h - 2 * $k, $w - 2 * $k, $k)
    $g.FillRectangle($grad, $x + $k, $y + $k, $k, $h - 2 * $k)
    $g.FillRectangle($grad, $x + $w - 2 * $k, $y + $k, $k, $h - 2 * $k)
}

# L'écran de la radio découpé dans une capture, avec bord noir et ombre franche (pas de flou)
function Screen($g, $file, $x, $y, $w) {
    $img = [System.Drawing.Image]::FromFile((Join-Path $ecrans "$file.png"))
    $src = New-Object System.Drawing.Rectangle 286, 129, 1028, 687
    $h = [int]($w * 687 / 1028)
    $g.FillRectangle((Brush '#000000' 110), $x + 14, $y + 14, $w, $h)
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.DrawImage($img, (New-Object System.Drawing.Rectangle $x, $y, $w, $h), $src, [System.Drawing.GraphicsUnit]::Pixel)
    $g.InterpolationMode = 'NearestNeighbor'
    $g.DrawRectangle((New-Object System.Drawing.Pen (Col '#000000'), 4), $x - 2, $y - 2, $w + 3, $h + 3)
    $img.Dispose()
    return $h
}

function Block($g, $name, $x, $y, $size) {
    $img = [System.Drawing.Image]::FromFile((Join-Path $rendus "$name.png"))
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.DrawImage($img, $x, $y, $size, $size)
    $g.InterpolationMode = 'NearestNeighbor'
    $img.Dispose()
}

# Case d'inventaire du jeu
function Slot($g, $x, $y, $s, $k) {
    $g.FillRectangle((Brush '#373737'), $x, $y, $s, $s)
    $g.FillRectangle((Brush '#FFFFFF'), $x + $k, $y + $k, $s - $k, $s - $k)
    $g.FillRectangle((Brush '#8B8B8B'), $x + $k, $y + $k, $s - 2 * $k, $s - 2 * $k)
}

function Save($c, $name) { $c[1].Dispose(); $c[0].Save((Join-Path $out "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png); $c[0].Dispose(); Write-Host "$name.png" }

# ---------- Bannière ----------
$c = Canvas 1600 600; $g = $c[1]
Grass $g 1600 600 96
PTitle $g 'RADIO' 20 84 50
# Phrase jaune penchée, comme celles du menu principal (au bout du titre)
$g.TranslateTransform(650, 215); $g.RotateTransform(-16)
$sp = 'Real music!'; PText $g $sp 5 $YELLOW (-(PWidth $sp 5) / 2) 0
$g.ResetTransform()
PText $g 'Live radios, any song, your discs' 4 $GRAY 88 300
PText $g 'and speakers, all in sync.' 4 $GRAY 88 348
PText $g 'NeoForge 1.21.1' 3 $DGRAY 88 432
Block $g 'speaker_big' 1180 16 400
Block $g 'boombox' 900 120 420
Block $g 'radio' 1250 200 330
Block $g 'speaker' 790 230 260
Note $g 860 70 6 $GREEN; Note $g 1100 40 5 $AQUA; Note $g 1530 20 6 $YELLOW; Note $g 1010 210 4 $RED
Save $c 'banniere'

# ---------- Logo ----------
$c = Canvas 400 400; $g = $c[1]
Grass $g 400 400 80
Block $g 'radio' 30 42 340
Note $g 40 40 6 $GREEN; Note $g 320 30 5 $YELLOW
Save $c 'logo'

# ---------- Cartes ----------
# Texte dans une bulle d'info à gauche, écran à droite, bloc posé sur l'herbe sous la bulle
function Card($title, $color, $lines, $screen, $block) {
    $c = Canvas 1600 800; $g = $c[1]
    Grass $g 1600 800 80
    $k = 3; $tw = 540
    # On mesure d'abord la hauteur du texte pour dimensionner la bulle
    $tmp = New-Object System.Drawing.Bitmap 10, 10; $tg = [System.Drawing.Graphics]::FromImage($tmp)
    $h = (PWrap $tg $title 5 $color 0 0 $tw) + 22
    foreach ($l in $lines) { $h += (PWrap $tg "- $l" $k $GRAY 0 0 $tw (2 * 6 * $k)) + 14 }
    $tg.Dispose(); $tmp.Dispose()
    TipBox $g 70 80 ($tw + 60) ($h + 48) 6
    $y = 110
    $y += (PWrap $g $title 5 $color 100 $y $tw) + 22
    foreach ($l in $lines) { $y += (PWrap $g "- $l" $k $GRAY 100 $y $tw (2 * 6 * $k)) + 14 }
    if ($screen) { Screen $g $screen 700 90 820 | Out-Null }
    $script:textEnd = 80 + $h + 48
    if ($block) {
        $size = [Math]::Min(300, 720 - $script:textEnd + 60)
        if ($size -ge 160) { Block $g $block 190 (720 - $size * 0.86) $size }
    }
    return $c
}

$c = Card 'Play any song' $GOLD @(
    'Type a title, or paste a Spotify or Deezer link (track, album, playlist).',
    'Covers, artists and durations show up right in the radio.',
    'Play it now or add it to the queue.') 'search' 'radio'
Save $c 'carte_recherche'

$c = Card 'Everyone hears the same thing' $RED @(
    'Songs start at 0:00 for everyone, at the same time.',
    'The queue shows who added what.',
    'Click the bar to jump anywhere in the song.',
    'Repeat, shuffle, volume and range.') 'now' 'radio'
Save $c 'carte_ensemble'

$c = Card 'Live radios' $GREEN @(
    'Thousands of web radios, by country or genre.',
    'Star your favorites.',
    'See the song playing on air.') 'radios' 'boombox'
Save $c 'carte_radios'

$c = Card 'Your discs' $AQUA @(
    'Right-click a radio with a disc to store it inside.',
    'Play it again whenever you want.',
    'Overworld, Nether and End music too.') 'minecraft' 'radio'
Save $c 'carte_disques'

$c = Card 'Portable radio' $GREEN @(
    'Everything the big radios do, in your pocket.',
    'Headphones: only you hear it.',
    'Speaker: players around you hear it too.') 'portable' $null
$g = $c[1]
$ss = 168; Slot $g 220 (720 - $ss - 20) $ss 6
$img = [System.Drawing.Image]::FromFile((Join-Path $rendus 'portable_radio.png'))
$g.DrawImage($img, 220 + 18, 720 - $ss - 20 + 18, $ss - 36, $ss - 36); $img.Dispose()
Save $c 'carte_portable'

# Enceintes : pas d'écran, les blocs posés sur l'herbe avec des notes
$c = Card 'Speakers' $GOLD @(
    'Right-click a radio with a speaker, then place it up to 64 blocks away.',
    'All speakers play together, in sync.',
    'Walk up to one and it gets louder.',
    'The concert speaker goes twice as far.') $null $null
$g = $c[1]
function Ground($g, $name, $x, $size) { Block $g $name $x (722 - $size * 0.86) $size }
Ground $g 'speaker' 690 250
Ground $g 'speaker_big' 860 420
Ground $g 'radio' 1180 300
Ground $g 'speaker' 1370 250
Note $g 760 330 6 $AQUA; Note $g 1010 150 7 $YELLOW; Note $g 1260 300 5 $GREEN; Note $g 1450 340 6 $RED
Save $c 'carte_enceintes'

# Deux styles : les deux écrans côte à côte
$c = Canvas 1600 800; $g = $c[1]
Grass $g 1600 800 80
PText $g 'Two radios, two screens' 6 $GOLD 90 60
PText $g 'Wood and a cream dial, or grey plastic and a green display.' 3 $GRAY 94 140
Screen $g 'search' 90 230 680 | Out-Null
Screen $g 'boombox_search' 830 230 680 | Out-Null
Save $c 'carte_styles'
