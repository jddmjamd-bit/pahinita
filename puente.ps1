[Console]::OutputEncoding = [System.Text.Encoding]::UTF8
$Ultimo = ""
Clear-Host
Write-Host "=========================================================" -ForegroundColor Cyan
Write-Host " PUENTE ARGON 4 v5 ACTIVO (Completo + Edicion Parcial)" -ForegroundColor Cyan
Write-Host " - ### LEER-ARCHIVOS ###   -> Copia archivos completos" -ForegroundColor Gray
Write-Host " - <<<ARCHIVO: ruta>>>     -> Reemplaza archivo completo" -ForegroundColor Gray
Write-Host " - <<<EDITAR: ruta>>>      -> Edita solo el fragmento" -ForegroundColor Gray
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
$IniArch = "<<<ARCHIVO:"
$FinArch = "<<<FIN-ARCHIVO>>>"
$IniEdit = "<<<EDITAR:"
$FinEdit = "<<<FIN-EDITAR>>>"
$MarcaBuscar = "<<<BUSCAR>>>"
$MarcaReemp = "<<<REEMPLAZAR>>>"
$FinCambio = "<<<FIN-CAMBIO>>>"
$Mid = ">>>"
$CR = [char]13
$LF = [char]10
$NL = [Environment]::NewLine
$Tick = [string][char]96
function Limpiar-Bordes($Txt) {
  $Res = $Txt.Replace("`r`n", "`n")
  if ($Res.StartsWith("`n")) { $Res = $Res.Substring(1) }
  if ($Res.EndsWith("`n")) { $Res = $Res.Substring(0, $Res.Length - 1) }
  return $Res
}
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
          $Salida += $NL + $IniArch + " " + $Item + $Mid + $NL + $Contenido + $NL + $FinArch + $NL
          Write-Host ("  Leido completo: " + $Item) -ForegroundColor Green
          $Leidos++
        } else { Write-Host ("  No encontrado: " + $Item) -ForegroundColor Red }
      }
      if ($Leidos -gt 0) {
        $Ultimo = $Salida
        Set-Clipboard -Value $Salida
        [console]::beep(1000, 150)
        Write-Host ("Listo! " + $Leidos + " archivo(s) en tu portapapeles. Ve al chat y pega con Ctrl+V.") -ForegroundColor Cyan
      }
      continue
    }
    if ($Texto.StartsWith("### APLICAR-CAMBIOS ###")) {
      $Ultimo = $Clip
      Write-Host ""
      Write-Host "[ARGON 4] Aplicando cambios en tus archivos..." -ForegroundColor Yellow
      $Pos = 0; $Cont = 0
      while ($true) {
        $I1 = $Texto.IndexOf($IniArch, $Pos); if ($I1 -lt 0) { break }
        $I2 = $Texto.IndexOf($Mid, $I1 + $IniArch.Length); if ($I2 -lt 0) { break }
        $RutaRel = $Texto.Substring($I1 + $IniArch.Length, $I2 - ($I1 + $IniArch.Length)).Trim()
        $Start = $I2 + $Mid.Length
        if ($Start -lt $Texto.Length -and $Texto[$Start] -eq $CR) { $Start++ }
        if ($Start -lt $Texto.Length -and $Texto[$Start] -eq $LF) { $Start++ }
        $NextArch = $Texto.IndexOf($IniArch, $Start)
        $NextEdit = $Texto.IndexOf($IniEdit, $Start)
        $EndMark = $Texto.IndexOf($FinArch, $Start)
        $Limite = $Texto.Length
        if ($NextArch -ge 0 -and $NextArch -lt $Limite) { $Limite = $NextArch }
        if ($NextEdit -ge 0 -and $NextEdit -lt $Limite) { $Limite = $NextEdit }
        if ($EndMark -ge 0 -and $EndMark -lt $Limite) { $End = $EndMark; $Pos = $EndMark + $FinArch.Length }
        else { $End = $Limite; $Pos = $Limite }
        $Body = $Texto.Substring($Start, $End - $Start).TrimEnd($CR, $LF) + $NL
        $RutaFinal = Resolver-Ruta $RutaRel
        $Existe = Test-Path $RutaFinal
        $Dir = Split-Path $RutaFinal -Parent
        if (!(Test-Path $Dir)) { New-Item -ItemType Directory -Path $Dir -Force | Out-Null }
        [System.IO.File]::WriteAllText($RutaFinal, $Body, [System.Text.UTF8Encoding]::new($false))
        $Estado = if ($Existe) { "[MODIFICADO COMPLETO]" } else { "[NUEVO]" }
        Write-Host ("  OK " + $Estado + ": " + $RutaRel) -ForegroundColor Green
        $Cont++
      }
      $PosE = 0
      while ($true) {
        $E1 = $Texto.IndexOf($IniEdit, $PosE); if ($E1 -lt 0) { break }
        $E2 = $Texto.IndexOf($Mid, $E1 + $IniEdit.Length); if ($E2 -lt 0) { break }
        $RutaRel = $Texto.Substring($E1 + $IniEdit.Length, $E2 - ($E1 + $IniEdit.Length)).Trim()
        $StartE = $E2 + $Mid.Length
        $EndE = $Texto.IndexOf($FinEdit, $StartE)
        if ($EndE -lt 0) { $EndE = $Texto.Length; $PosE = $Texto.Length } else { $PosE = $EndE + $FinEdit.Length }
        $BloqueEdit = $Texto.Substring($StartE, $EndE - $StartE)
        $RutaFinal = Resolver-Ruta $RutaRel
        if (!(Test-Path $RutaFinal)) { Write-Host ("  No existe para editar: " + $RutaRel) -ForegroundColor Red; continue }
        $Orig = [System.IO.File]::ReadAllText($RutaFinal, [System.Text.Encoding]::UTF8)
        $UsaCRLF = $Orig.Contains("`r`n")
        $Actual = $Orig.Replace("`r`n", "`n")
        $PosB = 0; $CambiosArch = 0
        while ($true) {
          $B1 = $BloqueEdit.IndexOf($MarcaBuscar, $PosB); if ($B1 -lt 0) { break }
          $R1 = $BloqueEdit.IndexOf($MarcaReemp, $B1 + $MarcaBuscar.Length); if ($R1 -lt 0) { break }
          $NextB = $BloqueEdit.IndexOf($MarcaBuscar, $R1 + $MarcaReemp.Length)
          $FinC = $BloqueEdit.IndexOf($FinCambio, $R1 + $MarcaReemp.Length)
          $LimC = $BloqueEdit.Length
          if ($NextB -ge 0 -and $NextB -lt $LimC) { $LimC = $NextB }
          if ($FinC -ge 0 -and $FinC -lt $LimC) { $FinR = $FinC; $PosB = $FinC + $FinCambio.Length } else { $FinR = $LimC; $PosB = $LimC }
          $StrBuscar = Limpiar-Bordes ($BloqueEdit.Substring($B1 + $MarcaBuscar.Length, $R1 - ($B1 + $MarcaBuscar.Length)))
          $StrReemp = Limpiar-Bordes ($BloqueEdit.Substring($R1 + $MarcaReemp.Length, $FinR - ($R1 + $MarcaReemp.Length)))
          if ($Actual.Contains($StrBuscar)) {
            $IdxMatch = $Actual.IndexOf($StrBuscar)
            $Actual = $Actual.Substring(0, $IdxMatch) + $StrReemp + $Actual.Substring($IdxMatch + $StrBuscar.Length)
            $CambiosArch++
          } else { Write-Host ("  Aviso: fragmento BUSCAR no coincidio en " + $RutaRel) -ForegroundColor Red }
        }
        if ($CambiosArch -gt 0) {
          $SalidaFinal = if ($UsaCRLF) { $Actual.Replace("`n", "`r`n") } else { $Actual }
          [System.IO.File]::WriteAllText($RutaFinal, $SalidaFinal, [System.Text.UTF8Encoding]::new($false))
          Write-Host ("  OK [EDITADO PARCIAL (" + $CambiosArch + " cambio/s)]: " + $RutaRel) -ForegroundColor Green
          $Cont++
        }
      }
      if ($Cont -gt 0) {
        [console]::beep(1000, 150)
        Write-Host ("Listo! " + $Cont + " archivo(s) actualizados.") -ForegroundColor Cyan
        git status -s
      }
    }
  } catch { Write-Host ("Error: " + $_.Exception.Message) -ForegroundColor Red }
}