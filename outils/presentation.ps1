# Assemble les visuels de la page CurseForge : bannière, logo et cartes de fonctions.
# Utilise les rendus 3D (outils\rendu.ps1) et les captures de l'écran (publication\ecrans).
# Résultat : publication\images\*.png
#
# Lancer :  powershell -ExecutionPolicy Bypass -File outils\presentation.ps1

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'
$root = Split-Path $PSScriptRoot -Parent
$pub = Join-Path $root 'publication'
$rendus = Join-Path $pub 'rendus'
$ecrans = Join-Path $pub 'ecrans'
$out = Join-Path $pub 'images'
New-Item -ItemType Directory -Force $out | Out-Null

function Col([string]$h, [int]$a = 255) { $c = [System.Drawing.ColorTranslator]::FromHtml($h); [System.Drawing.Color]::FromArgb($a, $c.R, $c.G, $c.B) }
$CREAM = '#F4E7C9'; $AMBER = '#E8A04A'; $MUTED = '#BDA98C'; $RED = '#D2493B'; $GREEN = '#4FC3A1'

function Canvas($w, $h) {
    $bmp = New-Object System.Drawing.Bitmap $w, $h
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.SmoothingMode = 'AntiAlias'; $g.TextRenderingHint = 'AntiAliasGridFit'; $g.InterpolationMode = 'NearestNeighbor'; $g.PixelOffsetMode = 'Half'
    $rect = New-Object System.Drawing.Rectangle 0, 0, $w, $h
    $br = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect, (Col '#3A2618'), (Col '#160E09'), 90.0
    $g.FillRectangle($br, $rect)
    # Lueur chaude derrière le sujet
    $path = New-Object System.Drawing.Drawing2D.GraphicsPath
    $path.AddEllipse([int]($w * 0.35), [int](-$h * 0.3), [int]($w * 0.9), [int]($h * 1.4))
    $pg = New-Object System.Drawing.Drawing2D.PathGradientBrush $path
    $pg.CenterColor = Col '#7A4A26' 120; $pg.SurroundColors = @((Col '#7A4A26' 0))
    $g.FillPath($pg, $path)
    # Fines lattes de bois en fond
    for ($y = 0; $y -lt $h; $y += 46) { $g.FillRectangle((New-Object System.Drawing.SolidBrush (Col '#000000' 28)), 0, $y, $w, 2) }
    return @($bmp, $g)
}

function Waves($g, $cx, $cy, $count, $start, $step, $color) {
    for ($i = 0; $i -lt $count; $i++) {
        $r = $start + $i * $step
        $pen = New-Object System.Drawing.Pen (Col $color ([int](150 - $i * 35))), ([float](9 - $i * 2))
        $pen.StartCap = 'Round'; $pen.EndCap = 'Round'
        $g.DrawArc($pen, [float]($cx - $r), [float]($cy - $r), [float]($r * 2), [float]($r * 2), -50, 100)
        $g.DrawArc($pen, [float]($cx - $r), [float]($cy - $r), [float]($r * 2), [float]($r * 2), 130, 100)
    }
}

function Text($g, $s, $font, $size, $color, $x, $y, $w = 0) {
    $f = New-Object System.Drawing.Font $font, $size, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
    $br = New-Object System.Drawing.SolidBrush (Col $color)
    $sh = New-Object System.Drawing.SolidBrush (Col '#000000' 110)
    if ($w -gt 0) {
        $r = New-Object System.Drawing.RectangleF ([float]($x + 2)), ([float]($y + 3)), ([float]$w), 2000
        $g.DrawString($s, $f, $sh, $r)
        $r = New-Object System.Drawing.RectangleF ([float]$x), ([float]$y), ([float]$w), 2000
        $g.DrawString($s, $f, $br, $r)
        return $g.MeasureString($s, $f, [int]$w).Height
    }
    $g.DrawString($s, $f, $sh, [float]($x + 2), [float]($y + 3)); $g.DrawString($s, $f, $br, [float]$x, [float]$y)
    return $g.MeasureString($s, $f).Height
}

function RoundRect($x, $y, $w, $h, $r) {
    $p = New-Object System.Drawing.Drawing2D.GraphicsPath
    $p.AddArc($x, $y, $r * 2, $r * 2, 180, 90); $p.AddArc($x + $w - $r * 2, $y, $r * 2, $r * 2, 270, 90)
    $p.AddArc($x + $w - $r * 2, $y + $h - $r * 2, $r * 2, $r * 2, 0, 90); $p.AddArc($x, $y + $h - $r * 2, $r * 2, $r * 2, 90, 90)
    $p.CloseFigure(); return $p
}

