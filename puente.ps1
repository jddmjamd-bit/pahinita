$ultimoTexto = ""
Clear-Host
Write-Host "====================================================" -ForegroundColor Cyan
Write-Host " 🚀 PUENTE ARGON 4 ACTIVO (Escuchando portapapeles)" -ForegroundColor Cyan
Write-Host " Dale al botón 'Copiar' en el bloque de Argon 4." -ForegroundColor Gray
Write-Host "====================================================" -ForegroundColor Cyan

while ($true) {
    Start-Sleep -Milliseconds 500
    try {
        $clip = Get-Clipboard -Raw -ErrorAction SilentlyContinue
        if ($null -ne $clip -and $clip -ne $ultimoTexto -and $clip.TrimStart().StartsWith("### APLICAR-CAMBIOS ###")) {
            $ultimoTexto = $clip
            Write-Host "`n⚡ Bloque de Argon 4 detectado. Aplicando cambios..." -ForegroundColor Yellow

            $partes = $clip -split "<<<ARCHIVO:\s*"
            $contador = 0

            for ($i = 1; $i -lt $partes.Count; $i++) {
                if ($partes[$i] -match "^([^\r\n]+?)>>>\r?\n([\s\S]*?)<<<FIN-ARCHIVO>>>") {
                    $rutaSolicitada = $matches[1].Trim()
                    $contenido = $matches[2]
                    $rutaFinal = Join-Path (Get-Location).Path $rutaSolicitada

                    # Si es un roadmap y tiene otro nombre o subcarpeta en el proyecto, encontrar el existente
                    if (!(Test-Path $rutaFinal) -and $rutaSolicitada -match "roadmap") {
                        $encontrado = Get-ChildItem -Path (Get-Location).Path -Filter "*roadmap*.md" -Recurse -File -ErrorAction SilentlyContinue |
                            Where-Object { $_.FullName -notmatch '\\(node_modules|target|\.git)\\' } |
                            Select-Object -First 1
                        if ($null -ne $encontrado) {
                            $rutaFinal = $encontrado.FullName
                        }
                    }

                    $carpeta = Split-Path $rutaFinal -Parent
                    if (!(Test-Path $carpeta)) { New-Item -ItemType Directory -Path $carpeta -Force | Out-Null }

                    [System.IO.File]::WriteAllText($rutaFinal, $contenido, [System.Text.UTF8Encoding]::new($false))
                    Write-Host "  ✅ Actualizado: $rutaFinal" -ForegroundColor Green
                    $contador++
                }
            }

            if ($contador -gt 0) {
                [console]::beep(1000, 180)
                Write-Host "🎉 ¡$contador archivo(s) editados con éxito!" -ForegroundColor Cyan
                git status --short
            }
        }
    } catch { }
}
