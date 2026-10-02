@echo off
rem US-052-thread-end-probe-build.bat
rem Evidence for backlog story US-052 (Glass Windows toolkit teardown). NOT shipped and NOT built by Maven.
rem Builds probe.dll, probe_dm.dll, probe_dl.dll and probe_exe.exe next to the sources, with the flags that
rem modules/javafx.graphics/native/win.cmake gives glass.dll in a Release build: JFX_COMMON_COMPILE_OPTIONS,
rem /DUNICODE /D_UNICODE, /MD (CMAKE_MSVC_RUNTIME_LIBRARY) and CMAKE_SHARED_LINKER_FLAGS.
rem Needs Visual Studio 2022; set VCVARS to another vcvars64.bat if it is not the Community edition.
setlocal
cd /d "%~dp0"
set "VS2022=C:\Program Files\Microsoft Visual Studio\2022\Community"
if not defined VCVARS set "VCVARS=%VS2022%\VC\Auxiliary\Build\vcvars64.bat"
call "%VCVARS%" >nul || exit /b 1
set P=US-052-thread-end-probe
set CFLAGS=/nologo /W3 /EHsc /MD /O2 /DNDEBUG /D_DISABLE_CONSTEXPR_MUTEX_CONSTRUCTOR /DINLINE=__inline /DWIN32 /DIAL
set CFLAGS=%CFLAGS% /D_LITTLE_ENDIAN /DWIN32_LEAN_AND_MEAN /DUNICODE /D_UNICODE
set LFLAGS=/nologo /manifest /opt:REF /incremental:no /dynamicbase /nxcompat
cl 2>&1 | findstr /c:"Version"
cl %CFLAGS% /LD %P%-dll.cpp /Foprobe.obj /Feprobe.dll /link %LFLAGS% user32.lib || exit /b 1
cl %CFLAGS% /DPROBE_DLLMAIN /LD %P%-dll.cpp /Foprobe_dm.obj /Feprobe_dm.dll /link %LFLAGS% user32.lib || exit /b 1
set DELAY=/DELAYLOAD:user32.dll delayimp.lib
cl %CFLAGS% /LD %P%-delayload.cpp /Foprobe_dl.obj /Feprobe_dl.dll /link %LFLAGS% %DELAY% user32.lib || exit /b 1
cl %CFLAGS% %P%-exe.cpp /Foprobe_exe.obj /Feprobe_exe.exe /link %LFLAGS% user32.lib || exit /b 1
for %%D in (probe.dll probe_dm.dll) do (
    echo ---- dumpbin /EXPORTS %%D
    dumpbin /nologo /EXPORTS %%D | findstr /c:"probe_"
    echo ---- dumpbin /DEPENDENTS %%D
    dumpbin /nologo /DEPENDENTS %%D | findstr /i ".dll"
    echo ---- dumpbin /TLS %%D
    dumpbin /nologo /TLS %%D
)
echo ---- dumpbin /IMPORTS probe_dl.dll
dumpbin /nologo /IMPORTS probe_dl.dll | findstr /i /c:"delay load" /c:".dll" /c:"MessageW"
endlocal
