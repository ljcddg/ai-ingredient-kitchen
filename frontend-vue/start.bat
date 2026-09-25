@echo off
chcp 65001 > nul
setlocal

REM ============================================================
REM  AI 灵感厨房 · 前端（Vue 版）
REM
REM  这是「迁移试验版」，与原生版 frontend\ 并存、互不干扰。
REM  想回滚（删掉 Vue 版用回原生版）→ 双击项目根目录的
REM  rollback-frontend.bat
REM
REM  前置：后端服务需在跑（gateway 7000 / account 8081 / dish 8082）
REM ============================================================

cd /d "%~dp0"

echo.
echo ============================================
echo  AI 灵感厨房 · Vue 版前端
echo ============================================
echo.

if not exist "node_modules" (
    echo [1/2] 首次运行，正在安装依赖 ...
    call npm install
    if errorlevel 1 (
        echo.
        echo [错误] 依赖安装失败，请检查 Node 是否已安装（需 Node 18+）
        pause
        exit /b 1
    )
) else (
    echo [1/2] 依赖已就绪
)

echo [2/2] 启动开发服务器 http://127.0.0.1:5500
echo.
echo   * 接口已由 Vite 反向代理到网关 7000（页面与接口同源，无跨域）
echo   * 按 Ctrl+C 停止
echo.

call npm run dev

pause
