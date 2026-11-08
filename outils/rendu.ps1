# Dessine les blocs en 3D (vue de trois quarts, fond transparent) à partir de leur modèle et de leur texture,
# pour la page du mod. Résultat : publication/rendus/<bloc>.png
#
# Lancer :  powershell -ExecutionPolicy Bypass -File outils\rendu.ps1

Add-Type -AssemblyName System.Drawing
$ErrorActionPreference = 'Stop'
Add-Type -ReferencedAssemblies System.Drawing -TypeDefinition @"
using System;
using System.Drawing;
using System.Drawing.Imaging;
public class ZRender {
    int W, H; float[] z; int[] px; int[] tex; int tw, th;
    public ZRender(int w, int h, Bitmap t) {
        W = w; H = h; z = new float[w * h]; px = new int[w * h];
        for (int i = 0; i < z.Length; i++) z[i] = float.MaxValue;
        tw = t.Width; th = t.Height; tex = new int[tw * th];
        for (int y = 0; y < th; y++) for (int x = 0; x < tw; x++) tex[y * tw + x] = t.GetPixel(x, y).ToArgb();
    }
    // Face : coins écran (haut-gauche, haut-droite, bas-gauche) + profondeur de chaque coin, zone de texture, ombre
    public void Face(float ax, float ay, float az, float bx, float by, float bz, float cx, float cy, float cz,
                     float u1, float v1, float u2, float v2, float shade) {
        float ex = bx - ax, ey = by - ay, fx = cx - ax, fy = cy - ay;
        float det = ex * fy - ey * fx;
        if (Math.Abs(det) < 1e-6) return;
        float dx = cx + ex, dy = cy + ey;
        int minX = (int)Math.Floor(Math.Min(Math.Min(ax, bx), Math.Min(cx, dx))), maxX = (int)Math.Ceiling(Math.Max(Math.Max(ax, bx), Math.Max(cx, dx)));
        int minY = (int)Math.Floor(Math.Min(Math.Min(ay, by), Math.Min(cy, dy))), maxY = (int)Math.Ceiling(Math.Max(Math.Max(ay, by), Math.Max(cy, dy)));
        minX = Math.Max(0, minX); minY = Math.Max(0, minY); maxX = Math.Min(W - 1, maxX); maxY = Math.Min(H - 1, maxY);
        for (int y = minY; y <= maxY; y++) for (int x = minX; x <= maxX; x++) {
            float qx = x + 0.5f - ax, qy = y + 0.5f - ay;
            float s = (qx * fy - qy * fx) / det, t = (ex * qy - ey * qx) / det;
            if (s < 0 || s >= 1 || t < 0 || t >= 1) continue;
            float depth = az + (bz - az) * s + (cz - az) * t;
            int i = y * W + x;
            if (depth >= z[i]) continue;
            int tu = (int)Math.Floor(u1 + (u2 - u1) * s), tv = (int)Math.Floor(v1 + (v2 - v1) * t);
            tu = Math.Max(0, Math.Min(tw - 1, tu)); tv = Math.Max(0, Math.Min(th - 1, tv));
            int c = tex[tv * tw + tu];
            if (((c >> 24) & 255) < 128) continue;
            int r = (int)(((c >> 16) & 255) * shade), g = (int)(((c >> 8) & 255) * shade), b = (int)((c & 255) * shade);
            px[i] = (255 << 24) | (r << 16) | (g << 8) | b; z[i] = depth;
        }
    }
    public Bitmap Image() {
        Bitmap bmp = new Bitmap(W, H, PixelFormat.Format32bppArgb);
        for (int y = 0; y < H; y++) for (int x = 0; x < W; x++) bmp.SetPixel(x, y, Color.FromArgb(px[y * W + x]));
        return bmp;
    }
}
"@
$root = Split-Path $PSScriptRoot -Parent
$assets = Join-Path $root 'src\main\resources\assets\radio'
$out = Join-Path $root 'publication\rendus'
New-Item -ItemType Directory -Force $out | Out-Null

function V($x, $y, $z) { ,@([double]$x, [double]$y, [double]$z) }
function Dot($a, $b) { $a[0] * $b[0] + $a[1] * $b[1] + $a[2] * $b[2] }
function Norm($a) { $l = [Math]::Sqrt((Dot $a $a)); V ($a[0] / $l) ($a[1] / $l) ($a[2] / $l) }
function Cross($a, $b) { V ($a[1] * $b[2] - $a[2] * $b[1]) ($a[2] * $b[0] - $a[0] * $b[2]) ($a[0] * $b[1] - $a[1] * $b[0]) }

# Vrai si la face $a doit être dessinée avant la face $b
function Before($a, $b) {
    $same = [Math]::Abs($a.n[0] - $b.n[0]) + [Math]::Abs($a.n[1] - $b.n[1]) + [Math]::Abs($a.n[2] - $b.n[2]) -lt 0.001
    if ($same) { return $a.plane -lt $b.plane - 0.0001 }
    return $a.depth -gt $b.depth + 0.0001
}

