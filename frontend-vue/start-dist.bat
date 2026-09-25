@echo off
chcp 65001 > nul
setlocal

REM ============================================================
REM  AI 灵感厨房 · Vue 版（用构建产物 dist 跑）
REM
REM  与 start.bat 的区别：
REM    start.bat      = vite dev server，改代码自动热更新（开发用）
REM    本脚本         = 纯静态 + 反代 /api，启动即用、稳（演示/给别人看用）
REM
REM  前置：后端服务需在跑（gateway 7000 / account 8081 / dish 8082）
REM ============================================================

cd /d "%~dp0"

echo.
echo ============================================
echo  AI 灵感厨房 · Vue 版（dist）
echo ============================================
echo.

if not exist "dist\index.html" (
    echo [1/2] 还没有构建产物，正在构建 ...
    if not exist "node_modules" (
        echo       先安装依赖 ...
        call npm install
        if errorlevel 1 (
            echo [错误] 依赖安装失败
            pause
            exit /b 1
        )
    )
    call npm run build
    if errorlevel 1 (
        echo [错误] 构建失败
        pause
        exit /b 1
    )
) else (
    echo [1/2] 已有构建产物（想更新请先手动 npm run build）
)

echo [2/2] 启动服务 http://127.0.0.1:5600
echo.
echo   * 接口已反向代理到网关 7000（页面与接口同源，无跨域）
echo   * 按 Ctrl+C 停止
echo.

python serve-dist.py 5600

pause
