@echo off
chcp 65001 > nul
setlocal

REM ============================================================
REM  前端一键回滚：把 Vue 版换回原生版
REM  用法：双击本文件即可
REM ============================================================

set ROOT=%~dp0
set BACKUP_DIR=%ROOT%backup

echo.
echo ============================================
echo  前端回滚工具（Vue 版 -^> 原生版）
echo ============================================
echo.

if not exist "%BACKUP_DIR%" (
    echo [错误] 找不到备份目录：%BACKUP_DIR%
    echo.
    pause
    exit /b 1
)

echo 可用的原生前端备份：
echo.
set /a IDX=0
for /f "delims=" %%D in ('dir /b /ad /o-n "%BACKUP_DIR%\frontend-native-*" 2^>nul') do (
    set /a IDX+=1
    echo   [!IDX!] %%D
)
echo.

if %IDX%==0 (
    echo [错误] 备份目录下没有 frontend-native-* 快照
    echo.
    pause
    exit /b 1
)

set /p PICK=请输入要回滚的编号（直接回车 = 最新的那个）:
if "%PICK%"=="" set PICK=1

set /a C=0
for /f "delims=" %%D in ('dir /b /ad /o-n "%BACKUP_DIR%\frontend-native-*"') do (
    set /a C+=1
    if !C!==%PICK% set SRC=%BACKUP_DIR%\%%D
)

if not defined SRC (
    echo [错误] 编号 %PICK% 不存在
    echo.
    pause
    exit /b 1
)

echo.
echo 将要回滚：%SRC%
echo 目标目录：%ROOT%frontend
echo.
echo [警告] 这会覆盖当前 frontend\ 下的同名文件，当前 Vue 版改动将丢失！
set /p OK=确认继续？(y/N):
if /i not "%OK%"=="y" (
    echo 已取消。
    pause
    exit /b 0
)

REM 先把当前前端另存一份，避免回滚后彻底找不回 Vue 版
set STAMP=%date:~0,4%%date:~5,2%%date:~8,2%-%time:~0,2%%time:~3,2%%time:~6,2%
set STAMP=%STAMP: =0%
set SAFE=%BACKUP_DIR%\frontend-before-rollback-%STAMP%
echo.
echo [1/3] 暂存当前前端到 %SAFE% ...
xcopy "%ROOT%frontend" "%SAFE%" /E /I /Y /Q > nul

echo [2/3] 执行回滚 ...
xcopy "%SRC%" "%ROOT%frontend" /E /I /Y /Q > nul

echo [3/3] 完成。
echo.
echo 回滚完成！当前 frontend\ 已恢复为原生版。
echo 回滚前的版本（可能是 Vue 版）已暂存在：
echo   %SAFE%
echo.
echo 提示：回滚后请重启前端服务（start.bat）。
echo.
pause
