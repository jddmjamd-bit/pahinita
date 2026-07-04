@REM Maven Wrapper startup script for Windows
@REM Downloads Maven if not present and runs it

@echo off
setlocal

set MAVEN_VERSION=3.9.6
set MAVEN_DIR=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%
set MAVEN_BIN=%MAVEN_DIR%\bin\mvn.cmd

if exist "%MAVEN_BIN%" (
    "%MAVEN_BIN%" %*
    exit /b %ERRORLEVEL%
)

echo Downloading Maven %MAVEN_VERSION%...
mkdir "%MAVEN_DIR%" 2>nul

set DOWNLOAD_URL=https://repo.maven.apache.org/maven2/org/apache/maven/apache-maven/%MAVEN_VERSION%/apache-maven-%MAVEN_VERSION%-bin.zip
set ZIP_FILE=%TEMP%\apache-maven-%MAVEN_VERSION%-bin.zip

powershell -Command "Invoke-WebRequest -Uri '%DOWNLOAD_URL%' -OutFile '%ZIP_FILE%'"
powershell -Command "Expand-Archive -Path '%ZIP_FILE%' -DestinationPath '%MAVEN_DIR%\..'"

@REM After extract, the bin is at wrapper\dists\apache-maven-VERSION\bin
set EXTRACTED_DIR=%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%
if not exist "%EXTRACTED_DIR%\bin\mvn.cmd" (
    @REM Try the nested directory
    for /d %%i in ("%USERPROFILE%\.m2\wrapper\dists\apache-maven-%MAVEN_VERSION%*") do (
        set EXTRACTED_DIR=%%i
    )
)

"%EXTRACTED_DIR%\bin\mvn.cmd" %*
exit /b %ERRORLEVEL%
