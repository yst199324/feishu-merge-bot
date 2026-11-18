@echo off
rem Start merge-bot. Config is read from application.yml in the same folder.
rem Edit that file and restart; no rebuild needed.
cd /d %~dp0
java -jar merge-bot.jar --spring.config.additional-location=file:./
pause
