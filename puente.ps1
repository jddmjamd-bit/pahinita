[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Ultimo = ""
Clear-Host
Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host " PUENTE ARGON 4 ACTIVO (Escuchando tu portapapeles)" -ForegroundColor Cyan
Write-Host " Deja esta ventana abierta. Ahora ve al chat y dale clic" -ForegroundColor Yellow
Write-Host " al boton Copiar del Paso 2 (no lo pegues aqui)." -ForegroundColor Yellow
Write-Host "=========================================================" -ForegroundColor Cyan
function Resolver-Ruta($Rel) {
  $Cwd = (Get-Location).Path
  $Norm = $Rel.Replace("\", "/").ToLower()
  $Nombre = [System.IO.Path]::GetFileName($Rel).ToLower()
  try {
    $GitFiles = $(git ls-files 2>$null)
    foreach ($F in $GitFiles) { if ($F.Trim().ToLower() -eq $Norm) { return Join-Path $Cwd $F.Trim() } }
    $Coinc = $($GitFiles | Where-Object { [System.IO.Path]::GetFileName($_.Trim()).ToLower() -eq $Nombre })
    if ($Coinc.Count -eq 1) { return Join-Path $Cwd $Coinc[0].Trim() }
  } catch {}
  return Join-Path $Cwd $Rel
}
$Ini = "<<<ARCHIVO:"
$Mid = ">>>"
$Fin = "<<<FIN-ARCHIVO>>>"
$CR = [char]13
$LF = [char]10
$NL = [Environment]::NewLine
$Tick = [string][char]96
while ($true) {
  Start-Sleep -Milliseconds 400
  try {
    $Clip = Get-Clipboard -Raw -ErrorAction SilentlyContinue
    if ([string]::IsNullOrWhiteSpace($Clip) -or $Clip -eq $Ultimo) { continue }
    $Texto = $Clip.TrimStart()
    if ($Texto.StartsWith("### LEER-ARCHIVOS ###")) {
      $Ultimo = $Clip
      Write-Host ""
      Write-Host "[ARGON 4] Leyendo archivos solicitados..." -ForegroundColor Yellow
      $Lineas = $Texto -split "\r?\n"
      $Salida = "### ARCHIVOS-COMPLETOS ###" + $NL
      $Leidos = 0
      for ($i = 1; $i -lt $Lineas.Count; $i++) {
        $Item = $Lineas[$i].Trim()
        if ($Item -eq "" -or $Item.StartsWith("#") -or $Item.StartsWith($Tick)) { continue }
        $RutaCompleta = Resolver-Ruta $Item
        if (Test-Path $RutaCompleta) {
          $Contenido = [System.IO.File]::ReadAllText($RutaCompleta, [System.Text.Encoding]::UTF8)
          $Salida += $NL + $Ini + " " + $Item + $Mid + $NL + $Contenido + $NL + $Fin + $NL
          Write-Host ("  Leido completo: " + $Item) -ForegroundColor Green
          $Leidos++
        } else { Write-Host ("  No encontrado: " + $Item) -ForegroundColor Red }
      }
      if ($Leidos -gt 0) {
        $Ultimo = $Salida
        Set-Clipboard -Value $Salida
        [console]::beep(1000, 150)
        Write-Host ("Listo! " + $Leidos + " archivo(s) en tu portapapeles. Ve al chat y presiona Ctrl+V.") -ForegroundColor Cyan
      }
      continue
    }
    if ($Texto.StartsWith("### APLICAR-CAMBIOS ###")) {
      $Ultimo = $Clip
      Write-Host ""
      Write-Host "[ARGON 4] Aplicando cambios en tus archivos..." -ForegroundColor Yellow
      $Pos = 0; $Cont = 0
      while ($true) {
        $I1 = $Texto.IndexOf($Ini, $Pos); if ($I1 -lt 0) { break }
        $I2 = $Texto.IndexOf($Mid, $I1 + $Ini.Length); if ($I2 -lt 0) { break }
        $RutaRel = $Texto.Substring($I1 + $Ini.Length, $I2 - ($I1 + $Ini.Length)).Trim()
        $Start = $I2 + $Mid.Length
        if ($Start -lt $Texto.Length -and $Texto[$Start] -eq $CR) { $Start++ }
        if ($Start -lt $Texto.Length -and $Texto[$Start] -eq $LF) { $Start++ }
        $NextArch = $Texto.IndexOf($Ini, $Start)
        $EndMark = $Texto.IndexOf($Fin, $Start)
        if ($EndMark -ge 0 -and ($NextArch -lt 0 -or $EndMark -lt $NextArch)) { $End = $EndMark; $Pos = $EndMark + $Fin.Length }
        elseif ($NextArch -ge 0) { $End = $NextArch; $Pos = $NextArch }
        else { $End = $Texto.Length; $Pos = $Texto.Length }
        $Body = $Texto.Substring($Start, $End - $Start).TrimEnd($CR, $LF) + $NL
        $RutaFinal = Resolver-Ruta $RutaRel
        $Existe = Test-Path $RutaFinal
        $Dir = Split-Path $RutaFinal -Parent
        if (!(Test-Path $Dir)) { New-Item -ItemType Directory -Path $Dir -Force | Out-Null }
        [System.IO.File]::WriteAllText($RutaFinal, $Body, [System.Text.UTF8Encoding]::new($false))
        $Estado = if ($Existe) { "[MODIFICADO]" } else { "[NUEVO]" }
        Write-Host ("  OK " + $Estado + ": " + $RutaRel) -ForegroundColor Green
        $Cont++
      }
      if ($Cont -gt 0) {
        [console]::beep(1000, 150)
        Write-Host ("Listo! " + $Cont + " archivo(s) actualizados.") -ForegroundColor Cyan
        git status -s
      }
    }
  } catch { Write-Host ("Error: " + $_.Exception.Message) -ForegroundColor Red }
}