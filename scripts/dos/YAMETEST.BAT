@echo off
del PPP.OK >NUL 2>NUL
del DNS.OK >NUL 2>NUL
del SUITE.OK >NUL 2>NUL
del DNS.OUT >NUL 2>NUL

echo Starting LSppp dial-up...
LSPPP.EXE /d:1 /n:2 /B:19200 /V:60 /S:10.64.0.2 /D:15,1,0
if errorlevel 1 goto ppp_failed
echo OK>PPP.OK

set MTCPCFG=C:\MTCP.CFG
echo Resolving ci.yame.test through YAME...
DNSTEST.EXE -name ci.yame.test -timeout 10 > DNS.OUT
if errorlevel 1 goto dns_failed

echo OK>DNS.OK
echo OK>SUITE.OK
goto done

:ppp_failed
echo FAIL>PPP.OK
echo FAIL>SUITE.OK
goto done

:dns_failed
echo FAIL>DNS.OK
echo FAIL>SUITE.OK

:done
exit
