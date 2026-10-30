@echo off
set JAVA_HOME=D:\Installs\Java\jdk-21.0.11+10
set PATH=%JAVA_HOME%\bin;D:\Installs\Apache\maven-3.9.16\bin;%PATH%
cd /d D:\Documents\Spring\TradeIO-5
call mvn -q -o test -Dtest=PublicPagesTest,AuthControllerSignupTest,AuthControllerTest,AppPageControllerTest,MainControllerAdminPageTest,MainControllerUserPageTest > D:\Documents\Spring\TradeIO-5\logs\public-tests.log 2>&1
echo DONE_EXIT=%errorlevel% >> D:\Documents\Spring\TradeIO-5\logs\public-tests.log
