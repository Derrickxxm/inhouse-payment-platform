@echo off
setlocal
set "BASE_DIR=%~dp0"
set "WRAPPER_JAR=%BASE_DIR%.mvn\wrapper\maven-wrapper.jar"
if not exist "%WRAPPER_JAR%" (
  powershell -NoProfile -Command "$ErrorActionPreference='Stop'; $p=Join-Path $env:BASE_DIR '.mvn/wrapper/maven-wrapper.properties'; $url=(Get-Content $p | Where-Object { $_ -like 'wrapperUrl=*' }).Substring(11); $temp=$env:WRAPPER_JAR+'.tmp'; try { Invoke-WebRequest -UseBasicParsing -Uri $url -OutFile $temp; Move-Item -Force $temp $env:WRAPPER_JAR } finally { if (Test-Path $temp) { Remove-Item $temp } }"
  if errorlevel 1 exit /b 1
)
if defined JAVA_HOME (
  set "JAVA_EXE=%JAVA_HOME%\bin\java.exe"
) else (
  set "JAVA_EXE=java.exe"
)
"%JAVA_EXE%" "-Dmaven.multiModuleProjectDirectory=%BASE_DIR%." -classpath "%WRAPPER_JAR%" org.apache.maven.wrapper.MavenWrapperMain %*
exit /b %ERRORLEVEL%
