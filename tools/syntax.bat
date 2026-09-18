@echo off
REM Fast syntax-only pass over payload translation units.
REM   tools\syntax.bat payload\src\bridge\Minecraft.cpp [more.cpp ...]
setlocal
call "C:\Program Files\Microsoft Visual Studio\18\Community\VC\Auxiliary\Build\vcvars64.bat" >nul
set JH=C:\Program Files\Eclipse Adoptium\jdk-21.0.12.101-hotspot
pushd "%~dp0.."
cl /nologo /c /Zs /std:c++20 /EHsc /W4 /permissive- /Zc:__cplusplus /utf-8 ^
   /DWIN32_LEAN_AND_MEAN /DNOMINMAX /D_CRT_SECURE_NO_WARNINGS /DUNICODE /D_UNICODE ^
   /Ipayload\src /Ipayload\thirdparty /Ipayload\thirdparty\minhook\include ^
   /I"%JH%\include" /I"%JH%\include\win32" ^
   %*
set RC=%ERRORLEVEL%
popd
exit /b %RC%
