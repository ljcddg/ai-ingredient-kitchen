@echo off
setlocal

rem ================================================================
rem  dish-service  -  local launcher
rem
rem  为什么必须加 -Dfile.encoding=UTF-8（这个坑很难排查，别删掉）：
rem
rem  Java 17 在 Windows 中文环境下 file.encoding 默认仍是 GBK，
rem  （默认改成 UTF-8 是 Java 18 才引入的 JEP 400）。
rem  Nacos 上的 dish-service.yaml 带中文注释，Spring Cloud Alibaba 的
rem  NacosDataParserHandler 会用这个默认编码去解码，于是解析 UTF-8 字节
rem  直接抛：
rem      org.yaml.snakeyaml.error.YAMLException:
rem          java.nio.charset.MalformedInputException: Input length = 1
rem
rem  因为 spring.config.import 写的是 optional: 前缀，这个异常只被记成一行
rem  ERROR 就过去了，不会中断启动 —— 真正的症状变成后面那句更难联想的：
rem      Failed to instantiate HikariDataSource:
rem          Failed to determine a suitable driver class
rem
rem  在 IDEA 里跑不会有这个问题：IDEA 启动 Java 时默认就带了
rem  -Dfile.encoding=UTF-8，所以只有命令行手工启动才会踩到。
rem ================================================================

set "SERVICE_JAR=%~dp0target\dish-service-0.0.1-SNAPSHOT.jar"
set "JAVA_EXE=D:\idea\java\jdk17\jdk-17.0.12\bin\java.exe"

if not exist "%SERVICE_JAR%" (
    echo.
    echo [ERROR] 未找到 %SERVICE_JAR%
    echo         请先打包：mvn clean package -DskipTests
    echo.
    pause
    exit /b 1
)

rem 端口占用检查：避免第二个实例起来了却绑不上端口，看日志还得排查半天
set "SVC_PID="
for /f "tokens=5" %%P in ('netstat -ano ^| findstr /C:":8082" ^| findstr /C:"LISTENING"') do set "SVC_PID=%%P"
if defined SVC_PID (
    echo.
    echo [ERROR] 端口 8082 已被 PID %SVC_PID% 占用，dish-service 可能已经在跑了。
    echo         先关掉那个进程，或改端口后再启动。
    echo.
    pause
    exit /b 1
)

echo ==================================================
echo   dish-service   local
echo --------------------------------------------------
echo   JAR     : %SERVICE_JAR%
echo   HTTP    : http://localhost:8082
echo   ES      : http://localhost:9200   (需先启动 ES 7.16.2)
echo   Nacos   : http://localhost:8848
echo   MySQL   : localhost:3306/project0914
echo --------------------------------------------------
echo   接口：POST http://localhost:8082/search
echo         （经网关则是 POST http://localhost:7000/api/dish/search）
echo   KEEP THIS WINDOW OPEN - closing it stops the service.
echo ==================================================
echo.

"%JAVA_EXE%" -Dfile.encoding=UTF-8 -jar "%SERVICE_JAR%" --server.port=8082

echo.
echo [dish-service has exited]
pause