function Render($name, $size, $view) {
    $model = Get-Content (Join-Path $assets "models\block\$name.json") -Raw | ConvertFrom-Json
    $tex = [System.Drawing.Image]::FromFile((Join-Path $assets "textures\block\$name.png"))
    $k = $tex.Width / 16.0
    # Caméra : on regarde le bloc depuis la direction $view (vers la caméra)
    $toCam = Norm $view
    $fwd = V (-$toCam[0]) (-$toCam[1]) (-$toCam[2])
    $right = Norm (Cross $fwd (V 0 1 0))
    $up = Cross $right $fwd
    $faces = @()
    foreach ($el in $model.elements) {
        $f = $el.from; $t = $el.to
        $x1 = $f[0]; $y1 = $f[1]; $z1 = $f[2]; $x2 = $t[0]; $y2 = $t[1]; $z2 = $t[2]
        # Coins (haut-gauche, haut-droite, bas-gauche) de chaque face, dans le sens des UV de Minecraft
        $def = @{
            north = @((V $x2 $y2 $z1), (V $x1 $y2 $z1), (V $x2 $y1 $z1), (V 0 0 -1), 0.8)
            south = @((V $x1 $y2 $z2), (V $x2 $y2 $z2), (V $x1 $y1 $z2), (V 0 0 1), 0.8)
            east  = @((V $x2 $y2 $z2), (V $x2 $y2 $z1), (V $x2 $y1 $z2), (V 1 0 0), 0.6)
            west  = @((V $x1 $y2 $z1), (V $x1 $y2 $z2), (V $x1 $y1 $z1), (V -1 0 0), 0.6)
            up    = @((V $x1 $y2 $z1), (V $x2 $y2 $z1), (V $x1 $y2 $z2), (V 0 1 0), 1.0)
            down  = @((V $x1 $y1 $z2), (V $x2 $y1 $z2), (V $x1 $y1 $z1), (V 0 -1 0), 0.5)
        }
        $rot = $el.rotation
        foreach ($p in $el.faces.PSObject.Properties) {
            $d = $def[$p.Name]
            $pts = @($d[0], $d[1], $d[2]); $n = $d[3]
            if ($rot) {
                $a = [double]$rot.angle * [Math]::PI / 180; $o = $rot.origin
                $ca = [Math]::Cos($a); $sa = [Math]::Sin($a)
                $pts = $pts | ForEach-Object { $dx = $_[0] - $o[0]; $dy = $_[1] - $o[1]; , (V ($o[0] + $dx * $ca - $dy * $sa) ($o[1] + $dx * $sa + $dy * $ca) $_[2]) }
                $n = V ($n[0] * $ca - $n[1] * $sa) ($n[0] * $sa + $n[1] * $ca) $n[2]
            }
            if ((Dot $n $toCam) -le 0.001) { continue }   # face tournée de l'autre côté
            # Centre de la face (4e coin = TR + BL - TL) pour trier de loin à près
            $c = V (($pts[1][0] + $pts[2][0]) / 2) (($pts[1][1] + $pts[2][1]) / 2) (($pts[1][2] + $pts[2][2]) / 2)
            $faces += [pscustomobject]@{ pts = $pts; uv = $p.Value.uv; shade = $d[4]; depth = (Dot $c $fwd); n = $n; plane = (Dot $pts[0] $n) }
        }
    }
    $scale = $size / 30.0
    $center = V 8 8 8
    $cs = @((Dot $center $right), (Dot $center $up), (Dot $center $fwd))
    $zr = New-Object ZRender $size, $size, $tex
    foreach ($fc in $faces) {
        $sp = foreach ($q in $fc.pts) { , @(($size / 2 + ((Dot $q $right) - $cs[0]) * $scale), ($size / 2 - ((Dot $q $up) - $cs[1]) * $scale), ((Dot $q $fwd) - $cs[2])) }
        $u1 = $fc.uv[0] * $k; $v1 = $fc.uv[1] * $k; $u2 = $fc.uv[2] * $k; $v2 = $fc.uv[3] * $k
        $zr.Face($sp[0][0], $sp[0][1], $sp[0][2], $sp[1][0], $sp[1][1], $sp[1][2], $sp[2][0], $sp[2][1], $sp[2][2], $u1, $v1, $u2, $v2, $fc.shade)
    }
    $bmp = $zr.Image()
    $g = [System.Drawing.Graphics]::FromImage($bmp)
    $g.Dispose(); $tex.Dispose()
    $bmp.Save((Join-Path $out "$name.png"), [System.Drawing.Imaging.ImageFormat]::Png)
    $bmp.Dispose()
    Write-Host "$name : $($faces.Count) faces"
}

# Vue de trois quarts : de face, un peu à droite et au-dessus (on voit l'antenne et la poignée)
$view = V 0.75 0.62 -1
foreach ($b in 'radio', 'boombox', 'speaker', 'speaker_big') { Render $b 768 $view }

# Radio portable (objet) : agrandie sans flou
$item = [System.Drawing.Image]::FromFile((Join-Path $assets 'textures\item\portable_radio.png'))
$big = New-Object System.Drawing.Bitmap 512, 512
$g = [System.Drawing.Graphics]::FromImage($big); $g.InterpolationMode = 'NearestNeighbor'; $g.PixelOffsetMode = 'Half'
$g.DrawImage($item, 0, 0, 512, 512); $g.Dispose(); $item.Dispose()
$big.Save((Join-Path $out 'portable_radio.png'), [System.Drawing.Imaging.ImageFormat]::Png); $big.Dispose()
Write-Host 'portable_radio : objet'
