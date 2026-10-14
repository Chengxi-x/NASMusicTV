@echo off
set JAVA_HOME=C:\Program Files\Android\Android Studio\jbr
set PATH=%JAVA_HOME%\bin;%PATH%
C:\Users\hxzha\AppData\Local\Android\Sdk\build-tools\37.0.0\apksigner.bat verify --print-certs app\build\outputs\apk\release\NASMusicTV-release-v2-38-11.apk