function Shadow($g, $x, $y, $w, $h, $r) {
    for ($i = 12; $i -ge 1; $i--) {
        $p = RoundRect ($x - $i + 6) ($y - $i + 14) ($w + $i * 2) ($h + $i * 2) ($r + $i)
        $g.FillPath((New-Object System.Drawing.SolidBrush (Col '#000000' 9)), $p)
    }
}

# L'écran de la radio découpé dans une capture (sans le ciel autour), avec coins arrondis et ombre
function Screen($g, $file, $x, $y, $w) {
    $img = [System.Drawing.Image]::FromFile((Join-Path $ecrans "$file.png"))
    $src = New-Object System.Drawing.Rectangle 286, 129, 1028, 687
    $h = [int]($w * 687 / 1028)
    Shadow $g $x $y $w $h 18
    $clip = RoundRect $x $y $w $h 18
    $old = $g.Clip; $g.SetClip($clip)
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.DrawImage($img, (New-Object System.Drawing.Rectangle $x, $y, $w, $h), $src, [System.Drawing.GraphicsUnit]::Pixel)
    $g.InterpolationMode = 'NearestNeighbor'
    $g.Clip = $old
    $g.DrawPath((New-Object System.Drawing.Pen (Col '#000000' 160), 3), $clip)
    $img.Dispose()
    return $h
}

function Block($g, $name, $x, $y, $size) {
    $img = [System.Drawing.Image]::FromFile((Join-Path $rendus "$name.png"))
    # Ombre au sol
    $g.FillEllipse((New-Object System.Drawing.SolidBrush (Col '#000000' 70)), [float]($x + $size * 0.16), [float]($y + $size * 0.74), [float]($size * 0.68), [float]($size * 0.12))
    $g.InterpolationMode = 'HighQualityBicubic'
    $g.DrawImage($img, $x, $y, $size, $size)
    $g.InterpolationMode = 'NearestNeighbor'
    $img.Dispose()
}

function Chip($g, $s, $x, $y, $color) {
    $f = New-Object System.Drawing.Font 'Segoe UI Semibold', 22, ([System.Drawing.FontStyle]::Regular), ([System.Drawing.GraphicsUnit]::Pixel)
    $sz = $g.MeasureString($s, $f)
    $p = RoundRect $x $y ([int]$sz.Width + 28) 42 21
    $g.FillPath((New-Object System.Drawing.SolidBrush (Col $color 50)), $p)
    $g.DrawPath((New-Object System.Drawing.Pen (Col $color 200), 2), $p)
    $g.DrawString($s, $f, (New-Object System.Drawing.SolidBrush (Col $CREAM)), [float]($x + 14), [float]($y + 6))
    return [int]$sz.Width + 40
}

