@echo off
setlocal
if defined JAVA_HOME (set "JAVA=%JAVA_HOME%\bin\java.exe") else (set "JAVA=java")
"%JAVA%" -XX:TieredStopAtLevel=1 -XX:+UseSerialGC %SKIFF_JAVA_OPTS% -jar "%~dp0skiff.jar" %*
exit /b %ERRORLEVEL%
