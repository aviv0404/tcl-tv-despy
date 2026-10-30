param([string]$L)
Add-Type -AssemblyName System.Drawing
function New-Art([int]$w,[int]$h,[string]$path,[bool]$withText){
  $bmp = New-Object System.Drawing.Bitmap $w,$h
  $g = [System.Drawing.Graphics]::FromImage($bmp)
  $g.SmoothingMode='AntiAlias'; $g.TextRenderingHint='AntiAliasGridFit'
  $rect = New-Object System.Drawing.Rectangle 0,0,$w,$h
  $c1=[System.Drawing.Color]::FromArgb(255,28,52,74); $c2=[System.Drawing.Color]::FromArgb(255,12,16,22)
  $br = New-Object System.Drawing.Drawing2D.LinearGradientBrush $rect,$c1,$c2,35
  $g.FillRectangle($br,$rect)
  $gp = New-Object System.Drawing.Drawing2D.GraphicsPath
  $gp.AddEllipse([int](-$w*0.3),[int](-$h*0.6),[int]($w*1.1),[int]($h*1.3))
  $pgb = New-Object System.Drawing.Drawing2D.PathGradientBrush $gp
  $pgb.CenterColor=[System.Drawing.Color]::FromArgb(90,120,170,210)
  $pgb.SurroundColors=@([System.Drawing.Color]::FromArgb(0,0,0,0))
  $g.FillPath($pgb,$gp)
  $s = [Math]::Min($w,$h)
  $pw = [single]([Math]::Max(2,$s*0.045))
  if($withText){ $mh=[single]($h*0.40); $mw=[single]($mh*1.25); $mx=[single]($h*0.30); $my=[single](($h-$mh)/2) }
  else { $mw=[single]($s*0.56); $mh=[single]($s*0.44); $mx=[single](($w-$mw)/2); $my=[single](($h-$mh)/2) }
  $pen = New-Object System.Drawing.Pen ([System.Drawing.Color]::White),$pw
  $pen.LineJoin='Round'; $pen.StartCap='Round'; $pen.EndCap='Round'
  $r=[single]($mh*0.18); $d=[single](2*$r)
  $p = New-Object System.Drawing.Drawing2D.GraphicsPath
  $p.AddArc($mx,$my,$d,$d,180,90); $p.AddArc([single]($mx+$mw-$d),$my,$d,$d,270,90)
  $p.AddArc([single]($mx+$mw-$d),[single]($my+$mh-$d),$d,$d,0,90); $p.AddArc($mx,[single]($my+$mh-$d),$d,$d,90,90); $p.CloseFigure()
  $g.DrawPath($pen,$p)
  $xs=@(0.12,0.40,0.58,0.72,0.88); $ys=@(0.80,0.45,0.66,0.52,0.80)
  $pts = New-Object 'System.Drawing.PointF[]' 5
  for($i=0;$i -lt 5;$i++){ $pts[$i] = New-Object System.Drawing.PointF ([single]($mx+$mw*$xs[$i])),([single]($my+$mh*$ys[$i])) }
  $g.DrawLines($pen,$pts)
  $cr=[single]($mh*0.11)
  $g.DrawEllipse($pen,[single]($mx+$mw*0.70-$cr),[single]($my+$mh*0.28-$cr),[single](2*$cr),[single](2*$cr))
  if($withText){
    $pfc = New-Object System.Drawing.Text.PrivateFontCollection
    $pfc.AddFontFile("$L\app\src\main\assets\fonts\Manrope.ttf")
    $font = New-Object System.Drawing.Font $pfc.Families[0],([single]($h*0.25)),([System.Drawing.FontStyle]::Bold),([System.Drawing.GraphicsUnit]::Pixel)
    $tb = New-Object System.Drawing.SolidBrush ([System.Drawing.Color]::White)
    $sf = New-Object System.Drawing.StringFormat
    $sf.LineAlignment='Center'
    $tx=[single]($mx+$mw+$h*0.12)
    $tr = New-Object System.Drawing.RectangleF $tx,([single]0),([single]$w),([single]$h)
    $g.DrawString('Frame',$font,$tb,$tr,$sf)
  }
  $g.Dispose(); $bmp.Save($path,[System.Drawing.Imaging.ImageFormat]::Png); $bmp.Dispose()
}
New-Art 320 180 "$L\app\src\main\res\drawable-xhdpi\banner.png" $true
New-Art 96 96 "$L\app\src\main\res\mipmap-xhdpi\ic_launcher.png" $false