function Save($c, $name) { $c[1].Dispose(); $c[0].Save((Join-Path $out "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png); $c[0].Dispose(); Write-Host "$name.png" }

# ---------- Bannière ----------
$c = Canvas 1600 600; $g = $c[1]
Waves $g 1150 330 3 230 60 $AMBER
Text $g 'MINECRAFT MOD  ·  NEOFORGE 1.21.1' 'Segoe UI Semibold' 24 $AMBER 90 120 | Out-Null
Text $g 'RADIO' 'Segoe UI Black' 170 $CREAM 76 140 | Out-Null
Text $g 'Real music in your Minecraft world.' 'Segoe UI Semibold' 38 $CREAM 92 360 | Out-Null
$x = 92; foreach ($s in 'Live radios', 'Any song', 'Your discs', 'Speakers') { $x += Chip $g $s $x 430 $AMBER }
Block $g 'speaker_big' 1120 40 380
Block $g 'boombox' 900 160 420
Block $g 'radio' 1230 230 330
Block $g 'speaker' 790 260 260
Save $c 'banniere'

# ---------- Logo ----------
$c = Canvas 400 400; $g = $c[1]
Waves $g 200 215 3 120 34 $AMBER
Block $g 'radio' 30 25 340
Save $c 'logo'

# ---------- Cartes ----------
function Card($name, $title, $lines, $screen, $block, $accent) {
    $c = Canvas 1600 800; $g = $c[1]
    $y = 110
    $g.FillRectangle((New-Object System.Drawing.SolidBrush (Col $accent)), 90, $y, 70, 8)
    $y += 34
    $y += Text $g $title 'Segoe UI Black' 58 $CREAM 86 $y 560
    $y += 20
    foreach ($l in $lines) {
        $g.FillEllipse((New-Object System.Drawing.SolidBrush (Col $accent)), 92, $y + 13, 12, 12)
        $y += (Text $g $l 'Segoe UI Semibold' 27 $MUTED 122 $y 520) + 16
    }
    if ($screen) { Screen $g $screen 700 110 820 | Out-Null }
    # Le bloc sous le texte, à gauche (sans cacher l'écran)
    if ($block) {
        $size = [Math]::Min(300, 790 - $y)
        if ($size -ge 160) { Block $g $block 150 ($y - 10) $size }
    }
    $script:textEnd = $y
    return $c
}

$c = Card 'recherche' 'Play any song' @(
    'Type a title, or paste a Spotify or Deezer link: tracks, albums, playlists.',
    'Covers, artists and durations, right in the radio.',
    'Play it now or add it to a shared queue.') 'search' 'radio' $AMBER
Save $c 'carte_recherche'

$c = Card 'ensemble' 'Everyone hears the same moment' @(
    'Songs start for everyone at exactly 0:00.',
    'Shared queue: see who added what.',
    'Click the progress bar to jump anywhere.',
    'Repeat, shuffle, volume and range knobs.') 'now' 'radio' $RED
Save $c 'carte_ensemble'

$c = Card 'radios' 'Live radios from all over the world' @(
    'Browse by country and genre, keep your favorites.',
    'The song playing on air shows up live.') 'radios' 'boombox' $GREEN
Save $c 'carte_radios'

$c = Card 'disques' 'Your discs, ready to replay' @(
    'Right-click a radio with a disc to store it inside.',
    'Replay it anytime, or add it to the queue.',
    "Minecraft's own music too: Overworld, Nether, End..." ) 'minecraft' 'radio' $AMBER
Save $c 'carte_disques'

$c = Card 'portable' 'Take the music with you' @(
    'A portable radio with every feature of the big ones.',
    'Headphones: only you hear it.',
    'Speaker: everyone around you does.') 'portable' $null $GREEN
$g = $c[1]
# L'objet dans une case d'inventaire, comme dans le jeu
$sx = 150; $sy = $script:textEnd + 20; $ss = 190
$g.FillRectangle((New-Object System.Drawing.SolidBrush (Col '#373737')), $sx, $sy, $ss, $ss)
$g.FillRectangle((New-Object System.Drawing.SolidBrush (Col '#FFFFFF')), $sx + 8, $sy + 8, $ss - 8, $ss - 8)
$g.FillRectangle((New-Object System.Drawing.SolidBrush (Col '#8B8B8B')), $sx + 8, $sy + 8, $ss - 16, $ss - 16)
$img = [System.Drawing.Image]::FromFile((Join-Path $rendus 'portable_radio.png'))
$g.DrawImage($img, $sx + 21, $sy + 21, $ss - 42, $ss - 42); $img.Dispose()
Save $c 'carte_portable'

# Enceintes : pas d'écran, une scène avec les blocs et les ondes
$c = Card 'enceintes' 'Fill the room with speakers' @(
    'Right-click a radio with a speaker, then place it anywhere.',
    'Every speaker plays at once, perfectly in sync.',
    'Walk up to one and it gets louder.',
    'The concert speaker reaches twice as far.') $null $null $AMBER
$g = $c[1]
Waves $g 1130 470 3 210 70 $AMBER
Block $g 'speaker_big' 690 170 400
Block $g 'radio' 960 330 340
Block $g 'speaker' 1230 190 300
Block $g 'speaker' 1260 450 300
Save $c 'carte_enceintes'

# Deux styles : les deux écrans côte à côte
$c = Canvas 1600 800; $g = $c[1]
$g.FillRectangle((New-Object System.Drawing.SolidBrush (Col $AMBER)), 90, 90, 70, 8)
Text $g 'Two radios, two looks' 'Segoe UI Black' 58 $CREAM 86 120 | Out-Null
Text $g 'Each radio opens its own screen: warm wood and a cream dial, or grey plastic and a glowing green display.' 'Segoe UI Semibold' 27 $MUTED 90 200 960 | Out-Null
Screen $g 'search' 90 310 680 | Out-Null
Screen $g 'boombox_search' 830 310 680 | Out-Null
Block $g 'radio' 1090 20 250
Block $g 'boombox' 1310 20 270
Save $c 'carte_styles'
